package dtv.mobile.util

import platform.QuartzCore.CACurrentMediaTime
import platform.posix.time

actual fun currentTimeMillis(): Long = time(null) * 1000L

actual fun nanoTime(): Long =
  (CACurrentMediaTime() * 1_000_000_000.0).toLong()
