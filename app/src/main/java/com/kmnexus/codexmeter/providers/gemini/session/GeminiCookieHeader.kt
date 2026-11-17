package com.kmnexus.codexmeter.providers.gemini.session

/**
 * 管理存起來的 Google Cookie header。
 *
 * Google 會不定期用 Set-Cookie 換掉短效憑證（例如 `__Secure-1PSIDTS`）；
 * 如果不寫回本機，舊值過期後就會被當成登出，所以每次回應都要合併。
 * 這裡只處理 name=value，屬性（Domain、Path、Secure…）一律忽略，因為我們只對固定網域發請求。
 */
object GeminiCookieHeader {
    /** 登入後才會出現的長效 session cookie，用來判斷 WebView 是否已真正登入。 */
    const val SIGNED_IN_COOKIE = "__Secure-1PSID"

    fun hasSignedInSession(header: String): Boolean =
        parse(header)[SIGNED_IN_COOKIE].isNullOrEmpty().not()

    fun merge(header: String, setCookieHeaders: List<String>): String {
        if (setCookieHeaders.isEmpty()) return header
        val cookies = parse(header)
        setCookieHeaders.forEach { setCookie ->
            val parts = setCookie.split(";").map { it.trim() }
            val (name, value) = splitPair(parts.firstOrNull().orEmpty()) ?: return@forEach
            val expired = parts.drop(1).any { it.equals("Max-Age=0", ignoreCase = true) }
            if (value.isEmpty() || expired) cookies.remove(name) else cookies[name] = value
        }
        return cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun parse(header: String): LinkedHashMap<String, String> {
        val cookies = LinkedHashMap<String, String>()
        header.split(";").forEach { part ->
            splitPair(part.trim())?.let { (name, value) -> cookies[name] = value }
        }
        return cookies
    }

    private fun splitPair(pair: String): Pair<String, String>? {
        val index = pair.indexOf('=')
        if (index <= 0) return null
        return pair.substring(0, index).trim() to pair.substring(index + 1).trim()
    }
}
