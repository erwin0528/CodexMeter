package com.kmnexus.codexmeter.refresh

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kmnexus.codexmeter.domain.model.ProviderAccount
import com.kmnexus.codexmeter.domain.refresh.RefreshTrigger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

fun interface ExchangeRateRefresher {
    suspend fun refreshExchangeRates()
}

class QuotaRefreshWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        return when (QuotaRefreshWorkerOutcome.fromDependencies(applicationContext as? QuotaRefreshDependenciesProvider)) {
            QuotaRefreshWorkerOutcome.Success -> Result.success()
            QuotaRefreshWorkerOutcome.Retry -> Result.retry()
            QuotaRefreshWorkerOutcome.Failure -> Result.failure()
        }
    }
}

interface QuotaRefreshDependenciesProvider {
    val refreshCoordinator: RefreshCoordinator

    val exchangeRateRefresher: ExchangeRateRefresher
        get() = ExchangeRateRefresher { }

    suspend fun activeQuotaRefreshAccounts(): List<ProviderAccount>
}

enum class QuotaRefreshWorkerOutcome {
    Success,
    Retry,
    Failure;

    companion object {
        suspend fun fromDependencies(provider: QuotaRefreshDependenciesProvider?): QuotaRefreshWorkerOutcome {
            val dependencies = provider ?: return Failure
            val results = MultiAccountRefreshRunner(
                refreshCoordinator = dependencies.refreshCoordinator,
                exchangeRateRefresher = dependencies.exchangeRateRefresher,
            ).refresh(
                accounts = dependencies.activeQuotaRefreshAccounts(),
                trigger = RefreshTrigger.Periodic,
            )
            return when {
                results.any { it.isRetryableFailure() } -> Retry
                results.any { it is RefreshResult.Failure } -> Failure
                else -> Success
            }
        }
    }
}

class MultiAccountRefreshRunner(
    private val refreshCoordinator: RefreshCoordinator,
    private val parallelism: Int = DEFAULT_PARALLELISM,
    private val exchangeRateRefresher: ExchangeRateRefresher = ExchangeRateRefresher { },
    private val sameProviderSpacingMillis: Long = DEFAULT_SAME_PROVIDER_SPACING_MILLIS,
    private val staggeredProviderIds: Set<String> = DEFAULT_STAGGERED_PROVIDER_IDS,
) {
    init {
        require(parallelism > 0) { "parallelism must be positive" }
    }

    suspend fun refresh(
        accounts: List<ProviderAccount>,
        trigger: RefreshTrigger,
    ): List<RefreshResult> = coroutineScope {
        runCatching { exchangeRateRefresher.refreshExchangeRates() }
        val semaphore = Semaphore(parallelism)
        // The caller decides which accounts are eligible: activeAccounts for background refresh, or
        // manuallyRefreshableAccounts for a manual retry that may include NeedsReauth. The runner
        // refreshes exactly what it is handed and does not re-filter by status.
        // 指定 provider（目前只有走網頁 Cookie 的 Gemini）的第 N 個帳號延後 N × 間隔才送出，
        // 兩個 Google 帳號就不會同一瞬間打同一個非公開端點。其他 provider 維持原本的並發行為。
        // 延遲放在 semaphore 外面，等待時不佔並發名額。
        val seenPerProvider = mutableMapOf<String, Int>()
        accounts
            .map { account ->
                val providerId = account.providerId.value
                val providerIndex = if (providerId in staggeredProviderIds) {
                    seenPerProvider.merge(providerId, 1, Int::plus)!! - 1
                } else {
                    0
                }
                async {
                    if (providerIndex > 0) delay(providerIndex * sameProviderSpacingMillis)
                    semaphore.withPermit {
                        refreshCoordinator.refresh(account = account, trigger = trigger)
                    }
                }
            }
            .awaitAll()
    }

    companion object {
        // Refresh up to 4 accounts at once (bounded concurrency) so a multi-provider setup completes
        // a periodic cycle quickly without hammering every provider endpoint simultaneously.
        const val DEFAULT_PARALLELISM = 4

        // 5 秒對使用者無感，但足以讓同家帳號的請求看起來不像同時發出的腳本。
        const val DEFAULT_SAME_PROVIDER_SPACING_MILLIS = 5_000L
        val DEFAULT_STAGGERED_PROVIDER_IDS: Set<String> = setOf("gemini")
    }
}

private fun RefreshResult.isRetryableFailure(): Boolean =
    this is RefreshResult.Failure && error.retryable
