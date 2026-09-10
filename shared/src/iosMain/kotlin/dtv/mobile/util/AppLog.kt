package dtv.mobile.util

import platform.Foundation.NSLog

object AppLog {
  fun d(tag: String, message: String) {
    NSLog("[D] %@: %@", tag, message)
  }

  fun i(tag: String, message: String) {
    NSLog("[I] %@: %@", tag, message)
  }

  fun w(tag: String, message: String, t: Throwable? = null) {
    NSLog("[W] %@: %@ %@", tag, message, t?.message ?: "")
  }

  fun e(tag: String, message: String, t: Throwable? = null) {
    NSLog("[E] %@: %@ %@", tag, message, t?.toString() ?: "")
  }
}
