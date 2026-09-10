package dtv.mobile.platform.douyu

import dtv.mobile.repo.DanmakuMessage
import dtv.mobile.util.AppLog
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
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

class DouyuDanmakuClientIos {
  private val wsClient = HttpClient(Darwin) {
    install(WebSockets)
  }

  fun observe(roomId: String): Flow<DanmakuMessage> = flow {
    fun encode(msg: String): ByteArray {
      val msgBytes = msg.encodeToByteArray()
      val packetLen = msgBytes.size + 9
      val out = ByteArray(4 + 4 + 2 + 1 + 1 + msgBytes.size + 1)
      var o = 0
      fun writeIntLe(v: Int) {
        out[o++] = (v and 0xFF).toByte()
        out[o++] = ((v ushr 8) and 0xFF).toByte()
        out[o++] = ((v ushr 16) and 0xFF).toByte()
        out[o++] = ((v ushr 24) and 0xFF).toByte()
      }
      writeIntLe(packetLen)
      writeIntLe(packetLen)
      out[o++] = (689 and 0xFF).toByte()
      out[o++] = ((689 ushr 8) and 0xFF).toByte()
      out[o++] = 0
      out[o++] = 0
      msgBytes.copyInto(out, o)
      o += msgBytes.size
      out[o] = 0
      return out
    }

    fun decodeDouyuEscapes(value: String): String =
      value.replace("@S", "/").replace("@A", "@")

    fun parseMap(content: String): Map<String, String> {
      val map = HashMap<String, String>(16)
      content.split('/').forEach { item ->
        if (item.isBlank()) return@forEach
        val idx = item.indexOf("@=")
        if (idx <= 0) return@forEach
        val k = item.substring(0, idx)
        val v = item.substring(idx + 2)
        map[k] = decodeDouyuEscapes(v)
      }
      return map
    }

    var backoff = 1000L
    while (isActive) {
      try {
        wsClient.webSocket(
          urlString = "wss://danmuproxy.douyu.com:8506/",
          request = {
            header("Sec-WebSocket-Protocol", "binary")
          },
        ) {
          send(Frame.Binary(fin = true, data = encode("type@=loginreq/roomid@=$roomId/")))
          send(Frame.Binary(fin = true, data = encode("type@=joingroup/rid@=$roomId/gid@=1/")))

          launch {
            while (isActive) {
              delay(45_000L)
              val sent = runCatching { send(Frame.Binary(fin = true, data = encode("type@=mrkl/"))) }.isSuccess
              if (!sent) break
            }
          }

          for (frame in incoming) {
            if (frame !is Frame.Binary) {
              if (frame is Frame.Close) break
              continue
            }
            val data = frame.data
            if (data.size < 13) continue
            val payload = data.copyOfRange(12, data.size - 1)
            val text = runCatching { payload.decodeToString() }.getOrNull() ?: continue
            val m = parseMap(text)
            if (m["type"] == "chatmsg") {
              val user = m["nn"].orEmpty().ifBlank { "unknown" }
              val content = m["txt"].orEmpty()
              val userLevel = m["level"]?.toIntOrNull() ?: 0
              val fans = m["bl"]?.toIntOrNull() ?: 0
              val color = m["col"]
              emit(
                DanmakuMessage(
                  roomId = roomId,
                  user = user,
                  content = content,
                  userLevel = userLevel,
                  fansClubLevel = fans,
                  color = color,
                ),
              )
            }
          }
        }
      } catch (ce: CancellationException) {
        throw ce
      } catch (t: Throwable) {
        AppLog.w("DTV-Douyu", "douyu danmaku ws error roomId=$roomId", t)
      }
      delay(backoff)
      backoff = min(backoff * 2, 30_000L)
    }
  }.buffer(capacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST)
}
