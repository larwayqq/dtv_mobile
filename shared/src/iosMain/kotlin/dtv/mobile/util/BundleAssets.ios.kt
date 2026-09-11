package dtv.mobile.util

import dtv_mobile.shared.generated.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager

@OptIn(ExperimentalResourceApi::class)
actual suspend fun readBundleAssetBytes(path: String): ByteArray {
  // 1. Compose Multiplatform default lookup (main bundle compose-resources).
  runCatching { Res.readBytes("files/$path") }.getOrNull()?.let { return it }

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
  error("asset not found: files/$path; tried: ${candidates.joinToString(" | ")}")
}
