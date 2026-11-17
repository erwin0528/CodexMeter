package com.kmnexus.codexmeter.providers.gemini.auth

import com.kmnexus.codexmeter.data.secure.PayloadCipher
import com.kmnexus.codexmeter.data.secure.ProviderSessionEnvelope
import com.kmnexus.codexmeter.data.secure.SecureSessionStore
import com.kmnexus.codexmeter.domain.model.ProviderAccount
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshot
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshotSource
import com.kmnexus.codexmeter.providers.SessionImporter
import com.kmnexus.codexmeter.providers.gemini.mapper.GeminiUsageMapper
import com.kmnexus.codexmeter.providers.gemini.network.GeminiUsageClient
import com.kmnexus.codexmeter.providers.gemini.session.GeminiSessionPayload
import java.time.Clock
import kotlinx.serialization.json.Json

/**
 * WebView 登入後交進來的是完整 Cookie header（見 NavHost 的 Gemini 設定）。
 * 先實際查一次額度確認登入有效，成功才加密保存，失敗就不留任何東西。
 */
class GeminiSessionImporter(
    private val usageClient: GeminiUsageClient,
    private val sessionStore: SecureSessionStore,
    private val payloadCipher: PayloadCipher,
    private val clock: Clock = Clock.systemUTC(),
) : SessionImporter {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun importFromCookie(
        cookieJson: String,
        account: ProviderAccount,
    ): Result<QuotaSnapshot> {
        val now = clock.instant()
        // 剛登入的 Cookie 是新的，標記為剛輪替過，避免登入當下多打一次 RotateCookies。
        val fresh = GeminiSessionPayload(cookieHeader = cookieJson, rotatedAt = now.toString())
        return when (val result = usageClient.fetchUsage(fresh)) {
            is GeminiUsageClient.Result.Failure ->
                Result.failure(RuntimeException("Gemini usage fetch failed: ${result.error.safeMessageKey}"))
            is GeminiUsageClient.Result.Success -> {
                val encrypted = payloadCipher.encrypt(
                    json.encodeToString(GeminiSessionPayload.serializer(), result.session).encodeToByteArray(),
                )
                sessionStore.save(
                    ProviderSessionEnvelope(
                        providerId = account.providerId.value,
                        localAccountId = account.localAccountId.value,
                        providerAccountId = account.providerAccountId?.value,
                        schemaVersion = 1,
                        payloadCiphertext = encrypted.ciphertext,
                        payloadNonce = encrypted.nonce,
                        createdAt = now.toString(),
                        updatedAt = now.toString(),
                    ),
                )
                Result.success(
                    GeminiUsageMapper.map(
                        usage = result.usage,
                        localAccountId = account.localAccountId,
                        providerAccountId = account.providerAccountId,
                        fetchedAt = now,
                        source = QuotaSnapshotSource.CookieAuth,
                    ),
                )
            }
        }
    }

    override suspend fun importFromApiKey(
        apiKey: String,
        account: ProviderAccount,
        apiBaseUrl: String?,
    ): Result<QuotaSnapshot> =
        Result.failure(UnsupportedOperationException("Gemini does not support API key auth"))

    override suspend fun importFromOAuthPkce(
        code: String,
        verifier: String,
        redirectUri: String,
        account: ProviderAccount,
    ): Result<QuotaSnapshot> =
        Result.failure(UnsupportedOperationException("Gemini does not support OAuth PKCE auth"))
}
