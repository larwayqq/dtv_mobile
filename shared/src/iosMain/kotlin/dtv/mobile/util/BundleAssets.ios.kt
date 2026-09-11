package dtv.mobile.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager

/**
 * iOS resource reader.
 *
 * IMPORTANT: We deliberately do NOT call Compose's `Res.readBytes` here.
 * On CMP 1.6.11/iOS it performs a synchronous lookup tied to the main run
 * loop; when first invoked from a main-thread coroutine (LaunchedEffect) it
 * can deadlock the UI thread completely — withTimeout cannot interrupt a
 * synchronous block. Instead we resolve the file ourselves via NSFileManager
 * on a background thread. The Xcode post-build script guarantees the files
 * exist at `<bundle>/compose-resources/files/...` in both the app bundle and
 * the embedded shared.framework.
 */
actual suspend fun readBundleAssetBytes(path: String): ByteArray =
  withContext(Dispatchers.Default) {
    val relative = "compose-resources/files/$path"
    val mainBundle = NSBundle.mainBundle
    val candidates = buildList {
      mainBundle.resourcePath?.let { add("$it/$relative") }
      add("${mainBundle.bundlePath}/$relative")
      NSBundle.allFrameworks?.forEach { fw ->
        (fw as? NSBundle)?.resourcePath?.let { add("$it/$relative") }
      }
    }
    val fileManager = NSFileManager.defaultManager()
    for (candidate in candidates) {
      val data = fileManager.contentsAtPath(candidate) ?: continue
      return@withContext data.toByteArray()
    }
    Diagnostics.record(
      "资源读取",
      "asset not found: files/$path; tried: ${candidates.joinToString(" | ")}",
    )
    error("asset not found: files/$path; tried: ${candidates.joinToString(" | ")}")
  }
