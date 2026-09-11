package dtv.mobile.util

/** Read a file packaged under commonMain/composeResources/files as UTF-8 text. */
suspend fun readBundleAssetText(path: String): String =
  readBundleAssetBytes(path).decodeToString()

/** Read a file packaged under commonMain/composeResources/files as bytes. */
expect suspend fun readBundleAssetBytes(path: String): ByteArray
