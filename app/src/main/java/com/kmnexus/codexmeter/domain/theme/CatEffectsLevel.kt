package com.kmnexus.codexmeter.domain.theme

/**
 * 額度貓特效等級，讓使用者依喜好換耗電。
 * - FULL：會散步、常駐眨眼擺尾、互動動畫全開。
 * - LIGHT：不散步、不跑常駐動畫，只有點牠的那一下有動作。
 * - STATIC：完全不播動畫，只換姿勢和台詞。
 * - HIDDEN：首頁不顯示額度貓。
 * 系統關閉動畫時，FULL / LIGHT 會在畫面層自動降成 STATIC。
 */
enum class CatEffectsLevel(val storageValue: String) {
    FULL("full"),
    LIGHT("light"),
    STATIC("static"),
    HIDDEN("hidden"),
    ;

    val walks: Boolean get() = this == FULL
    val idleAnimations: Boolean get() = this == FULL
    val interactionAnimations: Boolean get() = this == FULL || this == LIGHT

    /** 系統關動畫時最多只能到 STATIC（尊重無障礙設定）。 */
    fun respecting(systemAnimatorsEnabled: Boolean): CatEffectsLevel =
        if (!systemAnimatorsEnabled && (this == FULL || this == LIGHT)) STATIC else this

    companion object {
        fun fromStorage(value: String?): CatEffectsLevel =
            entries.firstOrNull { it.storageValue == value } ?: FULL
    }
}
