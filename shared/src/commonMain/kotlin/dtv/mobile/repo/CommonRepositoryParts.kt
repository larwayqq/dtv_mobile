package dtv.mobile.repo

import dtv.mobile.util.normalizeHttpUrl
import dtv.mobile.util.readBundleAssetText
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal data class FollowSnapshot(
  val name: String?,
  val title: String?,
  val viewerText: String?,
  val avatarUrl: String?,
  val coverUrl: String?,
  val isLive: Boolean?,
)

internal fun JsonElement?.stringValueOrNull(): String? {
  val p = this as? JsonPrimitive ?: return null
  if (p is JsonNull) return null
  return p.content
}

/**
 * Follow-list snapshot enrichment shared by Android/iOS. The only platform differences are the
 * optional `isLive` fallbacks (stream resolver probes) for Douyu/Huya.
 */
internal class FollowInfoApi(
  private val client: HttpClient,
  private val json: Json,
  private val douyuIsLive: suspend (String) -> Boolean?,
  private val huyaIsLive: suspend (String) -> Boolean?,
) {
  suspend fun fetchDouyu(roomId: String): FollowSnapshot {
    val url = "https://www.douyu.com/betard/$roomId"
    val text = client.get(url) {
      headers {
        append("Accept", "application/json, text/plain, */*")
        append("Accept-Language", "zh-CN,zh;q=0.9")
        append("Cache-Control", "no-cache")
        append("Pragma", "no-cache")
        append("Referer", "https://www.douyu.com/$roomId")
        append(
          "User-Agent",
          "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        )
      }
    }.bodyAsText()

    val root = json.parseToJsonElement(text).jsonObject
    val roomObj = (root["data"]?.jsonObject?.get("room")?.jsonObject)
      ?: (root["room"]?.jsonObject)
      ?: (root["data"]?.jsonObject)
      ?: root

    val title = roomObj["room_name"].stringValueOrNull()?.trim()?.ifBlank { null }
    val name = roomObj["nickname"].stringValueOrNull()?.trim()?.ifBlank { null }
    val avatar = normalizeHttpUrl(
      roomObj["avatar_mid"].stringValueOrNull()
        ?: roomObj["avatarMid"].stringValueOrNull()
        ?: roomObj["avatar"].stringValueOrNull(),
    )

    fun normalizeDouyuCover(raw: String?): String? {
      val v = raw?.trim().orEmpty()
      if (v.isBlank()) return null
      return when {
        v.startsWith("http://") || v.startsWith("https://") -> v
        v.startsWith("//") -> "https:$v"
        v.startsWith("/") -> "https://rpic.douyucdn.cn$v"
        else -> "https://rpic.douyucdn.cn/$v"
      }
    }

    val cover = normalizeDouyuCover(
      roomObj["coverSrc"].stringValueOrNull()
        ?: roomObj["room_pic"].stringValueOrNull()
        ?: roomObj["roomPic"].stringValueOrNull()
        ?: roomObj["room_src"].stringValueOrNull()
        ?: roomObj["roomSrc"].stringValueOrNull()
        ?: roomObj["room_thumb"].stringValueOrNull()
        ?: roomObj["roomThumb"].stringValueOrNull(),
    )?.let(::normalizeHttpUrl)

    val showStatus = roomObj["show_status"]?.jsonPrimitive?.intOrNull
    val videoLoop = roomObj["videoLoop"]?.jsonPrimitive?.intOrNull
    val liveStatus: Boolean? = if (showStatus == 1) {
      when (videoLoop) {
        0 -> true
        1 -> false
        else -> null
      }
    } else if (showStatus == 2 || showStatus == 0) {
      false
    } else {
      null
    }
    val isLive = liveStatus ?: runCatching { douyuIsLive(roomId) }.getOrNull()

    val online = (
      roomObj["iol"].stringValueOrNull()
        ?: roomObj["ol"].stringValueOrNull()
        ?: roomObj["online"].stringValueOrNull()
        ?: roomObj["hn"].stringValueOrNull()
    )?.trim()?.ifBlank { null }

    val hot = roomObj["room_biz_all"]?.jsonObject?.get("hot").stringValueOrNull()?.trim()?.ifBlank { null }
    val viewerText = when {
      online.isNullOrBlank() -> hot
      online == "0" && !hot.isNullOrBlank() -> hot
      else -> online
    }
    return FollowSnapshot(
      name = name,
      title = title,
      viewerText = viewerText?.takeIf { it.isNotBlank() },
      avatarUrl = avatar,
      coverUrl = cover,
      isLive = isLive,
    )
  }

  suspend fun fetchHuya(roomId: String): FollowSnapshot {
    val url = "https://mp.huya.com/cache.php?m=Live&do=profileRoom&roomid=$roomId"
    val text = client.get(url) {
      headers { append("User-Agent", "Mozilla/5.0") }
    }.bodyAsText()

    val root = json.parseToJsonElement(text).jsonObject
    val data = root["data"]?.jsonObject
      ?: return FollowSnapshot(null, null, null, null, null, null)

    val profile = data["profileInfo"]?.jsonObject
    val liveData = data["liveData"]?.jsonObject

    val name = profile?.get("nick").stringValueOrNull()?.trim()?.ifBlank { null }
    val avatar = normalizeHttpUrl(profile?.get("avatar180").stringValueOrNull() ?: liveData?.get("avatar180").stringValueOrNull())
    val title = liveData?.get("introduction").stringValueOrNull()?.trim()?.ifBlank { null }
    val cover = normalizeHttpUrl(liveData?.get("screenshot").stringValueOrNull() ?: liveData?.get("sScreenshot").stringValueOrNull())

    val liveStatus = data["liveStatus"].stringValueOrNull()?.trim()?.uppercase()
    val isLive = when (liveStatus) {
      "ON" -> true
      "OFF" -> false
      else -> runCatching { huyaIsLive(roomId) }.getOrNull()
    }

    val totalCount = liveData?.get("totalCount")?.jsonPrimitive?.longOrNull?.toString()
    return FollowSnapshot(
      name = name,
      title = title,
      viewerText = totalCount?.takeIf { it.isNotBlank() },
      avatarUrl = avatar,
      coverUrl = cover,
      isLive = isLive,
    )
  }

  suspend fun fetchBilibili(roomId: String): FollowSnapshot {
    val url = "https://api.live.bilibili.com/xlive/web-room/v1/index/getH5InfoByRoom?room_id=$roomId"
    val text = client.get(url) {
      headers {
        append("User-Agent", "Mozilla/5.0")
        append("Referer", "https://live.bilibili.com/")
      }
    }.bodyAsText()

    val root = json.parseToJsonElement(text).jsonObject
    val data = root["data"]?.jsonObject
      ?: return FollowSnapshot(null, null, null, null, null, null)
    val roomInfo = data["room_info"]?.jsonObject
    val anchorBase = data["anchor_info"]?.jsonObject?.get("base_info")?.jsonObject

    val title = roomInfo?.get("title").stringValueOrNull()?.trim()?.ifBlank { null }
    val name = anchorBase?.get("uname").stringValueOrNull()?.trim()?.ifBlank { null }
    val avatar = normalizeHttpUrl(anchorBase?.get("face").stringValueOrNull())
    val cover = normalizeHttpUrl(
      roomInfo?.get("keyframe").stringValueOrNull()
        ?: roomInfo?.get("cover").stringValueOrNull()
        ?: roomInfo?.get("user_cover").stringValueOrNull(),
    )

    val liveStatus = roomInfo?.get("live_status")?.jsonPrimitive?.intOrNull
    val isLive = liveStatus?.let { it == 1 }
    val online = roomInfo?.get("online")?.jsonPrimitive?.longOrNull?.toString()

    return FollowSnapshot(
      name = name,
      title = title,
      viewerText = online?.takeIf { it.isNotBlank() },
      avatarUrl = avatar,
      coverUrl = cover,
      isLive = isLive,
    )
  }
}

// ---- Bundled category JSON parsing (files packaged under composeResources/files) ----

/**
 * Reads a bundled JSON asset and parses it off the main thread. The bundled
 * category files are 20-70 KB; both file I/O and kotlinx.serialization DOM
 * parsing must stay off the UI thread to avoid hangs on tab switch.
 */
private suspend fun <T> parseCategoriesBundle(
  json: Json,
  assetPath: String,
  parse: (JsonArray) -> List<T>,
): List<T> = withContext(Dispatchers.Default) {
  val text = readBundleAssetText(assetPath)
  val arr = json.parseToJsonElement(text) as? JsonArray ?: return@withContext emptyList()
  parse(arr)
}

internal suspend fun parseHuyaCategoriesBundle(json: Json): List<HuyaCate1> =
  parseCategoriesBundle(json, "categories/huya_categories.json") { arr ->
    arr.mapNotNull { c1El ->
      val obj = c1El.jsonObject
      val name = obj["title"].stringValueOrNull()?.trim().orEmpty()
      if (name.isBlank()) return@mapNotNull null
      val href = obj["href"].stringValueOrNull()
      val sub = obj["subcategories"]?.jsonArray.orEmpty().mapNotNull { c2El ->
        val c2Obj = c2El.jsonObject
        val gid = c2Obj["id"].stringValueOrNull()?.trim().orEmpty()
        val c2Name = c2Obj["title"].stringValueOrNull()?.trim().orEmpty()
        if (gid.isBlank() || c2Name.isBlank()) return@mapNotNull null
        HuyaCate2(
          gid = gid,
          name = c2Name,
          href = c2Obj["href"].stringValueOrNull(),
        )
      }
      HuyaCate1(name = name, href = href, cate2List = sub)
    }.filter { it.cate2List.isNotEmpty() }
  }

internal suspend fun parseBilibiliCategoriesBundle(json: Json): List<BilibiliCate1> =
  parseCategoriesBundle(json, "categories/bilibili_categories.json") { arr ->
    arr.mapNotNull { c1El ->
      val obj = c1El.jsonObject
      val parentId = obj["id"]?.jsonPrimitive?.intOrNull
        ?: obj["id"].stringValueOrNull()?.toIntOrNull()
        ?: return@mapNotNull null
      val name = obj["title"].stringValueOrNull()?.trim().orEmpty()
      if (name.isBlank()) return@mapNotNull null
      val href = obj["href"].stringValueOrNull()
      val sub = obj["subcategories"]?.jsonArray.orEmpty().mapNotNull { c2El ->
        val c2Obj = c2El.jsonObject
        val areaId = c2Obj["id"].stringValueOrNull()?.toIntOrNull()
          ?: c2Obj["id"]?.jsonPrimitive?.intOrNull
          ?: return@mapNotNull null
        val c2Name = c2Obj["title"].stringValueOrNull()?.trim().orEmpty()
        if (c2Name.isBlank()) return@mapNotNull null
        BilibiliCate2(
          areaId = areaId,
          parentAreaId = c2Obj["parent_id"].stringValueOrNull()?.toIntOrNull() ?: parentId,
          name = c2Name,
          href = c2Obj["href"].stringValueOrNull(),
        )
      }
      BilibiliCate1(parentAreaId = parentId, name = name, href = href, cate2List = sub)
    }.filter { it.cate2List.isNotEmpty() }
  }

internal suspend fun parseDouyinCategoriesBundle(json: Json): List<DouyinCate1> =
  parseCategoriesBundle(json, "categories/douyin_categories.json") { arr ->
    fun parseHrefToPartition(href: String): Pair<String, String>? {
      val parts = href.split("_")
      if (parts.size < 2) return null
      val partition = parts.last().trim()
      val partitionType = parts[parts.size - 2].trim()
      if (partition.isEmpty() || partitionType.isEmpty()) return null
      if (partition.toLongOrNull() == null || partitionType.toLongOrNull() == null) return null
      return partition to partitionType
    }

    arr.mapNotNull { c1El ->
      val obj = c1El.jsonObject
      val name = obj["title"].stringValueOrNull()?.trim().orEmpty()
      if (name.isBlank()) return@mapNotNull null
      val href = obj["href"].stringValueOrNull()
      val sub = obj["subcategories"]?.jsonArray.orEmpty().mapNotNull { c2El ->
        val c2Obj = c2El.jsonObject
        val c2Name = c2Obj["title"].stringValueOrNull()?.trim().orEmpty()
        val c2Href = c2Obj["href"].stringValueOrNull()?.trim().orEmpty()
        if (c2Name.isBlank() || c2Href.isBlank()) return@mapNotNull null
        val parsed = parseHrefToPartition(c2Href) ?: return@mapNotNull null
        DouyinCate2(
          partition = parsed.first,
          partitionType = parsed.second,
          name = c2Name,
          href = c2Href,
        )
      }
      DouyinCate1(name = name, href = href, cate2List = sub)
    }.filter { it.cate2List.isNotEmpty() }
  }
