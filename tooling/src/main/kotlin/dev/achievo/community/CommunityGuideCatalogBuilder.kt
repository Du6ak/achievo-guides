package dev.achievo.community

import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.time.Instant
import kotlin.system.exitProcess

/** Offline authority snapshot: API identifiers exported from Steam schemas, never inferred from prose. */
@kotlinx.serialization.Serializable
data class GuideAchievementRegistry(val games: Map<String, List<String>> = emptyMap()) {
    fun exists(appId: Int, apiName: String) = games[appId.toString()]?.contains(apiName) == true
}

object CommunityGuideCatalogBuilder {
    fun read(root: Path, registry: GuideAchievementRegistry): List<Pair<String, CommunityGuide>> {
        val folder = root.resolve("guides")
        require(Files.isDirectory(folder, NOFOLLOW_LINKS)) { "Отсутствует каталог guides" }
        val assets=linkedMapOf<String,Path>()
        val result = Files.walk(folder).use { paths -> paths.sorted().toList().filter { it != folder }.mapNotNull { path ->
            require(!Files.isSymbolicLink(path)) { "Символические ссылки запрещены: $path" }
            if (Files.isDirectory(path, NOFOLLOW_LINKS)) {
                val relative = folder.relativize(path).toString().replace('\\', '/')
                require(Regex("[1-9][0-9]*(?:/[a-z]{2,3}(?:-[A-Z][a-z]{3})?(?:-(?:[A-Z]{2}|[0-9]{3}))?(?:/[0-9a-f]{64}(?:/assets(?:/[0-9a-f-]{36})?)?)?)?").matches(relative)) { "Недопустимый каталог: $relative" }
                null
            } else if (path == folder.resolve(".gitkeep") && Files.size(path) == 0L) null else {
                val relative = root.relativize(path).toString().replace('\\', '/')
                if("/assets/" in relative) {
                    require(Files.isRegularFile(path,NOFOLLOW_LINKS) && Files.size(path)<=GUIDE_IMAGE_MAX_BYTES &&
                        Regex("guides/[1-9][0-9]*/[^/]+/[0-9a-f]{64}/assets/[0-9a-f-]{36}/[0-9a-f]{64}\\.png").matches(relative))
                    assets[relative]=path
                    return@mapNotNull null
                }
                require(Files.isRegularFile(path, NOFOLLOW_LINKS) && Files.size(path) <= GUIDE_MAX_BYTES) { "Некорректный файл $path" }
                val guide = CommunityGuideMarkdownParser.parse(Files.readString(path))
                CommunityGuideMarkdownParser.validate(guide, registry::exists, relative)
                relative to guide
            }
        } }
        validateGuideSet(result.map { it.second }, registry::exists)
        val used=linkedSetOf<String>()
        result.forEach { (_,guide) -> guide.metadata.content?.attachments.orEmpty().forEach { attachment ->
            val key=guidePublishedAttachmentPath(guide.metadata,attachment)
            validateGuideImage(Files.readAllBytes(requireNotNull(assets[key]) {"Отсутствует вложение"}),attachment)
            used+=key
        } }
        require(used==assets.keys) { "Неиспользуемые вложения запрещены" }
        return result
    }

