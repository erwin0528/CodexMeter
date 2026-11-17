package com.kmnexus.codexmeter.providers.gemini

import com.kmnexus.codexmeter.data.secure.PayloadCipher
import com.kmnexus.codexmeter.data.secure.ProviderSessionEnvelope
import com.kmnexus.codexmeter.data.secure.SecureSessionStore
import com.kmnexus.codexmeter.domain.model.ProviderAccount
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshotSource
import com.kmnexus.codexmeter.domain.refresh.QuotaError
import com.kmnexus.codexmeter.domain.refresh.RefreshTrigger
import com.kmnexus.codexmeter.providers.gemini.mapper.GeminiUsageMapper
import com.kmnexus.codexmeter.providers.gemini.network.GeminiUsageClient
import com.kmnexus.codexmeter.providers.gemini.session.GeminiSessionPayload
import com.kmnexus.codexmeter.refresh.ProviderRefreshResult
import com.kmnexus.codexmeter.refresh.RefreshProvider
import java.time.Clock
import kotlinx.serialization.json.Json

class GeminiRefreshProvider(
    private val client: GeminiUsageClient,
    private val sessionStore: SecureSessionStore,
    private val payloadCipher: PayloadCipher,
    private val clock: Clock = Clock.systemUTC(),
) : RefreshProvider {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun refresh(
        account: ProviderAccount,
        trigger: RefreshTrigger,
    ): ProviderRefreshResult {
        val envelope = sessionStore.load(account.providerId.value, account.localAccountId.value)
            ?: return authRequired("gemini_session_missing")
        val session = try {
            json.decodeFromString<GeminiSessionPayload>(
                payloadCipher.decrypt(envelope.payloadCiphertext, envelope.payloadNonce).decodeToString(),
            )
        } catch (_: Exception) {
            return authRequired("gemini_session_decode_failed")
        }

        return when (val result = client.fetchUsage(session)) {
            is GeminiUsageClient.Result.Failure -> ProviderRefreshResult.Failure(result.error)
            is GeminiUsageClient.Result.Success -> {
                // Cookie 輪替後要馬上寫回，否則下次會拿舊憑證去查（RULES §13：憑證更新後立即保存）。
                if (result.session != session) persist(envelope, result.session)
                ProviderRefreshResult.Success(
                    GeminiUsageMapper.map(
                        usage = result.usage,
                        localAccountId = account.localAccountId,
                        providerAccountId = account.providerAccountId,
                        fetchedAt = clock.instant(),
                        source = trigger.toSnapshotSource(),
                    ),
                )
            }
        }
    }

    private suspend fun persist(previous: ProviderSessionEnvelope, session: GeminiSessionPayload) {
        val encrypted = payloadCipher.encrypt(
            json.encodeToString(GeminiSessionPayload.serializer(), session).encodeToByteArray(),
        )
        sessionStore.save(
            previous.copy(
                payloadCiphertext = encrypted.ciphertext,
                payloadNonce = encrypted.nonce,
                updatedAt = clock.instant().toString(),
            ),
        )
    }

    private fun authRequired(digest: String): ProviderRefreshResult =
        ProviderRefreshResult.Failure(QuotaError.AuthRequired(httpStatus = null, diagnosticsDigest = digest))

    private fun RefreshTrigger.toSnapshotSource(): QuotaSnapshotSource = when (this) {
        RefreshTrigger.AppOpen -> QuotaSnapshotSource.AppOpenRefresh
        RefreshTrigger.Manual -> QuotaSnapshotSource.ManualRefresh
        RefreshTrigger.Widget -> QuotaSnapshotSource.WidgetRefresh
        RefreshTrigger.ImportValidation -> QuotaSnapshotSource.CookieAuth
        RefreshTrigger.AccountSwitch -> QuotaSnapshotSource.ManualRefresh
        RefreshTrigger.Periodic -> QuotaSnapshotSource.BackgroundRefresh
    }
}
