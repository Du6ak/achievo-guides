package dev.achievo.community

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.net.URI
import java.util.Locale

/** Shared restricted YAML 1.2: flat scalars/string arrays plus typed JSON flow content; no YAML object construction. */
object CommunityGuideMarkdownParser {
    fun parse(file: String): CommunityGuide {
        require(file.toByteArray(Charsets.UTF_8).size <= GUIDE_MAX_BYTES) { "Руководство превышает 32 КиБ" }
        val normalized = file.removePrefix("\uFEFF").replace("\r\n", "\n")
        require(normalized.startsWith("---\n")) { "Отсутствует YAML Front Matter" }
        val end = normalized.indexOf("\n---\n", 4)
        require(end >= 0) { "Не закрыт YAML Front Matter" }
        val fields = linkedMapOf<String, JsonElement>()
        var listKey: String? = null
        normalized.substring(4, end).lines().forEach { line ->
            if (line.isBlank() || line.trimStart().startsWith("#")) return@forEach
            if (line.startsWith("  - ")) {
                val key = requireNotNull(listKey) { "Неожиданный элемент YAML" }
                fields[key] = JsonArray((fields[key] as JsonArray) + scalar(line.substring(4)))
            } else {
                val match = Regex("([a-z_]+):(?: (.*))?").matchEntire(line)
                requireNotNull(match) { "Поддерживаются только плоские YAML поля и списки" }
                val key = match.groupValues[1]
                require(key !in fields) { "Повтор поля $key" }
                val value = match.groupValues[2].trim()
                listKey = if (value.isEmpty()) key else null
                fields[key] = if (value.isEmpty()) JsonArray(emptyList()) else scalar(value)
            }
        }
        // Unknown properties and missing mandatory fields are errors, including spoilers/schema_version.
        val mandatory = setOf("schema_version", "id", "app_id", "achievement_api_name", "language", "title", "guide_type", "spoilers")
        require(fields.keys.containsAll(mandatory)) { "Не заполнены обязательные метаданные: ${mandatory - fields.keys}" }
        listOf("schema_version", "app_id").forEach { key ->
            val value = fields[key] as? JsonPrimitive
            require(value != null && !value.isString && value.intOrNull != null) { "$key должен быть целым числом YAML" }
        }
        val spoilers = fields["spoilers"] as? JsonPrimitive
        require(spoilers != null && !spoilers.isString && spoilers.booleanOrNull != null) { "spoilers должен быть true или false" }
        listOf("id", "achievement_api_name", "language", "title", "guide_type", "summary", "author_name").forEach { key ->
            if(key in fields) require((fields[key] as? JsonPrimitive)?.isString == true) { "$key должен быть строкой" }
        }
        val m = guideJson.decodeFromJsonElement<CommunityGuideMetadata>(JsonObject(fields))
        return CommunityGuide(m, normalized.substring(end + 5).trim()).also { validate(it) }
    }

    private fun scalar(value: String): JsonElement = when {
        value.startsWith("\"") || value.startsWith("[") || value.startsWith("{") -> guideJson.parseToJsonElement(value)
        value.startsWith("'") -> {
            require(value.matches(Regex("'(?:[^']|'')*'")))
            JsonPrimitive(value.substring(1, value.lastIndex).replace("''", "'"))
        }
        value == "null" || value == "~" -> JsonNull
        value == "true" -> JsonPrimitive(true)
        value == "false" -> JsonPrimitive(false)
        value.matches(Regex("-?[0-9]+")) -> JsonPrimitive(value.toLong())
        else -> {
            require(value.none { it in "&*!{}|>" } && ": " !in value) { "Используйте кавычки для YAML строки" }
            JsonPrimitive(value)
        }
    }

