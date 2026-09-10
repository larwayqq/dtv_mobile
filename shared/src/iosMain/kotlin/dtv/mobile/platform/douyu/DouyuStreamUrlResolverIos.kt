package dtv.mobile.platform.douyu

import dtv.mobile.repo.DouyuPlayInfo
import dtv.mobile.repo.DouyuPlayVariant
import dtv.mobile.util.currentTimeMillis
import dtv.mobile.util.decodeHtmlEntities
import dtv.mobile.util.jsonElementToInt
import dtv.mobile.util.jsonElementToString
import dtv.mobile.util.md5Hex
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.roundToLong

/**
 * iOS Douyu resolver.
 *
 * AVPlayer only supports HLS, while the PC getH5Play API returns FLV. Playback therefore uses
 * the mobile web endpoint `playweb.douyucdn.cn/lapi/live/hlsH5Preview/{rid}` which returns an
 * `.m3u8` URL authenticated with `auth = md5(rid + timeMillis)`. getH5Play (JS-signed) is still
 * used to enumerate qualities/cdns for the player UI.
 */
class DouyuStreamUrlResolverIos(
  private val client: HttpClient,
) {
  private val signer = DouyuJsSignerIos()
  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
  }

  suspend fun resolve(
    roomId: String,
    quality: String? = null,
    cdn: String? = null,
  ): String = withContext(Dispatchers.Default) {
    val realRoomId = fetchRealRoomId(roomId)
    resolveHlsPreview(realRoomId)
  }

  suspend fun fetchPlayInfo(roomId: String): DouyuPlayInfo = withContext(Dispatchers.Default) {
    val (realRoomId, isLive) = fetchRoomDetail(roomId)
    if (!isLive) error("主播未开播")
    val signData = buildSignParams(realRoomId)
    val playInfo = getPlayQualities(realRoomId, signData)
    DouyuPlayInfo(
      cdns = playInfo.cdns,
      variants = playInfo.variants.map { DouyuPlayVariant(name = it.name, rate = it.rate, bit = it.bit) },
    )
  }

  suspend fun isLive(roomId: String): Boolean = withContext(Dispatchers.Default) {
    fetchRoomDetail(roomId).second
  }

  private suspend fun fetchRealRoomId(roomId: String): String {
    val (realRoomId, isLive) = fetchRoomDetail(roomId)
    if (!isLive) error("主播未开播")
    return realRoomId
  }

  private suspend fun resolveHlsPreview(realRoomId: String): String {
    val t13 = currentTimeMillis().toString()
    val auth = md5Hex("$realRoomId$t13")
    val payload = "rid=$realRoomId&did=$DEFAULT_DOUYU_DID"
    val respText = client.post("https://playweb.douyucdn.cn/lapi/live/hlsH5Preview/$realRoomId") {
      contentType(ContentType.Application.FormUrlEncoded)
      headers {
        set(HttpHeaders.Referrer, "https://m.douyu.com/")
        set("Origin", "https://m.douyu.com")
        set(HttpHeaders.UserAgent, DOUYU_USER_AGENT)
        set("rid", realRoomId)
        set("time", t13)
        set("auth", auth)
      }
      setBody(payload)
    }.bodyAsText().trim()

    val root = json.parseToJsonElement(respText) as? JsonObject ?: error("hlsH5Preview 响应解析失败")
    val errorCode = (root["error"] as? JsonPrimitive)?.content?.toIntOrNull() ?: -1
    if (errorCode != 0) {
      val msg = (root["msg"] as? JsonPrimitive)?.content
      error(
        when (errorCode) {
          102 -> "房间不存在"
          104 -> "房间未开播"
          else -> "hlsH5Preview error $errorCode: ${msg ?: "failed"}"
        },
      )
    }
    val data = root["data"] as? JsonObject ?: error("hlsH5Preview 无 data")
    val baseUrl = (data["rtmp_url"] as? JsonPrimitive)?.content?.trim().orEmpty()
    val livePath = (data["rtmp_live"] as? JsonPrimitive)?.content?.let(::decodeHtmlEntities).orEmpty()
    if (baseUrl.isBlank() || livePath.isBlank()) error("hlsH5Preview 缺少播放字段")
    if (!livePath.contains(".m3u8")) error("hlsH5Preview 未返回 HLS 地址")
    return "${baseUrl.trimEnd('/')}/$livePath"
  }

  private suspend fun fetchRoomDetail(roomId: String): Pair<String, Boolean> {
    val url = "https://www.douyu.com/betard/$roomId"
    val httpResp = client.get(url) { douyuHeaders(roomId) }
    val text = httpResp.bodyAsText().trim()
    val resp = json.decodeFromString(DouyuBetardResponse.serializer(), text)
    val room = resp.room ?: error("Missing room data")
    val realRoomId = jsonElementToString(room.roomId)?.trim().orEmpty()
    if (realRoomId.isEmpty()) error("Invalid room_id")
    val showStatus = jsonElementToInt(room.showStatus) ?: 0
    return realRoomId to (showStatus == 1)
  }

  private suspend fun buildSignParams(roomId: String): String {
    val script = getHomeH5Enc(roomId)
    val ts = (currentTimeMillis() / 1000.0).roundToLong()
    return signer.signParams(
      homeH5EncScript = script,
      roomId = roomId,
      did = DEFAULT_DOUYU_DID,
      tsSeconds = ts,
    )
  }

  private suspend fun getHomeH5Enc(roomId: String): String {
    val url = "https://www.douyu.com/swf_api/homeH5Enc?rids=$roomId"
    val httpResp = client.get(url) { douyuHeaders(roomId) }
    val text = httpResp.bodyAsText().trim()
    val resp = json.decodeFromString(DouyuHomeH5EncResponse.serializer(), text)
    if (resp.error != 0) error("homeH5Enc error: ${resp.error}")
    val key = "room$roomId"
    return resp.data?.get(key) ?: error("Missing homeH5Enc data")
  }

  private suspend fun getPlayQualities(roomId: String, signData: String): InternalPlayInfo {
    val payload = "$signData&cdn=&rate=-1&ver=$DOUYU_WEB_VER&iar=1&ive=1&hevc=0&fa=0"
    val url = "https://www.douyu.com/lapi/live/getH5Play/$roomId"
    val httpResp = client.post(url) {
      contentType(ContentType.Application.FormUrlEncoded)
      douyuHeaders(roomId)
      setBody(payload)
    }
    val text = httpResp.bodyAsText().trim()
    val resp = json.decodeFromString(DouyuH5PlayResponse.serializer(), text)

    if (resp.error != 0) error("getH5Play error ${resp.error}: ${resp.msg ?: "failed"}")
    val data = resp.data ?: error("No data field in response")

    val cdns = data.cdnsWithName.orEmpty().mapNotNull { it.cdn?.trim() }.filter { it.isNotEmpty() }
      .sortedWith(compareBy({ it.startsWith("scdn") }, { it }))
    val variants = data.multirates.orEmpty().mapNotNull { raw ->
      val name = raw.name?.trim().orEmpty()
      val rate = raw.rate
      if (name.isEmpty() || rate == null) return@mapNotNull null
      DouyuRateVariant(name = name, rate = rate, bit = raw.bit)
    }
    return InternalPlayInfo(
      variants = variants,
      cdns = if (cdns.isNotEmpty()) cdns else listOf(DEFAULT_DOUYU_CDN),
    )
  }

  private fun HttpRequestBuilder.douyuHeaders(roomId: String) {
    headers {
      set(HttpHeaders.Referrer, "https://www.douyu.com/$roomId")
      set(HttpHeaders.UserAgent, DOUYU_USER_AGENT)
    }
  }

  private data class DouyuRateVariant(
    val name: String,
    val rate: Int,
    val bit: Int?,
  )

  private data class InternalPlayInfo(
    val variants: List<DouyuRateVariant>,
    val cdns: List<String>,
  )

  private companion object {
    const val DEFAULT_DOUYU_CDN = "ws-h5"
    const val DEFAULT_DOUYU_DID = "10000000000000000000000000001501"
    const val DOUYU_WEB_VER = "Douyu_223061205"
    const val DOUYU_USER_AGENT =
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36 Edg/114.0.1823.43"
  }
}
