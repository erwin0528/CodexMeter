package com.kmnexus.codexmeter.providers.gemini.dto

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/** gemini.google.com/usage 頁面 `UsagePageQuery` 回應裡 `viewer.rpcs.getUsageInfo` 的部分。 */
@Serializable
data class GeminiUsageInfoDto(
    val subscriptionTier: String? = null,
    val pools: List<Pool>? = null,
) {
    @Serializable
    data class Pool(
        val poolType: String? = null,
        val percentageUsed: Double? = null,
        val gxu: Gxu? = null,
    )

    @Serializable
    data class Gxu(val windowRefreshesAt: String? = null)
}

@Serializable
private data class ViewerDto(val rpcs: RpcsDto? = null)

@Serializable
private data class RpcsDto(val getUsageInfo: GeminiUsageInfoDto? = null)

/**
 * 解析 GraphQL 回應。判斷規則照 CodexBar `gemini-web` 外掛：
 * - 回傳 HTML（被轉去登入頁）或 `viewer: null` → 已登出，需要重新登入。
 * - 結構對不上 → 視為 Google 改版，回報 Invalid，不猜數字。
 */
object GeminiUsageDecoder {
    private val json = Json { ignoreUnknownKeys = true }
    private const val XSSI_PREFIX = ")]}'"

    sealed interface Decoded {
        data class Usage(val usage: GeminiUsageInfoDto) : Decoded
        data object SignedOut : Decoded
        data object Invalid : Decoded
    }

    fun decode(body: String): Decoded {
        val trimmed = body.trimStart()
        if (trimmed.startsWith("<")) return Decoded.SignedOut
        val results = try {
            json.parseToJsonElement(trimmed.removePrefix(XSSI_PREFIX))
        } catch (_: SerializationException) {
            return Decoded.Invalid
        } as? JsonArray ?: return Decoded.Invalid

        val data = results
            .mapNotNull { ((it as? JsonObject)?.get("data")) as? JsonObject }
            .firstOrNull { it.containsKey("viewer") }
            ?: return Decoded.Invalid
        val viewer = data["viewer"]
        if (viewer is JsonNull) return Decoded.SignedOut

        val usage = try {
            json.decodeFromJsonElement<ViewerDto>(viewer ?: return Decoded.Invalid).rpcs?.getUsageInfo
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
        return if (usage?.pools != null) Decoded.Usage(usage) else Decoded.Invalid
    }
}
