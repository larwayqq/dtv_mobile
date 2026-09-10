package dtv.mobile.util

import platform.Foundation.NSDate
import platform.QuartzCore.CACurrentMediaTime

actual fun currentTimeMillis(): Long =
  (NSDate().timeIntervalSince1970 * 1000.0).toLong()

actual fun nanoTime(): Long =
  (CACurrentMediaTime() * 1_000_000_000.0).toLong()
