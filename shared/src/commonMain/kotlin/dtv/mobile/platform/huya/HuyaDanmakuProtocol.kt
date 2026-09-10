package dtv.mobile.platform.huya

import dtv.mobile.util.decodeTextBestEffort
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal data class HuyaWsInfo(
  val wsUrl: String,
  val registerPayload: ByteArray,
  val ayyuid: String,
)

internal data class HuyaChatDecoded(
  val user: String,
  val content: String,
)

/**
 * Pure protocol logic for Huya danmaku shared by Android/iOS. HTTP fetching is done by the
 * platform clients; this object only builds/parses payloads and resolves ayyuid from page text.
 */
internal object HuyaDanmakuProtocol {
  const val WS_URL = "wss://cdnws.api.huya.com"

  const val UA =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

  // Copied from DTV-heroui desktop backend (src-tauri/src/platforms/huya/danmaku.rs).
  // Do not modify unless upstream changes.
  val HEARTBEAT: ByteArray = byteArrayOf(
    0x00, 0x03, 0x1d, 0x00, 0x00, 0x69, 0x00, 0x00, 0x00, 0x69, 0x10, 0x03, 0x2c, 0x3c, 0x4c, 0x56,
    0x08, 0x6f, 0x6e, 0x6c, 0x69, 0x6e, 0x65, 0x75, 0x69, 0x66, 0x0f, 0x4f, 0x6e, 0x55, 0x73, 0x65,
    0x72, 0x48, 0x65, 0x61, 0x72, 0x74, 0x42, 0x65, 0x61, 0x74, 0x7d, 0x00, 0x00, 0x3c, 0x08, 0x00,
    0x01, 0x06, 0x04, 0x74, 0x52, 0x65, 0x71, 0x1d, 0x00, 0x00, 0x2f, 0x0a, 0x0a, 0x0c, 0x16, 0x00,
    0x26, 0x00, 0x36, 0x07, 0x61, 0x64, 0x72, 0x5f, 0x77, 0x61, 0x70, 0x46, 0x00, 0x0b, 0x12, 0x03,
    0xae.toByte(), 0xf0.toByte(), 0x0f, 0x22, 0x03, 0xae.toByte(), 0xf0.toByte(), 0x0f, 0x3c, 0x42,
    0x6d, 0x52, 0x02, 0x60, 0x5c, 0x60, 0x01, 0x7c, 0x82.toByte(), 0x00, 0x0b, 0xb0.toByte(), 0x1f,
    0x9c.toByte(), 0xac.toByte(), 0x0b, 0x8c.toByte(), 0x98.toByte(), 0x0c, 0xa8.toByte(), 0x0c,
  )

  private val json = Json { ignoreUnknownKeys = true; isLenient = true }

  fun buildRegisterPayload(ayyuid: String): ByteArray {
    val topics = listOf("live:$ayyuid", "chat:$ayyuid")

    val inner = Tars.Output()
      .writeStringList(0, topics)
      .writeString(1, "")
      .toByteArray()

    return Tars.Output()
      .writeInt32(0, 16)
      .writeBytes(1, inner)
      .toByteArray()
  }

  fun buildWsInfo(ayyuid: String): HuyaWsInfo =
    HuyaWsInfo(wsUrl = WS_URL, registerPayload = buildRegisterPayload(ayyuid), ayyuid = ayyuid)

