package dtv.mobile.sync

import platform.Foundation.NSDate
import platform.Foundation.NSISO8601DateFormatter

actual fun currentIsoTimestamp(): String =
  NSISO8601DateFormatter().stringFromDate(NSDate())
