package dtv.mobile.platform.douyin

import dtv.mobile.repo.DanmakuMessage
import dtv.mobile.util.AppLog
import dtv.mobile.util.currentTimeMillis
import dtv.mobile.util.gunzipOrNull
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.headers
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLQueryComponent
import io.ktor.websocket.Frame
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import platform.Foundation.NSLock

/**
 * Douyin danmaku client (iOS, Ktor Darwin + shared DouyinProtoLite):
 * - GET the room page to resolve user_unique_id / room id / cookie
 * - WebMsSDK signature via JavaScriptCore, connect WebSocket, gunzip + protobuf decode
 */
class DouyinDanmakuClientIos(
  private val webApi: DouyinWebApiIos,
  private val httpClient: HttpClient,
) {
  data class PrimedContext(
    val roomId: String,
    val msToken: String?,
  )

  private data class RoomInit(
    val roomId: String,
    val userUniqueId: String,
    val cookieHeader: String,
  )

  companion object {
    private const val TAG = "DTV-Douyin"

    private const val USER_AGENT = DouyinWebApiIos.DEFAULT_USER_AGENT
    private const val REFERER_BASE = "https://live.douyin.com"

    private val WS_BASES = listOf(
      "wss://webcast5-ws-web-hl.douyin.com/webcast/im/push/v2/?",
      "wss://webcast3-ws-web-lq.douyin.com/webcast/im/push/v2/?",
      "wss://webcast5-ws-web-lf.douyin.com/webcast/im/push/v2/?",
    )

    private const val VERSION_CODE = "180800"
    private const val WEBCAST_SDK_VERSION = "1.3.0"
    private const val UPDATE_VERSION_CODE = "1.3.0"

    private const val HEARTBEAT_MS = 10_000L
  }

  private val wsClient = HttpClient(Darwin) {
    install(WebSockets)
  }

  private val webMsdkSigner = DouyinWebMsdkSignatureIos()

  private val primedLock = NSLock()
  private val primed = mutableMapOf<String, PrimedContext>()

  private fun <T> withPrimedLock(block: () -> T): T {
    primedLock.lock()
    try {
      return block()
    } finally {
      primedLock.unlock()
    }
  }

  fun prime(webRid: String, roomId: String, msToken: String?) {
    val rid = webRid.trim()
    val rId = roomId.trim()
    if (rid.isBlank() || rId.isBlank()) return
    primedLock.lock()
    try {
      primed[rid] = PrimedContext(roomId = rId, msToken = msToken)
    } finally {
      primedLock.unlock()
    }
    AppLog.i(TAG, "prime douyin danmaku webRid=$rid roomId=$rId msToken=${!msToken.isNullOrBlank()}")
  }

  fun observe(webRid: String): Flow<DanmakuMessage> = flow {
    val rid = webRid.trim()
    if (rid.isBlank()) error("webRid is blank")

    val hb = DouyinProtoLite.encodePushFrame(payloadType = "hb", logId = 0L, payload = ByteArray(0))
    var backoffMs = 1000L

    while (isActive) {
      val primedCtx = withPrimedLock { primed[rid] }

      val init = runCatching { resolveRoomInit(webRid = rid, primedRoomId = primedCtx?.roomId) }
        .onFailure { AppLog.e(TAG, "resolve room init failed webRid=$rid", it) }
        .getOrNull()

      if (init == null) {
        delay(backoffMs)
        backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
        continue
      }

      for (base in WS_BASES.shuffled(Random)) {
        if (!isActive) break
        val wsUrl = runCatching { buildWsUrl(base = base, init = init) }.getOrNull().orEmpty()
        if (wsUrl.isBlank()) continue

        AppLog.i(TAG, "douyin danmaku connecting webRid=$rid roomId=${init.roomId} base=$base")

        try {
          wsClient.webSocket(urlString = wsUrl, request = {
            header("accept", "application/json, text/plain, */*")
            header("accept-language", "zh-CN,zh;q=0.9,en;q=0.8")
            header("cache-control", "no-cache")
            header("pragma", "no-cache")
            header("User-Agent", USER_AGENT)
            header("Cookie", init.cookieHeader)
            header("Origin", REFERER_BASE)
            header("Referer", "$REFERER_BASE/$rid")
          }) {
            AppLog.i(TAG, "douyin danmaku ws opened webRid=$rid roomId=${init.roomId} base=$base")

            launch {
              while (isActive) {
                runCatching { send(Frame.Binary(fin = true, data = hb)) }.onFailure { break }
                delay(HEARTBEAT_MS)
              }
            }

            for (frame in incoming) {
              if (frame is Frame.Close) break
              if (frame !is Frame.Binary) continue

              val push = DouyinProtoLite.decodePushFrame(frame.data) ?: continue
              if (push.payloadType != "msg" || push.payload.isEmpty()) continue

              val decompressed = gunzipOrNull(push.payload) ?: continue
              val resp = DouyinProtoLite.decodeResponse(decompressed) ?: continue

              if (resp.needAck) {
                val ack = DouyinProtoLite.encodePushFrame(
                  payloadType = "ack",
                  logId = push.logId,
                  payload = resp.internalExt.encodeToByteArray(),
                )
                runCatching { send(Frame.Binary(fin = true, data = ack)) }
              }

              for (m in resp.messages) {
                if (m.method != "WebcastChatMessage") continue
                val chat = DouyinProtoLite.decodeChatMessage(m.payload) ?: continue
                emit(
                  DanmakuMessage(
                    roomId = rid,
                    user = chat.nick,
                    content = chat.content,
                    userLevel = chat.userLevel,
                    fansClubLevel = chat.fansClubLevel,
                  ),
                )
              }
            }
          }
          // WebSocket session ended (server close) — break and go through outer backoff.
          break
        } catch (ce: CancellationException) {
          throw ce
        } catch (t: Throwable) {
          AppLog.w(TAG, "douyin danmaku ws failure webRid=$rid base=$base: ${t.message}")
        }
      }

      delay(backoffMs)
      backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
    }
  }.buffer(capacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST)

  private suspend fun resolveRoomInit(webRid: String, primedRoomId: String?): RoomInit {
    val resp: HttpResponse = httpClient.get("$REFERER_BASE/$webRid") {
      headers {
        append("User-Agent", USER_AGENT)
        append("Referer", REFERER_BASE)
        append("Cookie", DouyinWebApiIos.DEFAULT_COOKIE)
      }
    }

    val cookieHeader = resp.headers.getAll("Set-Cookie")
      .orEmpty()
      .firstOrNull()
      ?.substringBefore(";")
      ?.trim()
      .orEmpty()
      .ifBlank { DouyinWebApiIos.DEFAULT_COOKIE }

    val html = resp.bodyAsText()
    val rawUserUniqueId = extractUserUniqueId(html)
    val userUniqueId = rawUserUniqueId.ifBlank { randomDigits(12) }

    val roomIdFromHtml = extractRoomId(html)
    val roomIdFromApi = runCatching { webApi.fetchRoomEnter(webRid).roomId?.trim().orEmpty() }.getOrDefault("")
    val roomId = primedRoomId?.trim().orEmpty().ifBlank { roomIdFromHtml }.ifBlank { roomIdFromApi }
    if (roomId.isBlank()) error("Cannot resolve roomId for webRid=$webRid")

    return RoomInit(roomId = roomId, userUniqueId = userUniqueId, cookieHeader = cookieHeader)
  }

  private suspend fun buildWsUrl(base: String, init: RoomInit): String {
    val ts = currentTimeMillis()
    val urlPrefix =
      base +
        listOf(
          "app_name=douyin_web",
          "version_code=$VERSION_CODE",
          "webcast_sdk_version=$WEBCAST_SDK_VERSION",
          "update_version_code=$UPDATE_VERSION_CODE",
          "compress=gzip",
          "cursor=${encode("h-1_t-${ts}_r-1_d-1_u-1")}",
          "host=${encode("https://live.douyin.com")}",
          "aid=6383",
          "live_id=1",
          "did_rule=3",
          "debug=false",
          "maxCacheMessageNumber=20",
          "endpoint=live_pc",
          "support_wrds=1",
          "im_path=${encode("/webcast/im/fetch/")}",
          "user_unique_id=${init.userUniqueId}",
          "device_platform=web",
          "cookie_enabled=true",
          "screen_width=1920",
          "screen_height=1080",
          "browser_language=zh-CN",
          "browser_platform=Win32",
          "browser_name=Mozilla",
          "browser_version=${encode(USER_AGENT.removePrefix("Mozilla/"))}",
          "browser_online=true",
          "tz_name=Asia/Shanghai",
          "identity=audience",
          "room_id=${init.roomId}",
          "heartbeatDuration=0",
        ).joinToString("&")

    val sig = runCatching {
      webMsdkSigner.signature(
        roomId = init.roomId,
        userUniqueId = init.userUniqueId,
        webcastSdkVersion = WEBCAST_SDK_VERSION,
        userAgent = USER_AGENT,
      )
    }.getOrNull().orEmpty()

    if (sig.isBlank()) return ""
    return "$urlPrefix&signature=${encode(sig)}"
  }

  private fun extractUserUniqueId(html: String): String {
    val patterns = listOf(
      Regex("""\\\"user_unique_id\\\":\\\"(\d+)\\\""""),
      Regex(""""user_unique_id"\s*:\s*"(\d+)""""),
    )
    for (re in patterns) {
      val id = re.find(html)?.groupValues?.getOrNull(1)?.trim().orEmpty()
      if (id.isNotBlank()) return id
    }
    return ""
  }

  private fun extractRoomId(html: String): String {
    val patterns = listOf(
      Regex("""\\\"roomInfo\\\":\{\\\"room\\\":\{\\\"id_str\\\":\\\"(\d+)\\\""""),
      Regex(""""id_str"\s*:\s*"(\d+)""""),
    )
    for (re in patterns) {
      val id = re.find(html)?.groupValues?.getOrNull(1)?.trim().orEmpty()
      if (id.isNotBlank()) return id
    }
    return ""
  }

  private fun randomDigits(n: Int): String {
    val sb = StringBuilder(n)
    repeat(n) { sb.append(('0'.code + Random.nextInt(10)).toChar()) }
    return sb.toString()
  }

  private fun encode(s: String): String = s.encodeURLQueryComponent()
}