    fun validate(guide: CommunityGuide, exists: ((Int, String) -> Boolean)? = null, path: String? = null) {
        val m = guide.metadata
        require(m.schemaVersion == 1) { "Неподдерживаемая версия схемы" }
        require(isGuideUuid(m.id)) { "Некорректный UUID руководства" }
        require(m.appId > 0) { "AppID должен быть положительным целым числом" }
        require(m.achievementApiName.isNotBlank() && m.achievementApiName.length <= 256 && m.achievementApiName.none(Char::isISOControl)) { "Некорректное API-имя достижения" }
        require(m.language.matches(Regex("[a-z]{2,3}(?:-[A-Z][a-z]{3})?(?:-(?:[A-Z]{2}|[0-9]{3}))?"))) { "Некорректный код языка BCP 47" }
        require(Locale.Builder().setLanguageTag(m.language).build().toLanguageTag() == m.language) { "Используйте стандартный код языка" }
        require(m.title.isNotBlank() && m.title.length <= GUIDE_TITLE_LIMIT && m.title.none(Char::isISOControl)) { "Название: от 1 до 160 символов, одна строка" }
        require(m.summary.length <= GUIDE_SUMMARY_LIMIT && m.authorName.length <= 80 && m.authorName.none(Char::isISOControl)) { "Описание до 500, имя автора до 80 символов" }
        require(guide.markdown.isNotBlank()) { "Добавьте инструкцию" }
        require(m.relatedAchievements.size <= 32 && m.relatedAchievements.distinct().size == m.relatedAchievements.size)
        require(m.relatedAchievements.all { it.isNotBlank() && it.length <= 256 && it.none(Char::isISOControl) })
        require(m.achievementApiName !in m.relatedAchievements) { "Основное достижение нельзя связать с самим собой" }
        require(m.translationOf == null || isGuideUuid(m.translationOf) && m.translationOf != m.id) { "Некорректная связь перевода" }
        require(m.sourceUrls.size <= 16 && m.sourceUrls.all(::isSafeGuideUrl)) { "Разрешены только безопасные HTTP/HTTPS ссылки" }
        listOf(m.title, m.summary, m.authorName).forEach(::validateMarkdown)
        validateGuideContent(m)
        validateMarkdown(guide.markdown,m.content?.attachments.orEmpty().map {guideAttachmentPath(m.id,it.sha256)}.toSet())
        if(m.content!=null) require(guide.markdown.trim()==guideContentMarkdown(m)) { "Текст не соответствует структурированному содержимому" }
        require(serializeUnchecked(guide).toByteArray(Charsets.UTF_8).size <= GUIDE_MAX_BYTES) { "Руководство превышает 32 КиБ" }
        if (exists != null) {
            require(exists(m.appId, m.achievementApiName)) { "Достижение отсутствует в подтверждённой схеме Steam" }
            require(m.relatedAchievements.all { exists(m.appId, it) }) { "Связанное достижение отсутствует в схеме Steam" }
        }
        if (path != null) require(path == guidePath(m)) { "Метаданные не совпадают с путём файла" }
    }

    fun serialize(guide: CommunityGuide): String {
        validate(guide)
        return serializeUnchecked(guide)
    }

    private fun serializeUnchecked(guide: CommunityGuide): String = buildString {
        append("---\n")
        guideJson.encodeToJsonElement(guide.metadata).jsonObject.forEach { (key, value) ->
            append(key).append(": ").append(Json.encodeToString(value)).append('\n')
        }
        append("---\n\n").append(guide.markdown.trim()).append('\n')
    }

    /** Form text is always literal. Source links are the only generated link destinations. */
    fun escapeText(value: String): String = buildString {
        value.replace("\r\n", "\n").forEach { c -> when(c) {
            '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;")
            '\\', '*', '_', '[', ']', '!', '#', '`', '~', '-', '+', '.', '(', ')' -> append('\\').append(c)
            else -> append(c)
        } }
    }

