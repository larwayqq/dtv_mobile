package dtv.mobile.util

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.staticCFunction
import kotlin.experimental.ExperimentalNativeApi
import platform.Foundation.NSException
import platform.Foundation.NSSetUncaughtExceptionHandler
import platform.Foundation.NSUserDefaults
import platform.posix.SIGABRT
import platform.posix.SIGBUS
import platform.posix.SIGILL
import platform.posix.SIGSEGV
import platform.posix.SIGTRAP
import platform.posix.signal
import kotlin.native.setUnhandledExceptionHook
import kotlin.native.stackToString

private const val KEY_KOTLIN = "dtv_crash_kotlin"
private const val KEY_OBJC = "dtv_crash_objc"
private const val KEY_SIGNAL = "dtv_crash_signal"

@OptIn(ExperimentalForeignApi::class)
private fun objcExceptionHandler(ex: NSException?) {
  val defaults = NSUserDefaults.standardUserDefaults
  val symbols = ex?.callStackSymbols?.joinToString("\n") ?: "(no stack symbols)"
  val text = "${ex?.name ?: "NSException"}: ${ex?.reason ?: "unknown reason"}\n$symbols"
  defaults.setObject(text, forKey = KEY_OBJC)
  defaults.synchronize()
}

@OptIn(ExperimentalForeignApi::class)
private fun signalHandler(sig: Int) {
  // Best effort only: signal handlers allow only async-signal-safe calls.
  val defaults = NSUserDefaults.standardUserDefaults
  defaults.setObject("process killed by signal $sig", forKey = KEY_SIGNAL)
  defaults.synchronize()
}

@OptIn(ExperimentalNativeApi::class, ExperimentalForeignApi::class)
fun installCrashReporting() {
  val defaults = NSUserDefaults.standardUserDefaults

  // Surface the previous crash, then clear stored reports.
  val previous = buildList {
    defaults.stringForKey(KEY_KOTLIN)?.let {
      add("[Kotlin 未捕获异常]\n$it")
      defaults.removeObjectForKey(KEY_KOTLIN)
    }
    defaults.stringForKey(KEY_OBJC)?.let {
      add("[iOS 原生异常]\n$it")
      defaults.removeObjectForKey(KEY_OBJC)
    }
    defaults.stringForKey(KEY_SIGNAL)?.let {
      add("[系统信号]\n$it")
      defaults.removeObjectForKey(KEY_SIGNAL)
    }
  }
  if (previous.isNotEmpty()) {
    Diagnostics.setCrashReport(previous.joinToString("\n\n"))
  }

  setUnhandledExceptionHook { throwable ->
    defaults.setObject(
      "${throwable::class.simpleName}: ${throwable.message}\n${throwable.stackToString()}",
      forKey = KEY_KOTLIN,
    )
    defaults.synchronize()
  }

  NSSetUncaughtExceptionHandler(staticCFunction { ex -> objcExceptionHandler(ex) })

  listOf(SIGABRT, SIGSEGV, SIGBUS, SIGILL, SIGTRAP).forEach { sig ->
    signal(sig, staticCFunction { n -> signalHandler(n) })
  }
}
