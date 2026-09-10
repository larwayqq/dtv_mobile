package dtv.mobile.util

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFStringConvertEncodingToNSStringEncoding
import platform.CoreFoundation.kCFStringEncodingGB_18030_2000
import platform.Foundation.NSString
import platform.Compression.COMPRESSION_ZLIB
import platform.Compression.compression_decode_buffer

actual fun inflateZlibOrNull(data: ByteArray): ByteArray? {
  if (data.isEmpty()) return null
  // libcompression speaks raw deflate: strip the 2-byte zlib header and 4-byte adler trailer.
  val raw = if (data.size > 2 && ((data[0].toInt() and 0xFF) and 0x0F) == 0x08) {
    data.copyOfRange(2, data.size - 4)
  } else {
    data
  }
  return inflateRawOrNull(raw) ?: inflateRawOrNull(data)
}

actual fun gunzipOrNull(data: ByteArray): ByteArray? {
  if (data.size < 18) return null
  if ((data[0].toInt() and 0xFF) != 0x1F || (data[1].toInt() and 0xFF) != 0x8B) return null
  var pos = 10
  val flg = data[3].toInt() and 0xFF
  if (flg and 0x04 != 0) {
    // FEXTRA
    if (pos + 2 > data.size) return null
    val xlen = (data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8)
    pos += 2 + xlen
  }
  if (flg and 0x08 != 0) {
    // FNAME
    while (pos < data.size && data[pos].toInt() != 0) pos++
    pos++
  }
  if (flg and 0x10 != 0) {
    // FCOMMENT
    while (pos < data.size && data[pos].toInt() != 0) pos++
    pos++
  }
  if (flg and 0x02 != 0) pos += 2 // FHCRC
  if (pos >= data.size - 8) return null
  val raw = data.copyOfRange(pos, data.size - 8)
  return inflateRawOrNull(raw)
}

@OptIn(ExperimentalForeignApi::class)
private fun inflateRawOrNull(raw: ByteArray): ByteArray? {
  if (raw.isEmpty()) return null
  return memScoped {
    val src = allocArray<ByteVar>(raw.size)
    for (i in raw.indices) src[i] = raw[i]

    var capacity = (raw.size * 6).coerceAtLeast(1024)
    repeat(8) {
      val dst = allocArray<ByteVar>(capacity)
      val written = compression_decode_buffer(
        dst,
        capacity.convert(),
        src,
        raw.size.convert(),
        null,
        0u.convert(),
        COMPRESSION_ZLIB,
      ).toLong()
      if (written in 1 until capacity.toLong()) {
        return@memScoped ByteArray(written.toInt()) { i -> dst[i] }
      }
      capacity *= 4
    }
    null
  }
}

actual fun decodeTextBestEffort(bytes: ByteArray): String {
  if (bytes.isEmpty()) return ""
  val utf8 = bytes.decodeToString()
  if (!utf8.contains('�')) return utf8
  val gbk = decodeWithEncoding(
    bytes,
    CFStringConvertEncodingToNSStringEncoding(kCFStringEncodingGB_18030_2000),
  ) ?: return utf8
  val utf8Bad = utf8.count { it == '�' }
  val gbkBad = gbk.count { it == '�' }
  return if (gbkBad < utf8Bad) gbk else utf8
}

@OptIn(ExperimentalForeignApi::class)
private fun decodeWithEncoding(bytes: ByteArray, encoding: UInt): String? {
  if (bytes.isEmpty()) return ""
  return bytes.usePinned { pinned ->
    val ns = NSString.alloc().initWithBytes(
      pinned.addressOf(0),
      bytes.size.toULong(),
      encoding,
    )
    ns as String
  }
}
