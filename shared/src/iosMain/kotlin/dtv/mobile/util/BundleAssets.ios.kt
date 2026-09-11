package dtv.mobile.util

import dtv_mobile.shared.generated.resources.Res
import kotlinx.coroutines.withTimeout
import org.jetbrains.compose.resources.ExperimentalResourceApi
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager

@OptIn(ExperimentalResourceApi::class)
actual suspend fun readBundleAssetBytes(path: String): ByteArray {
  // 1. Compose Multiplatform default lookup (main bundle compose-resources).
  //    Res.readBytes may hang indefinitely on iOS if the resource reader isn't
  //    initialised; wrap in a withTimeout so we fall through to file-path lookup.
  val composeResult = runCatching {
    withTimeout(3_000) { Res.readBytes("files/$path") }
  }
  composeResult.getOrNull()?.let { return it }

  // 2. Explicit multi-bundle fallback (main bundle + every embedded framework).
  val relative = "compose-resources/files/$path"
  val candidates = buildList {
    NSBundle.mainBundle.resourcePath?.let { add("$it/$relative") }
    add("${NSBundle.mainBundle.bundlePath}/$relative")
    NSBundle.allFrameworks?.forEach { fw ->
      (fw as? NSBundle)?.resourcePath?.let { add("$it/$relative") }
    }
  }
  val fileManager = NSFileManager.defaultManager()
  for (candidate in candidates) {
    val data = fileManager.contentsAtPath(candidate) ?: continue
    return data.toByteArray()
  }

  // Record detailed diagnostics so the error is visible in the UI banner.
  val composeError = composeResult.exceptionOrNull()?.message ?: "unknown"
  Diagnostics.record(
    "资源读取",
    "asset not found: files/$path\ncompose error: $composeError\ntried: ${candidates.joinToString(" | ")}",
  )
  error("asset not found: files/$path; tried: ${candidates.joinToString(" | ")}")
}
