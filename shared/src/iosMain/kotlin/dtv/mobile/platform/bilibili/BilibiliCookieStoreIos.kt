package dtv.mobile.platform.bilibili

import platform.Foundation.NSUserDefaults

class BilibiliCookieStoreIos {
  private val prefs = NSUserDefaults(suiteName = "dtv_bilibili")

  fun getCookie(): String? = prefs.stringForKey("cookie")?.takeIf { it.isNotBlank() }

  fun mergeFromCookieHeader(cookieHeader: String) {
    if (cookieHeader.isBlank()) return
    val existing = parseCookieHeader(getCookie().orEmpty())
    val updated = existing.toMutableMap()
    parseCookieHeader(cookieHeader).forEach { (k, v) ->
      if (k.isNotBlank() && v.isNotBlank()) updated[k] = v
    }
    val cookie = updated.entries.joinToString("; ") { (k, v) -> "$k=$v" }
    if (cookie.isNotBlank()) prefs.setObject(cookie, forKey = "cookie")
  }

  fun clear() {
    prefs.removeObjectForKey("cookie")
  }

  fun mergeFromSetCookieHeaders(setCookie: List<String>) {
    if (setCookie.isEmpty()) return
    val existing = parseCookieHeader(getCookie().orEmpty())
    val updated = existing.toMutableMap()
    setCookie.forEach { header ->
      val pair = header.substringBefore(';').trim()
      val idx = pair.indexOf('=')
      if (idx <= 0) return@forEach
      val k = pair.substring(0, idx).trim()
      val v = pair.substring(idx + 1).trim()
      if (k.isNotEmpty() && v.isNotEmpty()) updated[k] = v
    }
    val cookie = updated.entries.joinToString("; ") { (k, v) -> "$k=$v" }
    if (cookie.isNotBlank()) prefs.setObject(cookie, forKey = "cookie")
  }

  private fun parseCookieHeader(header: String): Map<String, String> {
    if (header.isBlank()) return emptyMap()
    return header.split(';')
      .mapNotNull { it.trim() }
      .mapNotNull { kv ->
        val idx = kv.indexOf('=')
        if (idx <= 0) return@mapNotNull null
        kv.substring(0, idx).trim() to kv.substring(idx + 1).trim()
      }.toMap()
  }
}
