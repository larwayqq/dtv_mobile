package dtv.mobile.util

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.dataWithBytes
import platform.posix.memcpy

/**
 * Kotlin/Native ships no ByteArray <-> NSData converters in its default
 * Foundation bindings, so the conversion is implemented manually.
 * dataWithBytes:length: copies the bytes, so the source array can be
 * released/freed right after the call.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun ByteArray.toNSData(): NSData = usePinned { pinned ->
  NSData.dataWithBytes(pinned.addressOf(0), size.toULong())
}

@OptIn(ExperimentalForeignApi::class)
internal fun NSData.toByteArray(): ByteArray {
  val length = length.toInt()
  if (length == 0) return ByteArray(0)
  val out = ByteArray(length)
  out.usePinned { pinned ->
    memcpy(pinned.addressOf(0), bytes, this.length)
  }
  return out
}
