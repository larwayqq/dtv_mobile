package dtv.mobile.state

import dtv.mobile.model.Streamer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import platform.Foundation.NSUserDefaults

@OptIn(ExperimentalForeignApi::class)
class SubscriptionStoreIos : SubscriptionStore {
  private val prefs = NSUserDefaults.standardUserDefaults
  private val json = Json { ignoreUnknownKeys = true }

  private fun getString(key: String): String? = prefs.stringForKey(key)?.takeIf { it.isNotBlank() }
  private fun setString(key: String, value: String) {
    prefs.setObject(value, forKey = key)
  }

  override fun loadThemeMode(): ThemeMode {
    val raw = prefs.stringForKey("theme_mode")?.trim().orEmpty()
    return runCatching { ThemeMode.valueOf(raw) }.getOrElse { ThemeMode.System }
  }

  override fun saveThemeMode(value: ThemeMode) {
    setString("theme_mode", value.name)
  }

  override fun loadFollowedStreamers(): List<Streamer> {
    val raw = getString("followed_streamers") ?: return emptyList()
    return runCatching { json.decodeFromString(ListSerializer(Streamer.serializer()), raw) }.getOrElse { emptyList() }
  }

  override fun saveFollowedStreamers(items: List<Streamer>) {
    setString("followed_streamers", json.encodeToString(ListSerializer(Streamer.serializer()), items))
  }

  override fun loadPinnedFollowedStreamerKeys(): List<String> {
    val raw = getString("pinned_followed_streamer_keys") ?: return emptyList()
    return runCatching { json.decodeFromString(ListSerializer(String.serializer()), raw) }.getOrElse { emptyList() }
  }

  override fun savePinnedFollowedStreamerKeys(items: List<String>) {
    setString("pinned_followed_streamer_keys", json.encodeToString(ListSerializer(String.serializer()), items))
  }

  override fun loadLandscapeDanmakuFontScale(): Float =
    prefs.floatForKey("landscape_danmaku_font_scale").let { if (it == 0.0f) 1.2f else it }.coerceIn(0.85f, 2.0f)

  override fun saveLandscapeDanmakuFontScale(value: Float) {
    prefs.setFloat(value.coerceIn(0.85f, 2.0f), forKey = "landscape_danmaku_font_scale")
  }

  override fun loadDanmakuFontScale(): Float =
    prefs.floatForKey("danmaku_font_scale").let { if (it == 0.0f) 1.0f else it }.coerceIn(0.85f, 1.3f)

  override fun saveDanmakuFontScale(value: Float) {
    prefs.setFloat(value.coerceIn(0.85f, 1.3f), forKey = "danmaku_font_scale")
  }

  override fun loadDanmakuOpacity(): Float =
    prefs.floatForKey("danmaku_opacity").let { if (it == 0.0f) 1.0f else it }.coerceIn(0.35f, 1.0f)

  override fun saveDanmakuOpacity(value: Float) {
    prefs.setFloat(value.coerceIn(0.35f, 1.0f), forKey = "danmaku_opacity")
  }

  override fun loadDanmakuAreaFraction(): Float =
    prefs.floatForKey("danmaku_area_fraction").let { if (it == 0.0f) 0.5f else it }.coerceIn(0.25f, 1.0f)

  override fun saveDanmakuAreaFraction(value: Float) {
    prefs.setFloat(value.coerceIn(0.25f, 1.0f), forKey = "danmaku_area_fraction")
  }

  override fun loadSubscribedPartitions(): List<SubscribedPartition> {
    val raw = getString("subscribed_partitions") ?: return emptyList()
    return runCatching { json.decodeFromString(ListSerializer(SubscribedPartition.serializer()), raw) }.getOrElse { emptyList() }
  }

  override fun saveSubscribedPartitions(items: List<SubscribedPartition>) {
    setString("subscribed_partitions", json.encodeToString(ListSerializer(SubscribedPartition.serializer()), items))
  }

  override fun loadDanmuBlockKeywords(): List<String> {
    val raw = getString("danmu_block_keywords") ?: return emptyList()
    return runCatching { json.decodeFromString(ListSerializer(String.serializer()), raw) }.getOrElse { emptyList() }
  }

  override fun saveDanmuBlockKeywords(items: List<String>) {
    setString("danmu_block_keywords", json.encodeToString(ListSerializer(String.serializer()), items))
  }

  override fun loadSimpleModeByPlatform(): List<SimpleModeEntry> {
    val raw = getString("simple_mode_by_platform") ?: return emptyList()
    return runCatching { json.decodeFromString(ListSerializer(SimpleModeEntry.serializer()), raw) }.getOrElse { emptyList() }
  }

  override fun saveSimpleModeByPlatform(items: List<SimpleModeEntry>) {
    setString("simple_mode_by_platform", json.encodeToString(ListSerializer(SimpleModeEntry.serializer()), items))
  }
}
