package dtv.mobile.repo.ios

import dtv.mobile.model.Platform
import dtv.mobile.model.Streamer
import dtv.mobile.net.createHttpClient
import dtv.mobile.platform.bilibili.BilibiliAuthApiIos
import dtv.mobile.platform.bilibili.BilibiliCookieStoreIos
import dtv.mobile.platform.bilibili.BilibiliDanmakuClientIos
import dtv.mobile.platform.bilibili.BilibiliLiveListApiIos
import dtv.mobile.platform.bilibili.BilibiliSearchApiIos
import dtv.mobile.platform.bilibili.BilibiliStreamUrlResolverIos
import dtv.mobile.platform.douyin.DouyinDanmakuClientIos
import dtv.mobile.platform.douyin.DouyinWebApiIos
import dtv.mobile.platform.douyu.DouyuCate2DirectoryApi
import dtv.mobile.platform.douyu.DouyuCate3DirectoryApi
import dtv.mobile.platform.douyu.DouyuCategoriesApi
import dtv.mobile.platform.douyu.DouyuDanmakuClientIos
import dtv.mobile.platform.douyu.DouyuMobileApi
import dtv.mobile.platform.douyu.DouyuSearchApiIos
import dtv.mobile.platform.douyu.DouyuThreeCateApi
import dtv.mobile.platform.douyu.DouyuStreamUrlResolverIos
import dtv.mobile.platform.huya.HuyaDanmakuClientIos
import dtv.mobile.platform.huya.HuyaLiveListApiIos
import dtv.mobile.platform.huya.HuyaSearchApiIos
import dtv.mobile.platform.huya.HuyaStreamUrlResolverIos
import dtv.mobile.repo.BilibiliCate1
import dtv.mobile.repo.BilibiliQrCode
import dtv.mobile.repo.BilibiliQrPollResult
import dtv.mobile.repo.DanmakuMessage
import dtv.mobile.repo.DouyinCate1
import dtv.mobile.repo.DouyuCate1
import dtv.mobile.repo.DouyuCate2
import dtv.mobile.repo.DouyuCate3
import dtv.mobile.repo.DouyuCategories
import dtv.mobile.repo.DouyuPlayInfo
import dtv.mobile.repo.DtvRepository
import dtv.mobile.repo.FollowInfoApi
import dtv.mobile.repo.HuyaCate1
import dtv.mobile.repo.PagedResult
import dtv.mobile.repo.fake.FakeDtvRepository
import dtv.mobile.repo.parseBilibiliCategoriesBundle
import dtv.mobile.repo.parseDouyinCategoriesBundle
import dtv.mobile.repo.parseHuyaCategoriesBundle
import dtv.mobile.util.AppLog
import dtv.mobile.util.formatViewerCountWanIfNeeded
import dtv.mobile.util.normalizeHttpUrl
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class IOSDtvRepository : DtvRepository {
  private val client = createHttpClient()
  private val json = Json { ignoreUnknownKeys = true; isLenient = true }

  private val douyuMobileApi = DouyuMobileApi(client)
  private val douyuCategoriesApi = DouyuCategoriesApi(client)
  private val douyuThreeCateApi = DouyuThreeCateApi(client)
  private val douyuCate2DirectoryApi = DouyuCate2DirectoryApi(client)
  private val douyuCate3DirectoryApi = DouyuCate3DirectoryApi(client)
  private val douyuStreamResolver = DouyuStreamUrlResolverIos(client)
  private val douyuDanmakuClient = DouyuDanmakuClientIos()
  private val douyuSearchApi = DouyuSearchApiIos(client)

  private val huyaLiveListApi = HuyaLiveListApiIos(client)
  private val huyaStreamResolver = HuyaStreamUrlResolverIos(client)
  private val huyaDanmakuClient = HuyaDanmakuClientIos(client)
  private val huyaSearchApi = HuyaSearchApiIos(client)

  private val douyinWebApi = DouyinWebApiIos(client)
  private val douyinDanmakuClient = DouyinDanmakuClientIos(douyinWebApi, client)

  private val bilibiliCookieStore = BilibiliCookieStoreIos()
  private val bilibiliAuthApi = BilibiliAuthApiIos(client, bilibiliCookieStore)
  private val bilibiliLiveListApi = BilibiliLiveListApiIos(client)
  private val bilibiliStreamResolver = BilibiliStreamUrlResolverIos(client) { bilibiliCookieStore.getCookie() }
  private val bilibiliSearchApi = BilibiliSearchApiIos(client, bilibiliCookieStore)
  private val bilibiliDanmakuClient = BilibiliDanmakuClientIos(
    httpClient = client,
    cookieProvider = { bilibiliCookieStore.getCookie() },
  )

  private val fallback = FakeDtvRepository()

  private val followInfoApi = FollowInfoApi(
    client = client,
    json = json,
    douyuIsLive = { rid -> runCatching { douyuStreamResolver.isLive(rid) }.getOrNull() },
    huyaIsLive = { rid -> runCatching { huyaStreamResolver.isLive(rid) }.getOrNull() },
  )

  override suspend fun searchAnchors(platform: Platform, keyword: String): List<Streamer> {
    val trimmed = keyword.trim()
    if (trimmed.isEmpty()) return emptyList()
    return when (platform) {
      Platform.Douyu -> runCatching { douyuSearchApi.searchAnchors(trimmed) }.getOrElse { emptyList() }
      Platform.Huya -> runCatching { huyaSearchApi.searchAnchors(trimmed) }.getOrElse { emptyList() }
      Platform.Bilibili -> runCatching { bilibiliSearchApi.searchRooms(trimmed) }.getOrElse { emptyList() }
      Platform.Douyin -> runCatching {
        val info = douyinWebApi.fetchRoomEnter(trimmed)
        val title = info.title?.trim().orEmpty().ifBlank { "直播中" }
        val name = info.anchorName?.trim().orEmpty().ifBlank { "抖音主播" }
        val isLive = info.hlsPullUrlMap.isNotEmpty() || info.flvPullUrlMap.isNotEmpty()
        listOf(
          Streamer(
            platform = Platform.Douyin,
            roomId = trimmed,
            name = name,
            title = title,
            viewerText = "",
            avatarUrl = normalizeHttpUrl(info.avatarUrl),
            coverUrl = null,
            isLive = isLive,
          ),
        )
      }.getOrElse { emptyList() }
      else -> emptyList()
    }
  }

  override suspend fun fetchLiveStatus(streamer: Streamer): Boolean? {
    return when (streamer.platform) {
      Platform.Douyu -> runCatching { douyuStreamResolver.isLive(streamer.roomId) }.getOrNull()
      Platform.Huya -> runCatching { huyaStreamResolver.isLive(streamer.roomId) }.getOrNull()
      Platform.Douyin -> runCatching {
        val info = douyinWebApi.fetchRoomEnter(streamer.roomId)
        info.hlsPullUrlMap.isNotEmpty() || info.flvPullUrlMap.isNotEmpty()
      }.getOrNull()
      Platform.Bilibili -> runCatching { fetchBilibiliLiveStatus(streamer.roomId) }.getOrNull()
      else -> null
    }
  }

  override suspend fun fetchFollowedStreamerSnapshot(streamer: Streamer): Streamer? {
    val roomId = streamer.roomId.trim()
    if (roomId.isBlank()) return null

    return when (streamer.platform) {
      Platform.Douyu -> runCatching {
        val info = followInfoApi.fetchDouyu(roomId)
        streamer.copy(
          name = info.name ?: streamer.name,
          title = info.title ?: streamer.title,
          viewerText = info.viewerText ?: streamer.viewerText,
          avatarUrl = info.avatarUrl ?: streamer.avatarUrl,
          coverUrl = info.coverUrl ?: streamer.coverUrl,
          isLive = info.isLive ?: streamer.isLive,
        )
      }.getOrNull()

      Platform.Huya -> runCatching {
        val info = followInfoApi.fetchHuya(roomId)
        streamer.copy(
          name = info.name ?: streamer.name,
          title = info.title ?: streamer.title,
          viewerText = info.viewerText ?: streamer.viewerText,
          avatarUrl = info.avatarUrl ?: streamer.avatarUrl,
          coverUrl = info.coverUrl ?: streamer.coverUrl,
          isLive = info.isLive ?: streamer.isLive,
        )
      }.getOrNull()

      Platform.Bilibili -> runCatching {
        val info = followInfoApi.fetchBilibili(roomId)
        streamer.copy(
          name = info.name ?: streamer.name,
          title = info.title ?: streamer.title,
          viewerText = info.viewerText ?: streamer.viewerText,
          avatarUrl = info.avatarUrl ?: streamer.avatarUrl,
          coverUrl = info.coverUrl ?: streamer.coverUrl,
          isLive = info.isLive ?: streamer.isLive,
        )
      }.getOrNull()

      Platform.Douyin -> runCatching {
        val info = douyinWebApi.fetchRoomEnter(roomId)
        val isLive = info.hlsPullUrlMap.isNotEmpty() || info.flvPullUrlMap.isNotEmpty()
        streamer.copy(
          name = info.anchorName?.trim()?.ifBlank { null } ?: streamer.name,
          title = info.title?.trim()?.ifBlank { null } ?: streamer.title,
          viewerText = info.viewerCountStr?.trim()?.ifBlank { null } ?: streamer.viewerText,
          avatarUrl = normalizeHttpUrl(info.avatarUrl) ?: streamer.avatarUrl,
          coverUrl = normalizeHttpUrl(info.coverUrl) ?: streamer.coverUrl,
          isLive = isLive,
        )
      }.getOrNull()

      else -> null
    }
  }

  private suspend fun fetchBilibiliLiveStatus(roomId: String): Boolean {
    val url = "https://api.live.bilibili.com/room/v1/Room/get_info?room_id=$roomId"
    val text = client.get(url).bodyAsText()
    val obj = json.parseToJsonElement(text).jsonObject
    val data = obj["data"]?.jsonObject ?: return false
    val status = data["live_status"]?.jsonPrimitive?.intOrNull ?: 0
    return status == 1
  }

  override suspend fun fetchHuyaCategories(): List<HuyaCate1> =
    runCatching { parseHuyaCategoriesBundle(json) }.getOrElse { emptyList() }

  override suspend fun fetchBilibiliCategories(): List<BilibiliCate1> =
    runCatching { parseBilibiliCategoriesBundle(json) }.getOrElse { emptyList() }

  override suspend fun fetchDouyinCategories(): List<DouyinCate1> =
    runCatching { parseDouyinCategoriesBundle(json) }.getOrElse { emptyList() }

  override suspend fun fetchDouyuCategories(): DouyuCategories {
    return runCatching {
      val resp = douyuCategoriesApi.fetchCateList()
      if (resp.code != 0) return fallback.fetchDouyuCategories()
      val data = resp.data ?: return fallback.fetchDouyuCategories()

      val cate2ByCate1 = data.cate2Info.groupBy { it.cate1Id }
      val cate1List = data.cate1Info.map { c1 ->
        val rawC2 = cate2ByCate1[c1.cate1Id].orEmpty()
        val c2List = rawC2.map { c2 ->
          DouyuCate2(
            id = c2.cate2Id.toString(),
            name = c2.cate2Name,
            shortName = c2.shortName,
            iconUrl = c2.icon,
          )
        }.toMutableList()

        if (c1.cate1Id == 2) {
          c2List.add(
            DouyuCate2(
              id = "208",
              name = "一起看",
              shortName = "yqk",
              iconUrl = "https://sta-op.douyucdn.cn/dycatr/7c723d30bfb4399be7592c9fa12026e3.png",
            ),
          )
        }

        DouyuCate1(
          id = c1.cate1Id.toString(),
          name = c1.cate1Name,
          cate2List = c2List,
        )
      }

      DouyuCategories(cate1List = cate1List)
    }.getOrElse { fallback.fetchDouyuCategories() }
  }

  override suspend fun fetchDouyuThreeCate(cate2Id: String): List<DouyuCate3> {
    return runCatching {
      val resp = douyuThreeCateApi.fetchThreeCate(cate2Id)
      if (resp.error != 0) return emptyList()
      resp.data.orEmpty()
        .mapNotNull { raw ->
          val id = raw.id.trim()
          val name = raw.name.trim()
          if (id.isEmpty() || name.isEmpty()) return@mapNotNull null
          DouyuCate3(id = id, name = name, iconUrl = raw.iconResolved)
        }
    }.getOrDefault(emptyList())
  }

  override suspend fun fetchDouyuLiveListByCate2(cate2Id: String, offset: Int, limit: Int): PagedResult<Streamer> {
    return runCatching {
      val page = (offset / limit) + 1
      val resp = douyuCate2DirectoryApi.fetchMixListV1(cate2Id = cate2Id, page = page, limit = limit)
      if (resp.code != 0) return PagedResult(emptyList(), total = null)
      val data = resp.data ?: return PagedResult(emptyList(), total = null)
      val items = data.rl.map { s ->
        Streamer(
          platform = Platform.Douyu,
          roomId = s.rid.toString(),
          name = s.nn,
          title = s.rn,
          viewerText = formatViewerCountWanIfNeeded(s.ol.toString()),
          avatarUrl = normalizeHttpUrl(s.av.ifBlank { null }),
          coverUrl = normalizeHttpUrl(s.rs16.ifBlank { null }),
          isLive = s.type?.let { it == 1 } ?: true,
        )
      }
      PagedResult(items = items, total = null)
    }.getOrElse {
      PagedResult(items = emptyList(), total = null)
    }
  }

  override suspend fun fetchDouyuLiveListByCate3(cate3Id: String, page: Int, limit: Int): PagedResult<Streamer> {
    return runCatching {
      val resp = douyuCate3DirectoryApi.fetchMixListV1(cate3Id = cate3Id, page = page, limit = limit)
      if (resp.code != 0) return PagedResult(emptyList(), total = null)
      val data = resp.data ?: return PagedResult(emptyList(), total = null)
      val items = data.rl.map { s ->
        Streamer(
          platform = Platform.Douyu,
          roomId = s.rid.toString(),
          name = s.nn,
          title = s.rn,
          viewerText = formatViewerCountWanIfNeeded(s.ol.toString()),
          avatarUrl = normalizeHttpUrl(s.av.ifBlank { null }),
          coverUrl = normalizeHttpUrl(s.rs16.ifBlank { null }),
          isLive = s.type?.let { it == 1 } ?: true,
        )
      }
      PagedResult(items = items, total = null)
    }.getOrElse {
      PagedResult(items = emptyList(), total = null)
    }
  }

  override suspend fun fetchDouyuPlayInfo(roomId: String): DouyuPlayInfo {
    return runCatching { douyuStreamResolver.fetchPlayInfo(roomId) }
      .onFailure { AppLog.e("DTV-Douyu", "fetchPlayInfo failed roomId=$roomId", it) }
      .getOrThrow()
  }

  override suspend fun resolveDouyuStreamUrl(roomId: String, quality: String?, cdn: String?): String {
    return runCatching {
      douyuStreamResolver.resolve(roomId = roomId, quality = quality, cdn = cdn)
    }
      .onSuccess { AppLog.i("DTV-Douyu", "resolved hls url roomId=$roomId url=$it") }
      .onFailure { AppLog.e("DTV-Douyu", "resolve stream url failed roomId=$roomId", it) }
      .getOrThrow()
  }

  override fun observeDouyuDanmaku(roomId: String): Flow<DanmakuMessage> =
    douyuDanmakuClient.observe(roomId)

  override fun observeHuyaDanmaku(roomId: String): Flow<DanmakuMessage> =
    huyaDanmakuClient.observe(roomId)

  override fun observeDouyinDanmaku(webRid: String): Flow<DanmakuMessage> =
    douyinDanmakuClient.observe(webRid)

  override suspend fun fetchHuyaLiveList(gid: String, page: Int, limit: Int): PagedResult<Streamer> {
    return runCatching {
      val items = huyaLiveListApi.fetchLiveList(gid = gid, page = page, pageSize = limit)
      PagedResult(items = items, total = null)
    }.getOrElse { PagedResult(items = emptyList(), total = null) }
  }

  override suspend fun resolveHuyaStreamUrl(roomId: String): String {
    return runCatching { huyaStreamResolver.resolve(roomId) }
      .onFailure { AppLog.e("DTV-Huya", "resolve stream url failed roomId=$roomId", it) }
      .getOrThrow()
  }

  override suspend fun fetchDouyinPartitionLiveList(
    partition: String,
    partitionType: String,
    offset: Int,
    limit: Int,
    msToken: String,
  ): PagedResult<Streamer> {
    return runCatching {
      val resp = douyinWebApi.fetchPartitionRooms(
        partition = partition,
        partitionType = partitionType,
        offset = offset,
        limit = limit,
        msToken = msToken,
      )
      val items = resp.rooms.map { r ->
        Streamer(
          platform = Platform.Douyin,
          roomId = r.webRid,
          name = r.ownerNickname,
          title = r.title,
          viewerText = formatViewerCountWanIfNeeded(r.userCountStr),
          avatarUrl = normalizeHttpUrl(r.avatarUrl),
          coverUrl = normalizeHttpUrl(r.coverUrl),
          isLive = true,
        )
      }
      PagedResult(items = items, total = null)
    }.getOrElse {
      PagedResult(items = emptyList(), total = null)
    }
  }

  override suspend fun resolveDouyinStreamUrl(webRid: String, desiredQuality: String?): String {
    return runCatching {
      val room = douyinWebApi.fetchRoomEnter(webRid)
      room.roomId?.takeIf { it.isNotBlank() }?.let { roomId ->
        douyinDanmakuClient.prime(webRid = webRid, roomId = roomId, msToken = room.msToken)
      }
      val q = desiredQuality?.trim()?.takeIf { it.isNotBlank() }
      val hls = when {
        q == null -> null
        room.hlsPullUrlMap.containsKey(q) -> room.hlsPullUrlMap[q]
        room.hlsPullUrlMap.containsKey(q.uppercase()) -> room.hlsPullUrlMap[q.uppercase()]
        else -> null
      }
      val flv = when {
        q == null -> null
        room.flvPullUrlMap.containsKey(q) -> room.flvPullUrlMap[q]
        room.flvPullUrlMap.containsKey(q.uppercase()) -> room.flvPullUrlMap[q.uppercase()]
        else -> null
      }

      val hlsFallback = room.hlsPullUrlMap["ORIGIN"] ?: room.hlsPullUrlMap.values.firstOrNull()
      val flvFallback = room.flvPullUrlMap["ORIGIN"] ?: room.flvPullUrlMap.values.firstOrNull()
      // AVPlayer supports HLS only.
      (hls ?: hlsFallback) ?: error("未找到抖音HLS播放地址")
    }
      .onFailure { AppLog.e("DTV-Douyin", "resolve stream url failed webRid=$webRid", it) }
      .getOrThrow()
  }

  override suspend fun fetchBilibiliLiveList(parentAreaId: Int, areaId: Int, page: Int, pageSize: Int): PagedResult<Streamer> {
    return runCatching {
      val items = bilibiliLiveListApi.fetchLiveList(parentAreaId = parentAreaId, areaId = areaId, page = page, pageSize = pageSize)
      PagedResult(items = items, total = null)
    }.getOrElse {
      PagedResult(items = emptyList(), total = null)
    }
  }

  override suspend fun resolveBilibiliStreamUrl(roomId: String, qn: Int?): String {
    return runCatching { bilibiliStreamResolver.resolve(roomId = roomId, qn = qn) }
      .onFailure { AppLog.e("DTV-Bilibili", "resolve stream url failed roomId=$roomId", it) }
      .getOrThrow()
  }

  override fun observeBilibiliDanmaku(roomId: String): Flow<DanmakuMessage> =
    bilibiliDanmakuClient.observe(roomId)

  override suspend fun generateBilibiliQrCode(): BilibiliQrCode = bilibiliAuthApi.generateQrCode()

  override suspend fun pollBilibiliQrCode(qrcodeKey: String): BilibiliQrPollResult =
    bilibiliAuthApi.pollQrCode(qrcodeKey)

  override suspend fun getBilibiliCookie(): String? = bilibiliCookieStore.getCookie()

  override suspend fun mergeBilibiliCookie(cookieHeader: String) {
    bilibiliCookieStore.mergeFromCookieHeader(cookieHeader)
  }

  override suspend fun clearBilibiliCookie() {
    bilibiliCookieStore.clear()
  }
}