    /** All input is checked before any output is published. Version directory is immutable per build. */
    fun build(root: Path, publicRoot: Path, registry: GuideAchievementRegistry, generatedAt: Instant = Instant.EPOCH): CommunityGuideCatalog {
        val source = read(root, registry)
        require(!publicRoot.toAbsolutePath().normalize().startsWith(root.resolve("guides").toAbsolutePath().normalize()))
        Files.createDirectories(publicRoot)
        val target = publicRoot.resolve("v1")
        require(!Files.isSymbolicLink(target)) { "Выходной v1 не может быть символической ссылкой" }
        val staging = Files.createTempDirectory(publicRoot, ".catalog-")
        try {
            val games = source.groupBy { it.second.metadata.appId }.toSortedMap().map { (appId, rows) ->
                val entries = rows.sortedBy { it.first }.map { (relative, guide) ->
                    // Copy exactly the bytes that are hashed; normalized output has already passed the same validator.
                    val bytes = CommunityGuideMarkdownParser.serialize(guide).toByteArray(Charsets.UTF_8)
                    val path = staging.resolve(relative)
                    Files.createDirectories(path.parent); Files.write(path, bytes)
                    guide.metadata.content?.attachments.orEmpty().forEach { attachment ->
                        val asset=guidePublishedAttachmentPath(guide.metadata,attachment)
                        val image=Files.readAllBytes(root.resolve(asset))
                        validateGuideImage(image,attachment)
                        Files.createDirectories(staging.resolve(asset).parent);Files.write(staging.resolve(asset),image)
                    }
                    // Keep instructions and attachment manifests lazy: only the opened Markdown contains content.
                    CommunityGuideEntry(guide.metadata.copy(content=null), relative, guideSha256(bytes))
                }
                val index = guideJson.encodeToString(CommunityGuideGameIndex(appId, entries)).toByteArray(Charsets.UTF_8)
                require(entries.size <= 10000 && index.size <= GUIDE_INDEX_MAX_BYTES) { "Индекс игры превышает лимит v1" }
                val indexPath = "games/$appId.json"
                Files.createDirectories(staging.resolve("games")); Files.write(staging.resolve(indexPath), index)
                CommunityGuideGame(appId, entries.map { it.metadata.language }.distinct().sorted(), indexPath, guideSha256(index))
            }
            val catalog = CommunityGuideCatalog(games, generatedAt.toString())
            val catalogBytes = guideJson.encodeToString(catalog).toByteArray(Charsets.UTF_8)
            require(games.size <= 50000 && catalogBytes.size <= GUIDE_CATALOG_MAX_BYTES) { "Каталог превышает лимит v1" }
            Files.write(staging.resolve("catalog.json"), catalogBytes)
            val backup = publicRoot.resolve(".previous-${java.util.UUID.randomUUID()}")
            if (Files.exists(target)) Files.move(target, backup, ATOMIC_MOVE)
            try { Files.move(staging, target, ATOMIC_MOVE) }
            catch(error: Exception) { if(Files.exists(backup)) Files.move(backup, target, ATOMIC_MOVE); throw error }
            if(Files.exists(backup)) deleteBuildDirectory(backup, publicRoot)
            return catalog
        } finally {
            if (Files.exists(staging)) deleteBuildDirectory(staging, publicRoot)
        }
    }

    private fun deleteBuildDirectory(directory: Path, parent: Path) {
        require(directory.toAbsolutePath().normalize().parent == parent.toAbsolutePath().normalize() && !Files.isSymbolicLink(directory))
        Files.walk(directory).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }
}

/** CLI is packaged by communityGuideTools; no GitHub or Steam network calls. */
fun main(args: Array<String>) {
    require(args.size >= 2) { "validate|check-title-set|build <repository> [new-public-directory] [--timestamp=ISO-8601]" }
    if(args[0] == "validate-file") {
        require(args.size == 3) { "validate-file <markdown-file> <steam-achievements.json>" }
        val registry = guideJson.decodeFromString<GuideAchievementRegistry>(Files.readString(Path.of(args[2])))
        val guide = CommunityGuideMarkdownParser.parse(Files.readString(Path.of(args[1])))
        CommunityGuideMarkdownParser.validate(guide, registry::exists)
        guide.metadata.content?.attachments.orEmpty().forEach { attachment ->
            val asset=Path.of(args[1]).toAbsolutePath().parent.resolve(guideAttachmentPath(guide.metadata.id,attachment.sha256))
            validateGuideImage(Files.readAllBytes(asset),attachment)
        }
        println("Validated guide ${guide.metadata.id}")
        return
    }
    val root = Path.of(args[1]).toAbsolutePath().normalize()
    val registry = guideJson.decodeFromString<GuideAchievementRegistry>(Files.readString(root.resolve("schema/steam-achievements.json")))
    when (args[0]) {
        "validate" -> println("Validated ${CommunityGuideCatalogBuilder.read(root, registry).size} community guides")
        "check-title-set" -> {
            require(args.size == 2) { "check-title-set <repository>" }
            try {
                println("Checked ${CommunityGuideFinalSetChecker.check(root, registry)} community guides in the supplied local set")
            } catch (e: CommunityGuideFinalSetChecker.TitleConflict) {
                System.err.println("TITLE_CONFLICT: ${e.message}")
                exitProcess(2)
            }
        }
        "build" -> {
            require(args.size >= 3)
            val timestamp = args.firstOrNull { it.startsWith("--timestamp=") }?.substringAfter('=')?.let(Instant::parse) ?: Instant.EPOCH
            val catalog = CommunityGuideCatalogBuilder.build(root, Path.of(args[2]), registry, timestamp)
            println("Built ${catalog.games.size} game indexes")
        }
        else -> error("Unknown command")
    }
}
