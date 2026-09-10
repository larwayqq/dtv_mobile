package dtv.mobile.platform.huya

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Android-side HTTP wrapper around the shared [HuyaDanmakuProtocol].
 * Kept so [HuyaDanmakuClientAndroid] can continue to use OkHttp for page fetches.
 */
internal object HuyaDtvHerouiAlgo {
  suspend fun fetchWsInfo(client: OkHttpClient, roomIdOrUrl: String): HuyaWsInfo {
    val rid = HuyaDanmakuProtocol.toRoomId(roomIdOrUrl)
    val page = fetchText(
      client,
      "https://www.huya.com/$rid",
      headers = mapOf(
        "User-Agent" to HuyaDanmakuProtocol.UA,
        "Referer" to "https://www.huya.com/",
      ),
    )

    var ayyuid = HuyaDanmakuProtocol.resolveAyyuidFromPage(page)
    if (ayyuid == null) {
      val api = "https://mp.huya.com/cache.php?m=Live&do=profileRoom&roomid=$rid"
      val body = runCatching {
        fetchText(client, api, headers = mapOf("User-Agent" to HuyaDanmakuProtocol.UA))
      }.getOrDefault("")
      ayyuid = HuyaDanmakuProtocol.resolveAyyuidFromProfileJson(body) ?: rid
    }

    return HuyaDanmakuProtocol.buildWsInfo(ayyuid)
  }

  fun decodeChat(data: ByteArray): HuyaChatDecoded? = HuyaDanmakuProtocol.decodeChat(data)

  fun peekCmds(data: ByteArray): Pair<Int?, Int?> = HuyaDanmakuProtocol.peekCmds(data)

  private suspend fun fetchText(client: OkHttpClient, url: String, headers: Map<String, String> = emptyMap()): String {
    return withContext(Dispatchers.IO) {
      val reqBuilder = Request.Builder().url(url)
      headers.forEach { (k, v) -> reqBuilder.header(k, v) }
      client.newCall(reqBuilder.build()).execute().use { resp ->
        if (!resp.isSuccessful) error("HTTP ${resp.code} for $url")
        resp.body?.string() ?: ""
      }
    }
  }
}
