package dtv.mobile.util

/**
 * Pure-Kotlin MD5 (RFC 1321), shared by all platforms.
 */
object Md5 {
  private val S = intArrayOf(
    7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
    5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
    4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
    6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
  )

  private val K = intArrayOf(
    0xd76aa478.toInt(), 0xe8c7b756.toInt(), 0x242070db.toInt(), 0xc1bdceee.toInt(),
    0xf57c0faf.toInt(), 0x4787c62a.toInt(), 0xa8304613.toInt(), 0xfd469501.toInt(),
    0x698098d8.toInt(), 0x8b44f7af.toInt(), 0xffff5bb1.toInt(), 0x895cd7be.toInt(),
    0x6b901122.toInt(), 0xfd987193.toInt(), 0xa679438e.toInt(), 0x49b40821.toInt(),
    0xf61e2562.toInt(), 0xc040b340.toInt(), 0x265e5a51.toInt(), 0xe9b6c7aa.toInt(),
    0xd62f105d.toInt(), 0x02441453, 0xd8a1e681.toInt(), 0xe7d3fbc8.toInt(),
    0x21e1cde6.toInt(), 0xc33707d6.toInt(), 0xf4d50d87.toInt(), 0x455a14ed.toInt(),
    0xa9e3e905.toInt(), 0xfcefa3f8.toInt(), 0x676f02d9.toInt(), 0x8d2a4c8a.toInt(),
    0xfffa3942.toInt(), 0x8771f681.toInt(), 0x6d9d6122.toInt(), 0xfde5380c.toInt(),
    0xa4beea44.toInt(), 0x4bdecfa9.toInt(), 0xf6bb4b60.toInt(), 0xbebfbc70.toInt(),
    0x289b7ec6.toInt(), 0xeaa127fa.toInt(), 0xd4ef3085.toInt(), 0x04881d05,
    0xd9d4d039.toInt(), 0xe6db99e5.toInt(), 0x1fa27cf8.toInt(), 0xc4ac5665.toInt(),
    0xf4292244.toInt(), 0x432aff97.toInt(), 0xab9423a7.toInt(), 0xfc93a039.toInt(),
    0x655b59c3.toInt(), 0x8f0ccc92.toInt(), 0xffeff47d.toInt(), 0x85845dd1.toInt(),
    0x6fa87e4f, 0xfe2ce6e0.toInt(), 0xa3014314.toInt(), 0x4e0811a1.toInt(),
    0xf7537e82.toInt(), 0xbd3af235.toInt(), 0x2ad7d2bb.toInt(), 0xeb86d391.toInt(),
  )

  private fun ByteArray.getIntLe(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or
      ((this[offset + 1].toInt() and 0xFF) shl 8) or
      ((this[offset + 2].toInt() and 0xFF) shl 16) or
      ((this[offset + 3].toInt() and 0xFF) shl 24)

  private fun leftRotate(x: Int, c: Int): Int = (x shl c) or (x ushr (32 - c))

  fun digest(input: ByteArray): ByteArray {
    val bitLen = input.size.toLong() * 8L
    val paddedSize = (input.size + 9 + 63) and 63.inv()
    val msg = input.copyOf(paddedSize)
    msg[input.size] = 0x80.toByte()
    for (i in 0 until 8) {
      msg[paddedSize - 8 + i] = ((bitLen ushr (i * 8)) and 0xFFL).toByte()
    }

    var a0 = 0x67452301
    var b0 = -0x10325477
    var c0 = -0x67452302
    var d0 = 0x10325476

    val x = IntArray(16)
    var off = 0
    while (off < msg.size) {
      for (i in 0 until 16) x[i] = msg.getIntLe(off + i * 4)

      var a = a0
      var b = b0
      var c = c0
      var d = d0

      for (i in 0 until 64) {
        val f: Int
        val g: Int
        when {
          i < 16 -> {
            f = (b and c) or (b.inv() and d)
            g = i
          }
          i < 32 -> {
            f = (d and b) or (d.inv() and c)
            g = (5 * i + 1) % 16
          }
          i < 48 -> {
            f = b xor c xor d
            g = (3 * i + 5) % 16
          }
          else -> {
            f = c xor (b or d.inv())
            g = (7 * i) % 16
          }
        }
        val next = d
        d = c
        c = b
        b = b + leftRotate(a + f + K[i] + x[g], S[i])
        a = next
      }

      a0 += a
      b0 += b
      c0 += c
      d0 += d
      off += 64
    }

    val out = ByteArray(16)
    val states = intArrayOf(a0, b0, c0, d0)
    for (s in 0 until 4) {
      val v = states[s]
      for (j in 0 until 4) out[s * 4 + j] = ((v ushr (j * 8)) and 0xFF).toByte()
    }
    return out
  }
}

fun md5Hex(input: String): String = md5Hex(input.encodeToByteArray())

fun md5Hex(input: ByteArray): String {
  val bytes = Md5.digest(input)
  val sb = StringBuilder(bytes.size * 2)
  for (b in bytes) {
    val v = b.toInt() and 0xFF
    sb.append("0123456789abcdef"[v ushr 4])
    sb.append("0123456789abcdef"[v and 0x0F])
  }
  return sb.toString()
}
