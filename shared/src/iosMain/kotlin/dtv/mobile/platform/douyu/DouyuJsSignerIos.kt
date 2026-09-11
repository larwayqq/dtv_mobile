package dtv.mobile.platform.douyu

import dtv.mobile.util.readBundleAssetText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.objc.autoreleasepool
import platform.JavaScriptCore.JSContext
import platform.JavaScriptCore.JSValue

/**
 * iOS Douyu signer: evaluates CryptoJS + homeH5Enc inside JavaScriptCore and
 * calls `ub98484234(roomId, did, ts)` — same contract as the Android Rhino signer.
 *
 * JavaScriptCore contexts are pinned to their creating thread; running on a
 * Dispatchers.Default worker interacts badly with the Kotlin/Native GC and the
 * JSC GC, so all JSContext work is forced onto the main thread inside an
 * explicit autorelease pool.
 */
class DouyuJsSignerIos {
  suspend fun signParams(
    homeH5EncScript: String,
    roomId: String,
    did: String,
    tsSeconds: Long,
  ): String {
    val cryptoJs = readBundleAssetText("douyu/cryptojs.min.js")
    return withContext(Dispatchers.Main) {
      autoreleasepool {
        val ctx = JSContext()
        var jsError: String? = null
        ctx.exceptionHandler = { _, exception ->
          jsError = exception?.toString()
        }
        ctx.evaluateScript(cryptoJs)
        check(jsError == null) { "cryptojs evaluate failed: $jsError" }
        ctx.evaluateScript(homeH5EncScript)
        check(jsError == null) { "homeH5Enc evaluate failed: $jsError" }

        val result: JSValue? = ctx.evaluateScript(
          "ub98484234(${jsString(roomId)},${jsString(did)},$tsSeconds);",
        )
        val out = result?.toString()
        if (jsError != null) error("douyu sign call failed: $jsError")
        if (result == null || result.isUndefined() || out.isNullOrBlank()) {
          error("douyu sign returned empty result")
        }
        out
      }
    }
  }

  private fun jsString(input: String): String {
    val escaped = buildString {
      input.forEach { ch ->
        when (ch) {
          '\\' -> append("\\\\")
          '"' -> append("\\\"")
          '\n' -> append("\\n")
          '\r' -> append("\\r")
          '\t' -> append("\\t")
          else -> append(ch)
        }
      }
    }
    return "\"$escaped\""
  }
}
