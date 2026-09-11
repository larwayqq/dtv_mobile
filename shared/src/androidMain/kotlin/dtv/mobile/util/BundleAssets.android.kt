package dtv.mobile.util

import dtv_mobile.shared.generated.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.readBytes

@OptIn(ExperimentalResourceApi::class)
actual suspend fun readBundleAssetBytes(path: String): ByteArray =
  Res.readBytes("files/$path")
