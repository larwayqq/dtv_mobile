package dtv.mobile.platform.douyin

import dtv.mobile.util.md5Hex
import dtv.mobile.util.readBundleAssetText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.autoreleasepool
import platform.JavaScriptCore.JSContext

/**
 * WebMsSDK signature generator (iOS):
 * - Loads `douyin/webmssdk.js` from Compose resources
 * - Evaluates `getMSSDKSignature(msStub, userAgent)` via JavaScriptCore, retrying up to 12 times
 */
internal class DouyinWebMsdkSignatureIos {
  companion object {
    private const val WEB_MSSDK_JS_RESOURCE = "douyin/webmssdk.js"

    private val MS_STUB_KEYS = listOf(
      "live_id",
      "aid",
      "version_code",
      "webcast_sdk_version",
      "room_id",
      "sub_room_id",
      "sub_channel_id",
      "did_rule",
      "user_unique_id",
      "device_platform",
      "device_type",
      "ac",
      "identity",
    )
  }

  private fun msStub(roomId: String, userUniqueId: String, webcastSdkVersion: String): String {
    val params = linkedMapOf(
      "live_id" to "1",
      "aid" to "6383",
      "version_code" to "180800",
      "webcast_sdk_version" to webcastSdkVersion,
      "room_id" to roomId,
      "sub_room_id" to "",
      "sub_channel_id" to "",
      "did_rule" to "3",
      "user_unique_id" to userUniqueId,
      "device_platform" to "web",
      "device_type" to "",
      "ac" to "",
      "identity" to "audience",
    )
    val toSign = MS_STUB_KEYS.joinToString(",") { k -> "$k=${params[k].orEmpty()}" }
    return md5Hex(toSign)
  }

  suspend fun signature(roomId: String, userUniqueId: String, webcastSdkVersion: String, userAgent: String): String {
    val stub = msStub(roomId = roomId, userUniqueId = userUniqueId, webcastSdkVersion = webcastSdkVersion)
    val js = readBundleAssetText(WEB_MSSDK_JS_RESOURCE)
    // JavaScriptCore contexts are pinned to their creating thread; keep all
    // JSContext work on the main thread with an explicit autorelease pool.
    return withContext(Dispatchers.Main) {
      autoreleasepool {
        val ctx = JSContext()
        var jsError: String? = null
        ctx.exceptionHandler = { _, ex -> jsError = ex?.toString() }
        ctx.evaluateScript(js)
        check(jsError == null) { "webmssdk load failed: $jsError" }

        var out = ""
        repeat(12) {
          val expr = "getMSSDKSignature(${stub.toJsStringLiteral()}, ${userAgent.toJsStringLiteral()})"
          val v = ctx.evaluateScript(expr)?.toString().orEmpty().trim()
          if (v.isNotBlank() && !v.contains('-') && !v.contains('=')) {
            out = v
            return@repeat
          }
        }
        if (out.isBlank()) error("empty/invalid douyin WebMsSDK signature")
        out
      }
    }
  }

  private fun String.toJsStringLiteral(): String =
    buildString(this.length + 2) {
      append('"')
      for (ch in this@toJsStringLiteral) {
        when (ch) {
          '\\' -> append("\\\\")
          '"' -> append("\\\"")
          '\n' -> append("\\n")
          '\r' -> append("\\r")
          '\t' -> append("\\t")
          else -> append(ch)
        }
      }
      append('"')
    }
}
