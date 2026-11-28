package com.kmnexus.codexmeter.providers.gemini.network

import com.kmnexus.codexmeter.core.network.ProviderHttpClient
import com.kmnexus.codexmeter.domain.refresh.QuotaError
import com.kmnexus.codexmeter.providers.gemini.dto.GeminiUsageDecoder
import com.kmnexus.codexmeter.providers.gemini.dto.GeminiUsageInfoDto
import com.kmnexus.codexmeter.providers.gemini.session.GeminiCookieHeader
import com.kmnexus.codexmeter.providers.gemini.session.GeminiSessionPayload
import java.io.IOException
import java.net.URLEncoder
import java.time.Clock
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant

/**
 * 讀取 gemini.google.com/usage 頁面背後的額度查詢（唯讀，不送任何對話請求）。
 *
 * 端點與參數來自 CodexBar `gemini-web` 外掛（MIT）：Cookie 驗證的 GET GraphQL persisted query，
 * 只需要 Cookie、Accept、Referer。這是非公開介面，Google 改版時會回 Invalid 而不是亂猜。
 *
 * Cookie 輪替：瀏覽器分頁會定期呼叫 accounts.google.com/RotateCookies 換新 `__Secure-1PSIDTS`。
 * 我們沒有常駐的瀏覽器，所以在刷新時代替它做這件事；輪替失敗不擋查詢，讓查詢結果決定是否要重新登入。
 */
class GeminiUsageClient(
    /** 必須用 [noRedirectHttpClient]：OkHttp 跨網域轉址時不會拿掉自訂的 Cookie header。 */
    private val httpClient: ProviderHttpClient,
    private val clock: Clock = Clock.systemUTC(),
    private val geminiBaseUrl: String = "https://gemini.google.com",
    private val accountsBaseUrl: String = "https://accounts.google.com",
) {
    sealed interface Result {
        /** [session] 是合併過 Set-Cookie 的最新狀態，呼叫端要寫回加密儲存。 */
        data class Success(val usage: GeminiUsageInfoDto, val session: GeminiSessionPayload) : Result
        data class Failure(val error: QuotaError) : Result
    }

    suspend fun fetchUsage(session: GeminiSessionPayload): Result {
        val current = if (isRotationDue(session)) rotate(session) else session
        val response = try {
            httpClient.get(
                url = usageUrl(),
                headers = mapOf(
                    "Cookie" to current.cookieHeader,
                    "Accept" to "application/json",
                    "Referer" to "${geminiBaseUrl.trimEnd('/')}/usage",
                ),
            )
        } catch (_: IOException) {
            return Result.Failure(QuotaError.Network(diagnosticsDigest = "gemini_network_error"))
        }
        val updated = current.copy(
            cookieHeader = GeminiCookieHeader.merge(current.cookieHeader, response.setCookieHeaders),
        )

        return when (response.statusCode) {
            in 200..299 -> decode(response.body, updated)
            // 登出時 Google 會轉址到登入頁；我們不跟隨轉址，直接當成需要重新登入。
            in 300..399, 401, 403 -> authRequired(response.statusCode)
            429 -> Result.Failure(QuotaError.Network(diagnosticsDigest = "gemini_rate_limited"))
            else -> Result.Failure(QuotaError.Network(diagnosticsDigest = "gemini_http_${response.statusCode}"))
        }
    }

    private fun decode(body: String, session: GeminiSessionPayload): Result =
        when (val decoded = GeminiUsageDecoder.decode(body)) {
            is GeminiUsageDecoder.Decoded.Usage -> Result.Success(decoded.usage, session)
            GeminiUsageDecoder.Decoded.SignedOut -> authRequired(httpStatus = null)
            GeminiUsageDecoder.Decoded.Invalid ->
                Result.Failure(QuotaError.Network(diagnosticsDigest = "gemini_response_changed"))
        }

    private suspend fun rotate(session: GeminiSessionPayload): GeminiSessionPayload {
        val response = try {
            httpClient.postJson(
                url = "${accountsBaseUrl.trimEnd('/')}/RotateCookies",
                // 瀏覽器送出的固定佔位內容；伺服器只看 Cookie。
                jsonBody = ROTATE_BODY,
                headers = mapOf(
                    "Cookie" to session.cookieHeader,
                    "Origin" to "https://accounts.google.com",
                ),
            )
        } catch (_: IOException) {
            return session
        }
        val merged = GeminiCookieHeader.merge(session.cookieHeader, response.setCookieHeaders)
        val rotatedAt = if (response.statusCode in 200..299) clock.instant().toString() else session.rotatedAt
        return session.copy(cookieHeader = merged, rotatedAt = rotatedAt)
    }

    private fun isRotationDue(session: GeminiSessionPayload): Boolean {
        val last = session.rotatedAt?.let(::parseInstant) ?: return true
        return Duration.between(last, clock.instant()) >= ROTATION_INTERVAL
    }

    private fun usageUrl(): String {
        // 用字串版的 "UTF-8"：Charset 版的 encode 要 Android 13（API 33）才有，Android 12 會直接當掉
        val signature = URLEncoder.encode(QUERY_SIGNATURE, "UTF-8")
        val variables = URLEncoder.encode(QUERY_VARIABLES, "UTF-8")
        return "${geminiBaseUrl.trimEnd('/')}$USAGE_PATH?query_signature=$signature&variables=$variables"
    }

    private fun authRequired(httpStatus: Int?): Result =
        Result.Failure(
            QuotaError.AuthRequired(
                httpStatus = httpStatus,
                diagnosticsDigest = "gemini_auth_required_${httpStatus ?: "signed_out"}",
            ),
        )

    private fun parseInstant(raw: String): Instant? =
        try {
            Instant.parse(raw)
        } catch (_: DateTimeException) {
            null
        }

    companion object {
        /**
         * 不跟隨轉址的 HTTP client。整組 Google Cookie 等同帳號鑰匙，
         * 只允許送到我們指定的 gemini.google.com / accounts.google.com，絕不能被轉址帶去別的網域。
         */
        fun noRedirectHttpClient(): ProviderHttpClient =
            ProviderHttpClient(
                okhttp3.OkHttpClient.Builder()
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .build(),
            )

        private const val USAGE_PATH = "/_/BardChatUi/graphql/schemas/GEMINI_WEB_GRAPHQL/executeQuery"

        // UsagePageQuery 的 persisted query 簽章與變數（CodexBar docs/gemini.md，2026-11 版）。
        private const val QUERY_SIGNATURE = "2/dWbCvkVB0X16r9NPnxifeOAAqdCqMf0xeWvau9yPy7g="
        private const val QUERY_VARIABLES =
            """{"g1Request":{"entrypoints":["GEMINI_USAGE_SETTINGS_UPGRADE_BANNER"]},""" +
                """"workspaceRequest":{"recommendationsForSurfaceRequests":[]}}"""

        private const val ROTATE_BODY = """[000,"-0000000000000000000"]"""

        // 比背景刷新（15 分鐘）略短，讓每次排程刷新都會順手輪替一次，接近瀏覽器分頁的頻率。
        private val ROTATION_INTERVAL: Duration = Duration.ofMinutes(10)
    }
}
