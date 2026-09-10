package dtv.mobile.util

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.QuartzCore.CACurrentMediaTime
import platform.posix.time
import platform.posix.time_tVar

@OptIn(ExperimentalForeignApi::class)
actual fun currentTimeMillis(): Long = memScoped {
  val t = alloc<time_tVar>()
  time(t.ptr)
  t.value * 1000L
}

actual fun nanoTime(): Long =
  (CACurrentMediaTime() * 1_000_000_000.0).toLong()
