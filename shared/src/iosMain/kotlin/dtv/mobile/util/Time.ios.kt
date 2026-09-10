package dtv.mobile.util

import kotlinx.cinterop.ExperimentalForeignApi
import platform.QuartzCore.CACurrentMediaTime
import platform.posix.time

@OptIn(ExperimentalForeignApi::class)
actual fun currentTimeMillis(): Long = time(null) * 1000L

actual fun nanoTime(): Long =
  (CACurrentMediaTime() * 1_000_000_000.0).toLong()
