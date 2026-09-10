package dtv.mobile.platform.huya

import dtv.mobile.util.AppLog
import dtv.mobile.util.currentTimeMillis
import dtv.mobile.util.md5Hex
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsText
import io.ktor.http.decodeURLQueryComponent
import io.ktor.util.decodeBase64Bytes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.math.abs
import kotlin.random.Random

/**
 * iOS Huya resolver. Unlike Android (FLV for ExoPlayer), this picks the HLS fields
 * `sHlsUrl` / `sHlsUrlSuffix` / `sHlsAntiCode` so AVPlayer can play the stream.
 */
class HuyaStreamUrlResolverIos(
  private val client: HttpClient,
) {
  companion object {
    private const val IOS_MOBILE_UA =
      "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1"
    private const val DESKTOP_UA =
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    private const val HUYA_WEBH5_COOKIE = "huya_ua=webh5&0.1.0&websocket"
  }

  private val json = Json { ignoreUnknownKeys = true; isLenient = true }

  private fun JsonElement?.stringValueOrNull(): String? {
    val p = this as? kotlinx.serialization.json.JsonPrimitive ?: return null
    if (p is JsonNull) return null
    return p.content
  }

  private fun enforceHttps(url: String): String = when {
    url.startsWith("https://") -> url
    url.startsWith("http://") -> "https://${url.removePrefix("http://")}" }
    else -> url
  }

  private fun parseQuery(qs: String): Map<String, String> {
    val trimmed = qs.trim().trimStart('?', '&')
    if (trimmed.isBlank()) return emptyMap()
    return trimmed.split('&')
      .mapNotNull { kv ->
        val idx = kv.indexOf('=')
        if (idx <= 0) return@mapNotNull null
        val k = kv.substring(0, idx)
        val v = kv.substring(idx + 1)
        k to v
      }.toMap()
  }

  private fun urlDecodeOnce(s: String): String =
    runCatching { s.decodeURLQueryComponent() }.getOrDefault(s)

  suspend fun isLive(roomId: String): Boolean {
    suspend fun fetchHtml(useMobileHeaders: Boolean): String = fetchPage(roomId, useMobileHeaders)

    fun hasCandidates(html: String): Boolean {
      val re = Regex("(?s)stream:\\s*(\\{\"data\".*?),\"iWebDefaultBitRate\"")
      val match = re.find(html) ?: return false
      val jsonStr = match.groupValues[1] + "}"
      val value = runCatching { json.parseToJsonElement(jsonStr).jsonObject }.getOrNull() ?: return false
      val dataList = value["data"]?.jsonArray ?: return false
      val first = dataList.firstOrNull()?.jsonObject ?: return false
      val streamInfoList = first["gameStreamInfoList"]?.jsonArray ?: return false
      return streamInfoList.isNotEmpty()
    }

    val htmlDesktop = fetchHtml(useMobileHeaders = false)
    if (hasCandidates(htmlDesktop)) return true
    val htmlMobile = fetchHtml(useMobileHeaders = true)
    return hasCandidates(htmlMobile)
  }

  private fun generateWebAntiCode(streamName: String, antiCode: String): String {
    val sanitized = antiCode.replace("&amp;", "&")
    val params = parseQuery(sanitized)
    val fmValue = params["fm"] ?: error("missing fm in anti code")
    val ctype = params["ctype"] ?: error("missing ctype in anti code")
    val fs = params["fs"] ?: error("missing fs in anti code")

    val fmDecoded = urlDecodeOnce(fmValue)
    val fmPlain = fmDecoded.decodeBase64Bytes().decodeToString()
    val wsPrefix = fmPlain.split('_').firstOrNull().orEmpty()
    if (wsPrefix.isBlank()) error("failed to derive wsSecret prefix")

    val paramsT = 100L
    val sdkVersion = 2403051612L
    val t13 = currentTimeMillis()
    val sdkSid = t13

    val uid = 1_400_000_000_000L + abs(Random.nextLong()) % 10_000_000_000L
    val seqId = uid + sdkSid
    val wsTime = ((t13 + 110_624L) / 1000L).toString(16)

    val uuidSeed = (t13 % 10_000_000_000L) * 1000L + (abs(Random.nextLong()) % 1000L)
    val initUuid = uuidSeed % 4_294_967_295L

    val wsSecretHash = md5Hex("$seqId|$ctype|$paramsT")
    val wsSecretPlain = "${wsPrefix}_${uid}_${streamName}_${wsSecretHash}_${wsTime}"
    val wsSecretMd5 = md5Hex(wsSecretPlain)

    val parts = listOf(
      "wsSecret" to wsSecretMd5,
      "wsTime" to wsTime,
      "seqid" to seqId.toString(),
      "ctype" to ctype,
      "ver" to "1",
      "fs" to fs,
      "uuid" to initUuid.toString(),
      "u" to uid.toString(),
      "t" to paramsT.toString(),
      "sv" to sdkVersion.toString(),
      "sdk_sid" to sdkSid.toString(),
      "codec" to "264",
    )
    return parts.joinToString("&") { (k, v) -> "$k=$v" }
  }

  private fun adjustTxStreamUrl(url: String, cdn: String): String {
    if (!cdn.equals("tx", ignoreCase = true)) return url
    val replacedCtype = url.replace("&ctype=tars_mp", "&ctype=huya_webh5")
    return replacedCtype.replace("&fs=bhct", "&fs=bgct")
  }

  private suspend fun fetchPage(roomId: String, useMobileHeaders: Boolean): String {
    val url = "https://www.huya.com/$roomId"
    return client.get(url) {
      headers {
        if (useMobileHeaders) {
          append("User-Agent", IOS_MOBILE_UA)
          append("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
          append("Referer", "https://m.huya.com/")
        } else {
          append("User-Agent", DESKTOP_UA)
          append("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
          append("Referer", "https://www.huya.com/")
        }
        append("Accept-Language", "zh-CN,zh;q=0.9,en-US;q=0.6,en;q=0.4")
        append("Cookie", HUYA_WEBH5_COOKIE)
      }
    }.bodyAsText()
  }

  suspend fun resolve(roomId: String): String {
    fun parseCandidates(html: String): List<Pair<String, String>> {
      val re = Regex("(?s)stream:\\s*(\\{\"data\".*?),\"iWebDefaultBitRate\"")
      val match = re.find(html) ?: return emptyList()
      val jsonStr = match.groupValues[1] + "}"
      val value = json.parseToJsonElement(jsonStr).jsonObject
      val dataList = value["data"]?.jsonArray ?: return emptyList()
      val first = dataList.firstOrNull()?.jsonObject ?: return emptyList()
      val streamInfoList = first["gameStreamInfoList"]?.jsonArray ?: return emptyList()

      return streamInfoList.mapNotNull { item ->
        val obj = item.jsonObject
        val cdn = obj["sCdnType"].stringValueOrNull().orEmpty()
        val hlsUrl = obj["sHlsUrl"].stringValueOrNull().orEmpty()
        val streamName = obj["sStreamName"].stringValueOrNull().orEmpty()
        val suffix = obj["sHlsUrlSuffix"].stringValueOrNull().orEmpty()
        val anti = obj["sHlsAntiCode"].stringValueOrNull().orEmpty()
        if (hlsUrl.isBlank() || streamName.isBlank() || suffix.isBlank() || anti.isBlank()) {
          return@mapNotNull null
        }
        val antiParams = generateWebAntiCode(streamName, anti)
        val base = enforceHttps("${hlsUrl.trimEnd('/')}/$streamName.$suffix?$antiParams")
        cdn to base
      }
    }

    val htmlDesktop = fetchPage(roomId, useMobileHeaders = false)
    val desktopCandidates = parseCandidates(htmlDesktop)
    val candidates = if (desktopCandidates.isNotEmpty()) {
      desktopCandidates
    } else {
      val htmlMobile = fetchPage(roomId, useMobileHeaders = true)
      parseCandidates(htmlMobile)
    }

    AppLog.i(
      "DTV-Huya",
      "hls candidates roomId=$roomId: " + candidates.joinToString { (cdn, u) -> "$cdn=${u.take(64)}" },
    )

    val selected = candidates
      .sortedBy { (cdn, _) ->
        when (cdn.lowercase()) {
          "al" -> 0
          "hw" -> 1
          "hs" -> 2
          "tx" -> 3
          else -> 4
        }
      }
      .firstOrNull()
      ?: error("虎牙未找到可用HLS播放地址")

    val finalUrl = enforceHttps(adjustTxStreamUrl(selected.second, selected.first))
    AppLog.i("DTV-Huya", "selected hls roomId=$roomId cdn=${selected.first} url=${finalUrl.take(96)}")
    return finalUrl
  }
}
