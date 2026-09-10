package dtv.mobile.util

/**
 * Inflate RFC1950 (zlib wrapped) or RFC1951 (raw deflate) bytes.
 * Used by the Bilibili danmaku protocol when it returns zlib-compressed batches.
 */
expect fun inflateZlibOrNull(data: ByteArray): ByteArray?

/** Decompress a gzip (RFC1952) payload, as used by the Douyin danmaku protocol. */
expect fun gunzipOrNull(data: ByteArray): ByteArray?

/** Decode bytes as UTF-8, falling back to GBK when the bytes clearly aren't valid UTF-8. */
expect fun decodeTextBestEffort(bytes: ByteArray): String
