package dtv.mobile.util

/**
 * For raw viewer counts like "1739892", format to "173.9万" (truncate to 1 decimal).
 * If the server already returns a "万" string, keep it as-is.
 */
fun formatViewerCountWanIfNeeded(raw: String): String {
  val t = raw.trim()
  if (t.isBlank()) return t
  if (t.contains('万')) return t
  if (!t.all { it.isDigit() }) return t

  val value = t.toLongOrNull() ?: return t
  if (value < 10_000L) return t

  val tenths = (value / 1_000L) // wan * 10, truncated
  return "${tenths / 10}.${tenths % 10}万"
}
