package dtv.mobile.util

/** Growable big-endian byte writer, shared by the Tars/protobuf protocol codecs. */
class ByteOut(initialCapacity: Int = 64) {
  private var buffer: ByteArray = ByteArray(initialCapacity)
  var size: Int = 0
    private set

  private fun ensure(extra: Int) {
    val needed = size + extra
    if (needed <= buffer.size) return
    var next = if (buffer.isEmpty()) 64 else buffer.size
    while (next < needed) next = next shl 1
    buffer = buffer.copyOf(next)
  }

  fun writeByte(v: Int): ByteOut {
    ensure(1)
    buffer[size++] = (v and 0xFF).toByte()
    return this
  }

  fun writeShortBe(v: Int): ByteOut {
    ensure(2)
    buffer[size] = ((v ushr 8) and 0xFF).toByte()
    buffer[size + 1] = (v and 0xFF).toByte()
    size += 2
    return this
  }

  fun writeIntBe(v: Int): ByteOut {
    ensure(4)
    buffer[size] = ((v ushr 24) and 0xFF).toByte()
    buffer[size + 1] = ((v ushr 16) and 0xFF).toByte()
    buffer[size + 2] = ((v ushr 8) and 0xFF).toByte()
    buffer[size + 3] = (v and 0xFF).toByte()
    size += 4
    return this
  }

  fun writeLongBe(v: Long): ByteOut {
    ensure(8)
    var shift = 56
    for (i in 0 until 8) {
      buffer[size + i] = ((v ushr shift) and 0xFF).toByte()
      shift -= 8
    }
    size += 8
    return this
  }

  fun write(bytes: ByteArray): ByteOut {
    ensure(bytes.size)
    bytes.copyInto(buffer, size)
    size += bytes.size
    return this
  }

  fun toByteArray(): ByteArray = buffer.copyOf(size)
}
