package dtv.mobile.platform.bilibili

import dtv.mobile.repo.DanmakuMessage
import dtv.mobile.util.AppLog
import dtv.mobile.util.currentTimeMillis
import dtv.mobile.util.inflateZlibOrNull
import dtv.mobile.util.md5Hex
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsText
import io.ktor.websocket.Frame
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class BilibiliDanmakuClientIos(
  private val httpClient: HttpClient,
  private val cookieProvider: () -> String?,
) {
  companion object {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // https://github.com/SocialSisterYi/bilibili-API-collect (WBI signature)
    private val MIXIN_KEY_ENC_TAB = intArrayOf(
      46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
      27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
      37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
      22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52,
    )
  }

  private data class NavInfo(
    val imgKey: String,
    val subKey: String,
    val mid: Long?,
  )

  private var cachedNavInfo: NavInfo? = null
  private var cachedWbiAtMs: Long = 0L

  private data class DanmuInfo(
    val roomId: Int,
    val uid: Long,
    val token: String,
    val endpoints: List<Endpoint>,
  )

  private data class Endpoint(
    val host: String,
    val wssPort: Int,
  )

  private val wsClient = HttpClient(Darwin) {
    install(WebSockets)
  }

  private fun takeFilename(url: String): String? {
    val slash = url.lastIndexOf('/')
    if (slash < 0) return null
    val tail = url.substring(slash + 1)
    val dot = tail.lastIndexOf('.')
    if (dot <= 0) return null
    return tail.substring(0, dot)
  }

  private fun urlEncodedWbiComponent(input: String): String {
    val out = StringBuilder(input.length + 16)
    for (ch in input) {
      when {
        ch.isLetterOrDigit() || ch == '-' || ch == '_' || ch == '.' || ch == '~' -> out.append(ch)
        ch == '!' || ch == '\'' || ch == '(' || ch == ')' || ch == '*' -> Unit
        else -> {
          val bytes = ch.toString().encodeToByteArray()
          for (raw in bytes) {
            val v = raw.toInt() and 0xFF
            out.append('%')
            out.append("0123456789ABCDEF"[v ushr 4])
            out.append("0123456789ABCDEF"[v and 0x0F])
          }
        }
      }
    }
    return out.toString()
  }

  private fun getMixinKey(orig: String): String {
    val bytes = orig.encodeToByteArray()
    val take = min(32, MIXIN_KEY_ENC_TAB.size)
    val sb = StringBuilder(32)
    for (i in 0 until take) {
      val idx = MIXIN_KEY_ENC_TAB[i]
      val b = bytes.getOrNull(idx) ?: 0
      sb.append((b.toInt() and 0xFF).toChar())
    }
    return sb.toString()
  }

  private suspend fun getNavInfo(): NavInfo {
    val now = currentTimeMillis()
    cachedNavInfo?.let { if (now - cachedWbiAtMs < 6 * 60 * 60 * 1000L) return it }

    val url = "https://api.bilibili.com/x/web-interface/nav"
    val cookie = cookieProvider()
    val text = httpClient.get(url) {
      headers {
        append("User-Agent", "Mozilla/5.0 (X11; Linux x86_64; rv:138.0) Gecko/20100101 Firefox/138.0")
        append("Referer", "https://www.bilibili.com/")
        if (!cookie.isNullOrBlank()) append("Cookie", cookie)
      }
    }.bodyAsText()

    val root = json.parseToJsonElement(text).jsonObject
    val data = root["data"]?.jsonObject ?: error("B站 nav data 为空")
    val mid = data["mid"]?.jsonPrimitive?.content?.toLongOrNull()
    val wbi = data["wbi_img"]?.jsonObject ?: error("B站 nav wbi_img 为空")
    val imgUrl = wbi["img_url"]?.jsonPrimitive?.content?.trim().orEmpty()
    val subUrl = wbi["sub_url"]?.jsonPrimitive?.content?.trim().orEmpty()
    val imgKey = takeFilename(imgUrl) ?: error("B站 img_key 解析失败")
    val subKey = takeFilename(subUrl) ?: error("B站 sub_key 解析失败")
    val nav = NavInfo(imgKey = imgKey, subKey = subKey, mid = mid)

    cachedNavInfo = nav
    cachedWbiAtMs = now
    return nav
  }

  private suspend fun signedWbiQuery(params: List<Pair<String, String>>): String {
    val (imgKey, subKey, _) = getNavInfo()
    val mixinKey = getMixinKey(imgKey + subKey)
    val wts = (currentTimeMillis() / 1000L).toString()

    val all = (params + ("wts" to wts)).sortedBy { it.first }
    val query = all.joinToString("&") { (k, v) ->
      "${urlEncodedWbiComponent(k)}=${urlEncodedWbiComponent(v)}"
    }
    val wRid = md5Hex(query + mixinKey)
    return "$query&w_rid=$wRid"
  }

  private suspend fun fetchDanmuInfo(roomId: String): DanmuInfo {
    val query = signedWbiQuery(
      listOf(
        "id" to roomId,
        "type" to "0",
        "web_location" to "444.8",
      ),
    )
    val url = "https://api.live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo?$query"
    val cookie = cookieProvider()
    val text = httpClient.get(url) {
      headers {
        append("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
        append("Referer", "https://live.bilibili.com/")
        if (!cookie.isNullOrBlank()) append("Cookie", cookie)
      }
    }.bodyAsText()

    val root = json.parseToJsonElement(text).jsonObject
    val code = root["code"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1
    if (code != 0) {
      val msg = root["message"]?.jsonPrimitive?.content
      AppLog.w("DTV-Bilibili", "getDanmuInfo failed code=$code msg=$msg roomId=$roomId")
      error("B站弹幕参数获取失败(code=$code)")
    }

    val data = root["data"]?.jsonObject ?: error("B站弹幕参数为空")
    val token = data["token"]?.jsonPrimitive?.content?.trim().orEmpty()
    val realRoomId = data["roomid"]?.jsonPrimitive?.content?.toIntOrNull() ?: roomId.toIntOrNull() ?: 0

    val hostList = data["host_list"]?.jsonArray ?: JsonArray(emptyList())
    val endpoints =
      hostList.asSequence()
        .mapNotNull { it.jsonObject }
        .mapNotNull { obj ->
          val host = obj["host"]?.jsonPrimitive?.content?.trim().orEmpty()
          val port = obj["wss_port"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
          if (host.isBlank() || port <= 0) null else Endpoint(host = host, wssPort = port)
        }
        .distinctBy { "${it.host}:${it.wssPort}" }
        .sortedWith(compareBy<Endpoint>({ if (it.wssPort == 443) 0 else 1 }, { it.host }))
        .toList()
    if (token.isBlank()) error("B站弹幕 token 为空")

    val resolvedEndpoints = endpoints.ifEmpty { listOf(Endpoint(host = "broadcastlv.chat.bilibili.com", wssPort = 443)) }
    val uid = runCatching { getNavInfo().mid }.getOrNull() ?: 0L
    return DanmuInfo(roomId = realRoomId, uid = uid, token = token, endpoints = resolvedEndpoints)
  }

  // --- big-endian packet codec (no java.nio on iOS) ---

  private fun buildPacket(op: Int, body: ByteArray = ByteArray(0), ver: Int = 1, seq: Int = 1): ByteArray {
    val packetLen = 16 + body.size
    val out = ByteArray(packetLen)
    var o = 0
    fun putIntBe(v: Int) {
      out[o++] = ((v ushr 24) and 0xFF).toByte()
      out[o++] = ((v ushr 16) and 0xFF).toByte()
      out[o++] = ((v ushr 8) and 0xFF).toByte()
      out[o++] = (v and 0xFF).toByte()
    }
    fun putShortBe(v: Int) {
      out[o++] = ((v ushr 8) and 0xFF).toByte()
      out[o++] = (v and 0xFF).toByte()
    }
    putIntBe(packetLen)
    putShortBe(16)
    putShortBe(ver)
    putIntBe(op)
    putIntBe(seq)
    body.copyInto(out, o)
    return out
  }

  private class Packet(val ver: Int, val op: Int, val body: ByteArray)

  private fun ByteArray.getIntBe(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 24) or
      ((this[offset + 1].toInt() and 0xFF) shl 16) or
      ((this[offset + 2].toInt() and 0xFF) shl 8) or
      (this[offset + 3].toInt() and 0xFF)

  private fun ByteArray.getShortBe(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 8) or (this[offset + 1].toInt() and 0xFF)

  private fun parsePackets(bytes: ByteArray): List<Packet> {
    val out = ArrayList<Packet>()
    var offset = 0
    while (offset + 16 <= bytes.size) {
      val len = bytes.getIntBe(offset)
      if (len <= 0 || offset + len > bytes.size) break
      val headerLen = bytes.getShortBe(offset + 4)
      val ver = bytes.getShortBe(offset + 6)
      val op = bytes.getIntBe(offset + 8)
      val bodyStart = offset + headerLen
      val bodyEnd = offset + len
      val body = if (bodyStart in 0..bodyEnd && bodyEnd <= bytes.size) bytes.copyOfRange(bodyStart, bodyEnd) else ByteArray(0)
      out.add(Packet(ver = ver, op = op, body = body))
      offset += len
    }
    return out
  }

  private fun decodeChatMessageOrNull(packetBody: ByteArray): DanmakuMessage? {
    val root = runCatching { json.parseToJsonElement(packetBody.decodeToString()).jsonObject }.getOrNull() ?: return null
    val cmd = root["cmd"]?.jsonPrimitive?.content?.orEmpty() ?: return null
    if (!cmd.startsWith("DANMU_MSG")) return null

    val info = root["info"] as? JsonArray ?: return null
    val content = info.getOrNull(1)?.jsonPrimitive?.content?.trim().orEmpty()
    val user = (info.getOrNull(2) as? JsonArray)?.getOrNull(1)?.jsonPrimitive?.content?.trim().orEmpty()
    if (content.isBlank()) return null
    return DanmakuMessage(roomId = "", user = user.ifBlank { "匿名" }, content = content)
  }

  fun observe(roomId: String): Flow<DanmakuMessage> = flow {
    val info = runCatching { fetchDanmuInfo(roomId) }
      .onFailure { AppLog.e("DTV-Bilibili", "fetch danmu info failed roomId=$roomId", it) }
      .getOrNull()
      ?: return@flow

    val authJson =
      """{"uid":${info.uid.coerceAtLeast(0L)},"roomid":${info.roomId},"protover":1,"platform":"web","type":2,"key":"${info.token}"}""".encodeToByteArray()
    val authPacket = buildPacket(op = 7, body = authJson, ver = 1, seq = 1)
    val heartbeatPacket = buildPacket(op = 2, body = ByteArray(0), ver = 1, seq = 1)

    var backoffMs = 1_200L
    while (currentCoroutineContext().isActive) {
      var connectedOnce = false

      for (ep in info.endpoints) {
        if (!currentCoroutineContext().isActive) break
        val wsUrl = if (ep.wssPort == 443) "wss://${ep.host}/sub" else "wss://${ep.host}:${ep.wssPort}/sub"
        AppLog.i("DTV-Bilibili", "danmaku ws connecting url=$wsUrl roomId=$roomId(real=${info.roomId})")

        var authOk = false
        try {
          wsClient.webSocket(urlString = wsUrl, request = {
            header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
            header("Referer", "https://live.bilibili.com/")
            header("Origin", "https://live.bilibili.com")
          }) {
            connectedOnce = true
            send(Frame.Binary(fin = true, data = authPacket))

            launch {
              while (isActive) {
                delay(30_000L)
                val sent = runCatching { send(Frame.Binary(fin = true, data = heartbeatPacket)) }.isSuccess
                if (!sent) break
              }
            }

            for (frame in incoming) {
              if (frame is Frame.Close) break
              if (frame !is Frame.Binary) continue
              for (p in parsePackets(frame.data)) {
                when (p.op) {
                  8 -> {
                    authOk = true
                    AppLog.i("DTV-Bilibili", "danmaku auth ok roomId=$roomId ver=${p.ver}")
                  }
                  5 -> {
                    if (p.ver == 2) {
                      val inflated = inflateZlibOrNull(p.body) ?: continue
                      for (inner in parsePackets(inflated)) {
                        if (inner.op != 5) continue
                        val msg = decodeChatMessageOrNull(inner.body) ?: continue
                        emit(msg.copy(roomId = roomId))
                      }
                    } else {
                      val msg = decodeChatMessageOrNull(p.body) ?: continue
                      emit(msg.copy(roomId = roomId))
                    }
                  }
                }
              }
            }
          }
        } catch (ce: CancellationException) {
          throw ce
        } catch (t: Throwable) {
          AppLog.e("DTV-Bilibili", "bilibili danmaku ws failure roomId=$roomId url=$wsUrl", t)
        }

        if (authOk) break
      }

      backoffMs = if (!connectedOnce) (backoffMs * 2).coerceAtMost(12_000L) else 1_600L
      if (!currentCoroutineContext().isActive) break
      delay(backoffMs)
    }
  }.buffer(capacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST)
}