    fun fromDraft(draft: CommunityGuideDraft): CommunityGuide {
        if(draft.content != null) {
            val metadata=draft.metadata()
            return CommunityGuide(metadata,guideContentMarkdown(metadata))
        }
        require(draft.steps.any { it.isNotBlank() }) { "Добавьте хотя бы один шаг" }
        val body = buildString {
            append("# ").append(escapeText(draft.title)).append("\n\n")
            if (draft.summary.isNotBlank()) append(escapeText(draft.summary)).append("\n\n")
            append("## Пошаговая инструкция\n\n")
            draft.steps.filter { it.isNotBlank() }.forEachIndexed { i, text ->
                append(i + 1).append(". ").append(escapeText(text).replace("\n", "\n   ")).append("\n")
            }
            if (draft.tips.isNotBlank()) append("\n## Полезные советы\n\n").append(escapeText(draft.tips)).append('\n')
            if (draft.warnings.isNotBlank()) append("\n## Предупреждения\n\n").append(escapeText(draft.warnings)).append('\n')
            if (draft.metadata().sourceUrls.isNotEmpty()) {
                require(draft.metadata().sourceUrls.all(::isSafeGuideUrl)) { "Проверьте ссылки на источники" }
                append("\n## Источники\n\n")
                draft.metadata().sourceUrls.forEachIndexed { i, url -> append("- [Источник ${i+1}](").append(url.replace("(", "%28").replace(")", "%29")).append(")\n") }
            }
        }
        return CommunityGuide(draft.metadata(), body)
    }
}

fun isSafeGuideUrl(value: String): Boolean = runCatching {
    require(value.length <= 2048 && value.none { it.isWhitespace() || it.isISOControl() || it in "\\<>\"" })
    val uri = URI(value)
    uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.rawUserInfo == null
}.getOrDefault(false)

internal val markdownLink = Regex("(?<!!)\\[([^]\\n]*)]\\(([^\\s)]+)\\)")
fun validateMarkdown(body: String, attachments: Set<String> = emptySet()) {
    require(body.none { it == '<' || it == '>' || it == '\u0000' || it.isISOControl() && it !in "\n\r\t" }) { "HTML не поддерживается; используйте обычный текст" }
    val image = Regex("!\\[Иллюстрация]\\((assets/[0-9a-f-]{36}/[0-9a-f]{64}\\.png)\\)")
    val withoutImages=image.replace(body) { match -> require(match.groupValues[1] in attachments) { "Изображение отсутствует во вложениях" }; "" }
    val unescaped = withoutImages.replace(Regex("\\\\."), "")
    require(!Regex("!\\[").containsMatchIn(unescaped)) { "Изображения не поддерживаются" }
    require(!Regex("(?m)^\\s*```|^\\s*~~~|^\\s*\\[[^]]+]:").containsMatchIn(unescaped)) { "Используйте простой Markdown без блоков кода и ссылок-сносок" }
    markdownLink.findAll(withoutImages).forEach { require(isSafeGuideUrl(it.groupValues[2])) { "Небезопасная ссылка" } }
    // Malformed destinations and unsupported reference links cannot bypass URL validation.
    require(!Regex("(?<!!)\\[[^]\\n]*]\\s*(?:\\(|\\[)").containsMatchIn(markdownLink.replace(withoutImages, ""))) { "Некорректная Markdown ссылка" }
}

fun validateGuideSet(guides: List<CommunityGuide>, exists: ((Int, String) -> Boolean)? = null) {
    val byId = guides.associateBy { it.metadata.id }
    require(byId.size == guides.size) { "Дублирующийся UUID" }
    guides.forEach { guide ->
        CommunityGuideMarkdownParser.validate(guide, exists)
        guide.metadata.translationOf?.let { id ->
            val original = requireNotNull(byId[id]) { "Оригинал перевода не найден" }.metadata
            validateGuideTranslation(guide.metadata,original)
        }
    }
}

/** Shared relation rule for both catalog building and submission checks. */
fun validateGuideTranslation(translation: CommunityGuideMetadata, original: CommunityGuideMetadata) {
    require(translation.translationOf == original.id && original.translationOf == null && original.appId == translation.appId &&
        original.achievementApiName == translation.achievementApiName && original.language != translation.language &&
        original.guideType == translation.guideType) { "Перевод должен ссылаться на оригинальный способ того же достижения на другом языке" }
}
