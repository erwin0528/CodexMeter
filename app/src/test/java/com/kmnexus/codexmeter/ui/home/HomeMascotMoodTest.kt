package com.kmnexus.codexmeter.ui.home

import com.kmnexus.codexmeter.R
import com.kmnexus.codexmeter.domain.quota.QuotaWindowDisplayKind
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeMascotMoodTest {
    private val now = Instant.parse("2026-11-27T12:00:00Z")
    private val fiveHours = 5 * 3600

    private fun card(
        remainingPercent: Int?,
        resetIn: Duration?,
        windowSeconds: Int? = fiveHours,
        isPrimary: Boolean = true,
        status: HomeQuotaStatus = HomeQuotaStatus.Normal,
        windowId: String = "five_hour",
    ) = HomeQuotaCardUi(
        windowId = windowId,
        titleResId = R.string.app_name,
        displayKind = QuotaWindowDisplayKind.Percent,
        usedPercent = remainingPercent?.let { 100 - it },
        balanceAmount = null,
        balanceCurrency = null,
        usedCount = null,
        limitCount = null,
        subLabel = null,
        isPrimary = isPrimary,
        resetAt = resetIn?.let(now::plus),
        status = status,
        tone = HomeStatusTone.Neutral,
        statusLabelResId = R.string.app_name,
        limitWindowSeconds = windowSeconds,
    )

    private fun resolve(status: HomeContentStatus = HomeContentStatus.Fresh, vararg cards: HomeQuotaCardUi) =
        HomeMascotMoodResolver.resolve(status, cards.toList(), now).mood

    @Test
    fun `額度剩很多但時間快到了是餓肚子`() {
        // 剩 80%，5 小時只剩 1 小時（20%）→ 差 60 點，用太慢
        assertEquals(HomeMascotMood.Hungry, resolve(cards = arrayOf(card(80, Duration.ofHours(1)))))
    }

    @Test
    fun `額度跟時間差不多是剛好`() {
        assertEquals(HomeMascotMood.Relaxed, resolve(cards = arrayOf(card(50, Duration.ofMinutes(150)))))
    }

    @Test
    fun `額度剩得比時間少很多是吃太快`() {
        assertEquals(HomeMascotMood.Stuffed, resolve(cards = arrayOf(card(10, Duration.ofHours(4)))))
    }

    @Test
    fun `額度用完是吃光光並帶出補貨時間`() {
        val result = HomeMascotMoodResolver.resolve(
            HomeContentStatus.Fresh,
            listOf(card(0, Duration.ofHours(2))),
            now,
        )
        assertEquals(HomeMascotMood.AteAll, result.mood)
        assertEquals(now.plus(Duration.ofHours(2)), result.refillAt)
    }

    @Test
    fun `登入失效是碗被蓋住`() {
        assertEquals(
            HomeMascotMood.Locked,
            resolve(HomeContentStatus.AuthRequired, card(80, Duration.ofHours(1))),
        )
    }

    @Test
    fun `滿額度又還沒開始計時也算餓`() {
        // Gemini 沒開始用時不給重置時間，碗是滿的
        assertEquals(HomeMascotMood.Hungry, resolve(cards = arrayOf(card(100, null))))
    }

    @Test
    fun `沒有百分比資料就是等資料`() {
        assertEquals(HomeMascotMood.Waiting, resolve(cards = arrayOf(card(null, null))))
        assertEquals(HomeMascotMood.Waiting, resolve())
    }

    @Test
    fun `優先看主要額度而不是排第一的`() {
        val weekly = card(90, Duration.ofDays(6), windowSeconds = 7 * 86400, isPrimary = false, windowId = "weekly")
        val fiveHour = card(0, Duration.ofHours(2))
        assertEquals(HomeMascotMood.AteAll, resolve(cards = arrayOf(weekly, fiveHour)))
    }
}
