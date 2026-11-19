package com.kmnexus.codexmeter.widget.overview

import com.kmnexus.codexmeter.widget.WidgetField
import com.kmnexus.codexmeter.widget.WidgetQuotaState
import com.kmnexus.codexmeter.widget.WidgetQuotaStatus
import com.kmnexus.codexmeter.widget.WidgetQuotaTone
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 總覽小工具的一列＝一個帳號。存進 Glance 的 Preferences（JSON），
 * 小工具重繪時只讀這份快照，不在畫面執行緒查資料庫。
 * 圖示不存資源 id（每次 build 可能不同），渲染時再用 providerId 查。
 */
@Serializable
data class OverviewRow(
    val providerId: String,
    val accountName: String,
    val status: WidgetQuotaStatus,
    val tone: WidgetQuotaTone,
    /** 主要額度（provider 排序的第一個可顯示視窗），大字顯示＋進度條。 */
    val main: OverviewField?,
    /** 第二個額度（例如每週），以小字附在副標。 */
    val secondary: OverviewField?,
    val localAccountId: String? = null,
    val updatedAtEpochMillis: Long? = null,
)

@Serializable
data class OverviewField(
    val windowId: String,
    val isBalance: Boolean,
    val percent: Int?,
    val balanceAmount: String?,
    val balanceCurrency: String?,
    val resetAtEpochMillis: Long?,
    val limitWindowSeconds: Int?,
    val tone: WidgetQuotaTone,
) {
    /** 進度條比例；餘額型或缺值不畫進度條。 */
    val progressFraction: Float?
        get() = if (isBalance) null else percent?.coerceIn(0, 100)?.div(100f)
}

/** 已選帳號的狀態轉成一列；未設定（沒有帳號）的狀態不顯示。 */
fun WidgetQuotaState.toOverviewRow(): OverviewRow? {
    if (isUnconfigured) return null
    return OverviewRow(
        providerId = providerId ?: return null,
        accountName = accountName ?: providerName,
        status = status,
        tone = tone,
        main = fields.getOrNull(0)?.toOverviewField(),
        secondary = fields.getOrNull(1)?.toOverviewField(),
        localAccountId = localAccountId,
        updatedAtEpochMillis = updatedAt?.toEpochMilli(),
    )
}

private fun WidgetField.toOverviewField() = OverviewField(
    windowId = windowId,
    isBalance = isBalance,
    percent = percent,
    balanceAmount = balanceAmount,
    balanceCurrency = balanceCurrency,
    resetAtEpochMillis = resetAt?.toEpochMilli(),
    limitWindowSeconds = limitWindowSeconds,
    tone = tone,
)

object OverviewRowsCodec {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(OverviewRow.serializer())

    fun encode(rows: List<OverviewRow>): String = json.encodeToString(serializer, rows)

    /** 解不開（例如升級後格式變了）就當作還沒有資料，等下一次刷新重寫。 */
    fun decode(raw: String?): List<OverviewRow>? =
        raw?.let {
            try {
                json.decodeFromString(serializer, it)
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }
}

/** 版面尺寸計算（dp），抽出來方便單元測試。 */
object OverviewLayout {
    const val PADDING_DP = 10
    const val HEADER_HEIGHT_DP = 46
    const val RING_DP = 64
    const val ROW_GAP_DP = 6

    /** 三行字的高度（13＋20＋12sp 含行距）；實機量約 66dp，會隨系統字體放大。 */
    private const val TEXT_BLOCK_DP = 66

    /** 「更新中…」最多顯示這麼久；網路斷掉等情況下不會永遠卡著。 */
    private const val REFRESHING_TIMEOUT_MILLIS = 3 * 60 * 1000L

    /** 依高度決定能放幾列，至少一列。列高＝圓環和三行字取高者＋間距；只有字會跟著系統字體變大。 */
    fun rowCapacity(heightDp: Float, fontScale: Float = 1f): Int {
        val rowHeight = maxOf(RING_DP.toFloat(), TEXT_BLOCK_DP * fontScale.coerceAtLeast(1f)) + ROW_GAP_DP
        return ((heightDp - PADDING_DP * 2 - HEADER_HEIGHT_DP) / rowHeight).toInt().coerceAtLeast(1)
    }

    fun isRefreshing(sinceEpochMillis: Long?, now: java.time.Instant): Boolean =
        sinceEpochMillis != null && now.toEpochMilli() - sinceEpochMillis in 0 until REFRESHING_TIMEOUT_MILLIS
}

/**
 * 重置時間用「絕對時間」：小工具最快 15 分鐘才重繪一次，倒數（例如「2 小時後」）會過期誤導。
 * 今天內只顯示時間，跨日才帶日期。
 */
fun isSameLocalDay(instant: Instant, now: Instant, zone: ZoneId): Boolean =
    instant.atZone(zone).toLocalDate() == now.atZone(zone).toLocalDate()

/** 需要使用者處理的狀態，在列的右側用文字取代百分比。 */
val OverviewRow.needsAttention: Boolean
    get() = status == WidgetQuotaStatus.AuthRequired
