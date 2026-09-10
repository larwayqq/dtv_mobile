package dtv.mobile.util

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater

actual fun inflateZlibOrNull(data: ByteArray): ByteArray? = runCatching {
  val inflater = Inflater()
  inflater.setInput(data)
  val out = ByteArrayOutputStream()
  val buf = ByteArray(8 * 1024)
  while (!inflater.finished() && !inflater.needsInput()) {
    val n = inflater.inflate(buf)
    if (n <= 0) break
    out.write(buf, 0, n)
  }
  inflater.end()
  out.toByteArray()
}.getOrNull()

actual fun gunzipOrNull(data: ByteArray): ByteArray? = runCatching {
  GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
}.getOrNull()

actual fun decodeTextBestEffort(bytes: ByteArray): String {
  if (bytes.isEmpty()) return ""
  val utf8 = bytes.toString(Charsets.UTF_8)
  if (!utf8.contains('�')) return utf8
  val gbk = runCatching { bytes.toString(Charset.forName("GBK")) }.getOrNull() ?: return utf8
  val utf8Bad = utf8.count { it == '�' }
  val gbkBad = gbk.count { it == '�' }
  return if (gbkBad < utf8Bad) gbk else utf8
}
