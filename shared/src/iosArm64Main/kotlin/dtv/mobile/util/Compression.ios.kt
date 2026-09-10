package dtv.mobile.util

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFStringCreateWithBytes
import platform.CoreFoundation.kCFStringEncodingGB_18030_2000
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2
import platform.zlib.z_stream

// Lives in the iosArm64 leaf source set; only the on-device arm64 target is
// shipped. Uses the prebuilt platform.zlib klib (no custom cinterop needed).

actual fun inflateZlibOrNull(data: ByteArray): ByteArray? = inflateAutoOrNull(data)

actual fun gunzipOrNull(data: ByteArray): ByteArray? = inflateAutoOrNull(data)

/**
 * windowBits = 15 + 32 = 47 makes zlib auto-detect both zlib (RFC 1950) and
 * gzip (RFC 1952) wrappers.
 */
@OptIn(ExperimentalForeignApi::class)
private fun inflateAutoOrNull(data: ByteArray): ByteArray? {
  if (data.isEmpty()) return null
  val chunks = ArrayList<ByteArray>()
  try {
    memScoped {
      val stream = alloc<z_stream>()
      if (inflateInit2(stream.ptr, 47) != Z_OK) return null
      try {
        val chunkSize = (data.size * 6).coerceAtLeast(4096)
        var status = -9
        data.usePinned { pinIn ->
          stream.next_in = pinIn.addressOf(0).reinterpret()
          stream.avail_in = data.size.toUInt()
          do {
            val chunk = ByteArray(chunkSize)
            chunk.usePinned { pinOut ->
              stream.next_out = pinOut.addressOf(0).reinterpret()
              stream.avail_out = chunkSize.toUInt()
              status = inflate(stream.ptr, Z_NO_FLUSH)
              val produced = chunkSize - stream.avail_out.toInt()
              if (produced > 0) chunks.add(chunk.copyOf(produced))
            }
          } while (status == Z_OK)
        }
        if (status != Z_STREAM_END) return null
      } finally {
        inflateEnd(stream.ptr)
      }
    }
  } catch (e: Throwable) {
    return null
  }
  val total = chunks.sumOf { it.size }
  if (total == 0) return null
  val result = ByteArray(total)
  var pos = 0
  chunks.forEach { it.copyInto(result, pos); pos += it.size }
  return result
}

actual fun decodeTextBestEffort(bytes: ByteArray): String {
  if (bytes.isEmpty()) return ""
  val utf8 = bytes.decodeToString()
  if (!utf8.contains('�')) return utf8
  val gbk = decodeWithEncoding(bytes, kCFStringEncodingGB_18030_2000.toUInt()) ?: return utf8
  val utf8Bad = utf8.count { it == '�' }
  val gbkBad = gbk.count { it == '�' }
  return if (gbkBad < utf8Bad) gbk else utf8
}

@OptIn(ExperimentalForeignApi::class)
private fun decodeWithEncoding(bytes: ByteArray, encoding: UInt): String? {
  if (bytes.isEmpty()) return ""
  bytes.usePinned { pinned ->
    val cfString = CFStringCreateWithBytes(
      null,
      pinned.addressOf(0).reinterpret(),
      bytes.size.convert(),
      encoding,
      false,
    )
    return cfString as String?
  }
}
