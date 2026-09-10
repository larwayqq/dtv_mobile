package dtv.mobile.platform.huya

import dtv.mobile.repo.DanmakuMessage
import dtv.mobile.util.AppLog
import dtv.mobile.util.currentTimeMillis
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsText
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.min

class HuyaDanmakuClientIos(
  private val httpClient: HttpClient,
) {
  private val wsClient = HttpClient(Darwin) {
    install(WebSockets)
  }

  private suspend fun fetchWsInfo(roomIdOrUrl: String): HuyaWsInfo {
    val rid = HuyaDanmakuProtocol.toRoomId(roomIdOrUrl)
    val page = httpClient.get("https://www.huya.com/$rid") {
      headers {
        append("User-Agent", HuyaDanmakuProtocol.UA)
        append("Referer", "https://www.huya.com/")
      }
    }.bodyAsText()

    var ayyuid = HuyaDanmakuProtocol.resolveAyyuidFromPage(page)
    if (ayyuid == null) {
      val body = runCatching {
        httpClient.get("https://mp.huya.com/cache.php?m=Live&do=profileRoom&roomid=$rid") {
          header("User-Agent", HuyaDanmakuProtocol.UA)
        }.bodyAsText()
      }.getOrDefault("")
      ayyuid = HuyaDanmakuProtocol.resolveAyyuidFromProfileJson(body) ?: rid
    }
    return HuyaDanmakuProtocol.buildWsInfo(ayyuid)
  }

  fun observe(roomId: String): Flow<DanmakuMessage> = flow {
    var backoffMs = 1000L
    while (isActive) {
      val wsInfo = runCatching { fetchWsInfo(roomId) }
        .onFailure { AppLog.e("DTV-Huya", "fetch huya ws info failed roomId=$roomId", it) }
        .getOrNull()

      if (wsInfo == null) {
        delay(backoffMs)
        backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
        continue
      }

      AppLog.i("DTV-Huya", "huya danmaku connecting roomId=$roomId ayyuid=${wsInfo.ayyuid}")

      try {
        wsClient.webSocket(
          urlString = HuyaDanmakuProtocol.WS_URL,
          request = {
            header("Origin", "https://www.huya.com")
            header("Referer", "https://www.huya.com/")
            header("User-Agent", HuyaDanmakuProtocol.UA)
          },
        ) {
          var lastBinaryAt = currentTimeMillis()

          send(Frame.Binary(fin = true, data = wsInfo.registerPayload))

          launch {
            var hb = 0
            while (isActive) {
              hb += 1
              delay(20_000L)
              runCatching { send(Frame.Binary(fin = true, data = HuyaDanmakuProtocol.HEARTBEAT)) }
                .onFailure { break }
            }
          }

          launch {
            // Force reconnect when the server stops pushing binary frames.
            while (isActive) {
              delay(3_000L)
              if (currentTimeMillis() - lastBinaryAt >= 18_000L) {
                AppLog.w("DTV-Huya", "huya danmaku idle too long, reconnect roomId=$roomId")
                runCatching {
                  close(CloseReason(CloseReason.Codes.NORMAL_GOING_AWAY, "idle reconnect"))
                }
                break
              }
            }
          }

          for (frame in incoming) {
            if (frame is Frame.Binary) {
              lastBinaryAt = currentTimeMillis()
              val data = frame.data
              val chat = runCatching { HuyaDanmakuProtocol.decodeChat(data) }.getOrNull() ?: continue
              emit(DanmakuMessage(roomId = roomId, user = chat.user, content = chat.content))
            } else if (frame is Frame.Close) {
              break
            }
          }
        }
      } catch (ce: CancellationException) {
        throw ce
      } catch (t: Throwable) {
        AppLog.e("DTV-Huya", "huya danmaku ws failed roomId=$roomId", t)
      }

      delay(backoffMs)
      backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
    }
  }.buffer(capacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST)
}
