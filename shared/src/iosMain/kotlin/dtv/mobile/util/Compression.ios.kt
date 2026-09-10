package dtv.mobile.util

import compression.COMPRESSION_ZLIB
import compression.compression_decode_buffer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import platform.CoreFoundation.CFStringConvertEncodingToNSStringEncoding
import platform.CoreFoundation.kCFStringEncodingGB_18030_2000
import platform.Foundation.NSString
import platform.Foundation.NSStringEncoding

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
    val src = allocArray<UByteVar>(raw.size)
    for (i in raw.indices) src[i] = raw[i].toUByte()

    var capacity = (raw.size * 6).coerceAtLeast(1024)
    repeat(8) {
      val dst = allocArray<UByteVar>(capacity)
      val written = compression_decode_buffer(
        dst,
        capacity.toULong(),
        src,
        raw.size.toULong(),
        null,
        0u,
        COMPRESSION_ZLIB,
      ).toLong()
      if (written in 1 until capacity.toLong()) {
        return@memScoped ByteArray(written.toInt()) { i -> dst[i].toByte() }
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

private fun decodeWithEncoding(bytes: ByteArray, encoding: NSStringEncoding): String? {
  if (bytes.isEmpty()) return ""
  val nsData = bytes.toNSData()
  return NSString(data = nsData, encoding = encoding) as String?
}
