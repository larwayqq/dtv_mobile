package dtv.mobile.util

import dtv_mobile.shared.generated.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi

/** Read a file packaged under commonMain/composeResources/files as UTF-8 text. */
suspend fun readBundleAssetText(path: String): String =
  readBundleAssetBytes(path).decodeToString()

/** Read a file packaged under commonMain/composeResources/files as bytes. */
@OptIn(ExperimentalResourceApi::class)
suspend fun readBundleAssetBytes(path: String): ByteArray =
  Res.readBytes("files/$path")