  fun decodeChat(data: ByteArray): HuyaChatDecoded? {
    val ios = Tars.Input(data)
    val top = runCatching { ios.readInt32(0, required = false, defaultValue = -1) }.getOrDefault(-1)
    if (top != 7) return null

    val b1 = runCatching { ios.readBytes(1, required = false, defaultValue = ByteArray(0)) }.getOrDefault(ByteArray(0))
    if (b1.isEmpty()) return null

    val inner = Tars.Input(b1)
    val nested = runCatching { inner.readInt32(1, required = false, defaultValue = -1) }.getOrDefault(-1)
    val b2 = runCatching { inner.readBytes(2, required = false, defaultValue = ByteArray(0)) }.getOrDefault(ByteArray(0))
    if (nested != 1400 || b2.isEmpty()) return null

    val user = runCatching {
      val p = Tars.Input(b2)
      p.readStruct(0, required = false) { sr ->
        val nameBytes = sr.readStringBytes(2, defaultValue = ByteArray(0))
        decodeTextBestEffort(nameBytes)
      } ?: ""
    }.getOrDefault("")

    val text = runCatching {
      val p = Tars.Input(b2)
      val bytes = p.readStringBytes(3, required = false, defaultValue = ByteArray(0))
      decodeTextBestEffort(bytes)
    }.getOrDefault("")

    if (text.isBlank()) return null
    val nick = if (user.isNotBlank()) user else "匿名"
    return HuyaChatDecoded(user = nick, content = text)
  }

  fun peekCmds(data: ByteArray): Pair<Int?, Int?> {
    val ios = Tars.Input(data)
    val top = ios.readInt32(0, required = false, defaultValue = -1)
    val b1 = ios.readBytes(1, required = false, defaultValue = ByteArray(0))
    val nested = if (b1.isNotEmpty()) {
      val inner = Tars.Input(b1)
      inner.readInt32(1, required = false, defaultValue = -1)
    } else {
      null
    }
    return top to nested
  }

  /** Try the page-embedded TT_PROFILE_INFO / escaped JSON patterns. Returns null if not found. */
  fun resolveAyyuidFromPage(page: String): String? {
    Regex("""var\s+TT_PROFILE_INFO\s*=\s*(\{[\s\S]*?\});""")
      .find(page)
      ?.groupValues
      ?.getOrNull(1)
      ?.let { raw ->
        runCatching {
          val j = json.parseToJsonElement(raw)
          (j as? JsonObject)?.get("lp")?.asString()?.takeIf { it.isNotBlank() }
        }.getOrNull()
      }
      ?.let { return it }

    Regex("""\\\"lp\\\"\s*:\s*\\\"?(\d+)\\\"?""")
      .find(page)
      ?.groupValues
      ?.getOrNull(1)
      ?.takeIf { it.isNotBlank() }
      ?.let { return it }

    Regex("""\\\"ayyuid\\\"\s*:\s*\\\"?(\d+)\\\"?""").find(page)?.groupValues?.getOrNull(1)?.let { return it }
    Regex("""\\\"yyuid\\\"\s*:\s*\\\"?(\d+)\\\"?""").find(page)?.groupValues?.getOrNull(1)?.let { return it }

    return null
  }

  /** Recursively search the profileRoom API JSON for an ayyuid-like field. */
  fun resolveAyyuidFromProfileJson(body: String): String? = runCatching {
    findUidInJson(json.parseToJsonElement(body))
  }.getOrNull()

  private fun findUidInJson(v: JsonElement): String? {
    return when (v) {
      is JsonObject -> {
        for ((k, value) in v) {
          val key = k.lowercase()
          if (key == "ayyuid" || key == "yyuid" || key == "lp" || key == "uid") {
            value.asString()?.takeIf { it.isNotBlank() }?.let { return it }
          }
          findUidInJson(value)?.let { return it }
        }
        null
      }
      is JsonArray -> v.firstNotNullOfOrNull { findUidInJson(it) }
      else -> null
    }
  }

  private fun JsonElement.asString(): String? = when (this) {
    is JsonPrimitive -> content
    else -> null
  }

  /** Normalize a room id or full huya URL into the room path segment. */
  fun toRoomId(input: String): String {
    val trimmed = input.trim()
    if (trimmed.startsWith("http", ignoreCase = true)) {
      val path = trimmed.substringAfter("://", "").substringAfter('/', "").trim('/')
      val p = path.split('/').lastOrNull().orEmpty()
      if (p.isNotBlank()) return p
    }
    return trimmed.trim('/').ifBlank { error("Invalid room id/url: $input") }
  }
}
