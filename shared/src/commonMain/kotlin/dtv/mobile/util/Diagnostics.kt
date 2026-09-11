package dtv.mobile.util

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

data class DiagnosticError(
  val timeMs: Long,
  val where: String,
  val message: String,
)

/**
 * Process-wide diagnostics surfaced directly in the UI (the sideloaded build
 * has no console access). Platform crash hooks write [lastCrashReport];
 * repository layers record swallowed failures into [recentErrors].
 */
object Diagnostics {
  var lastCrashReport by mutableStateOf<String?>(null)

  val recentErrors = mutableStateListOf<DiagnosticError>()

  fun record(where: String, t: Throwable) {
    val msg = t.message?.takeIf { it.isNotBlank() } ?: t::class.simpleName.orEmpty()
    recentErrors.add(DiagnosticError(currentTimeMillisSafe(), where, msg))
    while (recentErrors.size > 30) recentErrors.removeAt(0)
  }

  fun record(where: String, message: String) {
    recentErrors.add(DiagnosticError(currentTimeMillisSafe(), where, message))
    while (recentErrors.size > 30) recentErrors.removeAt(0)
  }

  fun clearErrors() {
    recentErrors.clear()
  }

  fun setCrashReport(text: String) {
    lastCrashReport = text
  }

  fun clearCrash() {
    lastCrashReport = null
  }
}

private fun currentTimeMillisSafe(): Long =
  runCatching { currentTimeMillis() }.getOrDefault(0L)
