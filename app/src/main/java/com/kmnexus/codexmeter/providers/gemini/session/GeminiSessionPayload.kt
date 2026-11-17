package com.kmnexus.codexmeter.providers.gemini.session

import kotlinx.serialization.Serializable

/**
 * 加密保存的 Gemini 登入狀態。
 * [cookieHeader] 是登入當下 gemini.google.com 會帶的完整 Cookie header（含 .google.com 的 session cookie），
 * 之後隨 Set-Cookie 輪替更新；[rotatedAt] 記錄上次輪替時間（ISO-8601），用來控制輪替頻率。
 */
@Serializable
data class GeminiSessionPayload(
    val cookieHeader: String,
    val rotatedAt: String? = null,
)
