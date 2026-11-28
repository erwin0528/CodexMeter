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
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

class GeminiRefreshProvider(
    private val client: GeminiUsageClient,
    private val sessionStore: SecureSessionStore,
    private val payloadCipher: PayloadCipher,
    private val clock: Clock = Clock.systemUTC(),
) : RefreshProvider {
    private val json = Json { ignoreUnknownKeys = true }

    // 同一個帳號的刷新排隊進行：後到的那次會讀到前一次剛寫回的新 Cookie，
    // 加上 rotatedAt 冷卻，就不會兩次同時去打 Google 的輪替端點、互相蓋掉結果。
    override suspend fun refresh(
        account: ProviderAccount,
        trigger: RefreshTrigger,
    ): ProviderRefreshResult = lockFor(account).withLock { refreshLocked(account, trigger) }

    private suspend fun refreshLocked(
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
        // 寫回前再讀一次：如果這段時間使用者重新登入過（存檔已經換掉），
        // 這次輪替拿到的是舊帳號狀態，丟掉不寫，免得把新登入的 Cookie 蓋回舊的。
        val current = sessionStore.load(previous.providerId, previous.localAccountId)
        if (!isStillCurrent(previous, current)) return
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

    private fun lockFor(account: ProviderAccount): Mutex =
        accountLocks.getOrPut("${account.providerId.value}/${account.localAccountId.value}") { Mutex() }

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

    internal companion object {
        // 放在 companion：就算 Provider 被建立多次，同一帳號仍共用同一把鎖。
        private val accountLocks = ConcurrentHashMap<String, Mutex>()

        fun isStillCurrent(started: ProviderSessionEnvelope, current: ProviderSessionEnvelope?): Boolean =
            current != null &&
                current.payloadNonce.contentEquals(started.payloadNonce) &&
                current.payloadCiphertext.contentEquals(started.payloadCiphertext)
    }
}
