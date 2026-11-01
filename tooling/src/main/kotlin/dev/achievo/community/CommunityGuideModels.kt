package dev.achievo.community

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

internal val guideJson = Json { encodeDefaults = true; prettyPrint = true }
const val GUIDE_MAX_BYTES = 32 * 1024
const val GUIDE_CATALOG_MAX_BYTES = 4 * 1024 * 1024
const val GUIDE_INDEX_MAX_BYTES = 8 * 1024 * 1024
const val GUIDE_TITLE_LIMIT = 160
const val GUIDE_SUMMARY_LIMIT = 500

@Serializable
enum class CommunityGuideType(val title: String) {
    @SerialName("quick") QUICK("Быстрый способ"),
    @SerialName("detailed") DETAILED("Подробный способ"),
    @SerialName("safe") SAFE("Безопасный способ"),
    @SerialName("combo") COMBO("Комбо"),
    @SerialName("alternative") ALTERNATIVE("Альтернативный способ")
}

val communityGuideLanguages = linkedMapOf("ru" to "Русский", "en" to "English", "de" to "Deutsch",
    "fr" to "Français", "es" to "Español", "pt-BR" to "Português (Brasil)",
    "zh-Hans" to "简体中文", "ja" to "日本語", "ko" to "한국어")

@Serializable
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
data class CommunityGuideMetadata(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    val id: String,
    @SerialName("app_id") val appId: Int,
    @SerialName("achievement_api_name") val achievementApiName: String,
    val language: String,
    val title: String,
    @SerialName("guide_type") val guideType: CommunityGuideType,
    val spoilers: Boolean,
    val summary: String = "",
    @SerialName("author_name") val authorName: String = "",
    @SerialName("translation_of") val translationOf: String? = null,
    @SerialName("related_achievements") val relatedAchievements: List<String> = emptyList(),
    @SerialName("source_urls") val sourceUrls: List<String> = emptyList(),
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER) val content: GuideContent? = null,
)

data class CommunityGuide(val metadata: CommunityGuideMetadata, val markdown: String, val images: Map<String,ByteArray> = emptyMap())
@Serializable data class CommunityGuideEntry(val metadata: CommunityGuideMetadata, val path: String, val sha256: String)
@Serializable data class CommunityGuideGameIndex(val appId: Int, val guides: List<CommunityGuideEntry>, val schemaVersion: Int = 1)
@Serializable data class CommunityGuideGame(val appId: Int, val languages: List<String>, val indexPath: String, val sha256: String)
@Serializable data class CommunityGuideCatalog(val games: List<CommunityGuideGame>, val generatedAt: String, val schemaVersion: Int = 1,
    val languages: List<String> = games.flatMap { it.languages }.distinct().sorted())

fun guideSha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
fun achievementKey(apiName: String): String = guideSha256(apiName.toByteArray(StandardCharsets.UTF_8))
fun guidePath(m: CommunityGuideMetadata): String = "guides/${m.appId}/${m.language}/${achievementKey(m.achievementApiName)}/${m.id}.md"
fun isGuideUuid(value: String): Boolean = value.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) && runCatching { UUID.fromString(value) }.isSuccess

@Serializable
data class CommunityGuideDraft(
    val localId: String = UUID.randomUUID().toString(),
    val guideId: String = UUID.randomUUID().toString(),
    // Text preserves incomplete/invalid input during autosave.
    val appIdText: String = "", val achievementApiName: String = "",
    val gameName: String = "", val achievementName: String = "",
    val language: String = "ru", val title: String = "", val summary: String = "",
    val guideType: CommunityGuideType = CommunityGuideType.QUICK,
    val steps: List<String> = listOf(""), val tips: String = "", val warnings: String = "",
    val relatedAchievements: List<String> = emptyList(), val sourceUrls: List<String> = emptyList(),
    val spoilers: Boolean = true, val authorName: String = "", val authorshipConfirmed: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(), val updatedAt: Long = createdAt,
    val targetGuideId: String? = null, val baseRevision: String? = null, val translationOf: String? = null,
    // Additive draft-only fields. Markdown metadata v1 remains unchanged.
    val gameImageUrl: String? = null, val editorStep: Int? = null, val editorFurthestStep: Int = 0,
    val content: GuideContent? = null,
    val relatedEnabled: Boolean? = null, val sourcesEnabled: Boolean? = null,
    val authorMode: GuideAuthorMode? = null, val otherAuthorName: String? = null, val selfAuthorName: String = "",
    val achievementImageUrl: String? = null,
) {
    fun metadata() = CommunityGuideMetadata(id = guideId, appId = appIdText.toIntOrNull() ?: 0,
        achievementApiName = achievementApiName, language = language, title = title, guideType = guideType,
        spoilers = spoilers, summary = summary, authorName = resolvedAuthor(), translationOf = translationOf,
        relatedAchievements = publishedRelated(), sourceUrls = if(hasSources()) sourceUrls.filter(String::isNotBlank) else emptyList(),
        content = publishedContent())
    fun moveStep(index: Int, offset: Int): CommunityGuideDraft {
        val next = index + offset
        if (index !in steps.indices || next !in steps.indices) return this
        return if(content == null) copy(steps = steps.toMutableList().apply { add(next, removeAt(index)) })
        else withContent(content.copy(steps=content.steps.toMutableList().apply { add(next,removeAt(index)) }))
    }
}
