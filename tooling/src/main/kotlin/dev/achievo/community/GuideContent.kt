package dev.achievo.community

import kotlinx.serialization.Serializable
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.zip.CRC32
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream

const val GUIDE_IMAGE_MAX_BYTES = 4 * 1024 * 1024
const val GUIDE_IMAGES_MAX_BYTES = 20 * 1024 * 1024
const val GUIDE_IMAGES_MAX_COUNT = 24

@Serializable enum class GuideAuthorMode { SELF, OTHER }
@Serializable enum class GuidePartKind { TEXT, IMAGE }
@Serializable data class GuidePart(val id: String = UUID.randomUUID().toString(), val kind: GuidePartKind = GuidePartKind.TEXT,
    val text: String = "", val attachmentId: String? = null)
@Serializable data class GuideBlock(val id: String = UUID.randomUUID().toString(), val parts: List<GuidePart> = listOf(GuidePart()),
    val checkable: Boolean = false)
@Serializable data class GuideRelatedBlock(val achievementApiName: String = "", val title: String = "", val block: GuideBlock = GuideBlock())
@Serializable data class GuideAttachment(val sha256: String, val byteSize: Int, val width: Int, val height: Int)
@Serializable data class GuideContent(val steps: List<GuideBlock> = emptyList(), val tips: List<GuidePart> = emptyList(),
    val warnings: List<GuidePart> = emptyList(), val related: List<GuideRelatedBlock> = emptyList(),
    val attachments: List<GuideAttachment> = emptyList())

fun List<GuidePart>.plainText() = filter { it.kind == GuidePartKind.TEXT }.joinToString("\n") { it.text }
fun List<GuidePart>.hasContent() = any { it.kind == GuidePartKind.IMAGE || it.text.isNotBlank() }
fun GuideContent.parts(): List<GuidePart> = steps.flatMap { it.parts } + tips + warnings + related.flatMap { it.block.parts }
fun guideAttachmentPath(guideId: String, attachmentId: String) = "assets/$guideId/$attachmentId.png"
fun guidePublishedAttachmentPath(m: CommunityGuideMetadata, a: GuideAttachment) =
    guidePath(m).substringBeforeLast('/') + "/" + guideAttachmentPath(m.id,a.sha256)

fun CommunityGuideDraft.hasRelated() = relatedEnabled ?: relatedAchievements.isNotEmpty()
fun CommunityGuideDraft.hasSources() = sourcesEnabled ?: sourceUrls.isNotEmpty()
fun CommunityGuideDraft.effectiveAuthorMode() = authorMode ?: if(authorName.isNotBlank() && !authorshipConfirmed) GuideAuthorMode.OTHER else GuideAuthorMode.SELF
fun CommunityGuideDraft.resolvedAuthor() = if(effectiveAuthorMode() == GuideAuthorMode.OTHER) (otherAuthorName ?: authorName).trim()
    else selfAuthorName.ifBlank {if(authorMode==null && authorshipConfirmed) authorName else ""}
fun CommunityGuideDraft.publishedRelated() = if(!hasRelated()) emptyList() else content?.related?.map { it.achievementApiName } ?: relatedAchievements
fun CommunityGuideDraft.publishedContent(): GuideContent? = content?.let { c ->
    val visible = c.copy(related=if(hasRelated()) c.related else emptyList())
    val used = visible.parts().mapNotNull { it.attachmentId }.toSet()
    visible.copy(attachments=c.attachments.filter { it.sha256 in used })
}

/** Legacy string steps get repeatable IDs once; new blocks get independent UUIDs. */
fun CommunityGuideDraft.withRichContent(): CommunityGuideDraft {
    if(content != null) return this
    fun id(key: String) = UUID.nameUUIDFromBytes("$guideId:$key".toByteArray(Charsets.UTF_8)).toString()
    fun part(text: String,key: String) = GuidePart(id=id(key),text=text)
    return copy(content=GuideContent(steps=steps.mapIndexed { i,text -> GuideBlock(id("step:$i"),listOf(part(text,"step:$i:text"))) },
        tips=listOf(part(tips,"tips")),warnings=listOf(part(warnings,"warnings")),
        related=relatedAchievements.mapIndexed { i,api -> GuideRelatedBlock(api,block=GuideBlock(id("related:$i"),listOf(part("","related:$i:text")))) }))
}
fun CommunityGuideDraft.withContent(value: GuideContent) = copy(content=value,steps=value.steps.map { it.parts.plainText() },
    tips=value.tips.plainText(),warnings=value.warnings.plainText(),relatedAchievements=value.related.map { it.achievementApiName })

/** Only canonical PNG bytes are admitted; dimensions are checked before decoding. No SVG, GIF, HTML or URLs. */
fun validateGuideImage(bytes: ByteArray, attachment: GuideAttachment? = null): Pair<Int,Int> {
    require(bytes.size in 1..GUIDE_IMAGE_MAX_BYTES)
    require(bytes.take(8).toByteArray().contentEquals(byteArrayOf(137.toByte(),80,78,71,13,10,26,10)))
    validatePngChunks(bytes)
    val dimensions = MemoryCacheImageInputStream(ByteArrayInputStream(bytes)).use { input ->
        val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: error("Неподдерживаемое изображение")
        try { reader.input=input; val width=reader.getWidth(0);val height=reader.getHeight(0)
            require(width in 1..8192 && height in 1..8192 && width.toLong()*height <= 24_000_000)
            val options=reader.defaultReadParam
            val sampling=((maxOf(width,height)+1279)/1280).coerceAtLeast(1)
            options.setSourceSubsampling(sampling,sampling,0,0)
            require(reader.read(0,options)!=null); width to height
        } finally { reader.dispose() }
    }
    if(attachment != null) require(attachment.byteSize == bytes.size && attachment.sha256 == guideSha256(bytes) &&
        dimensions == attachment.width to attachment.height)
    return dimensions
}

/** Verify the complete PNG chunk stream and CRCs before the ImageIO decode. */
private fun validatePngChunks(bytes: ByteArray) {
    fun uint32(offset: Int): Long = (0..3).fold(0L) { n,i -> (n shl 8) or (bytes[offset+i].toLong() and 255) }
    var offset=8
    var seenHeader=false
    var seenData=false
    var seenEnd=false
    while(offset<bytes.size) {
        require(bytes.size-offset>=12)
        val length=uint32(offset)
        require(length<=bytes.size-offset-12L)
        val type=bytes.copyOfRange(offset+4,offset+8).toString(Charsets.US_ASCII)
        require(type.length==4 && type.all {it in 'A'..'Z' || it in 'a'..'z'})
        val end=offset+8+length.toInt()
        val crc=CRC32().apply {update(bytes,offset+4,length.toInt()+4)}.value
        require(crc==uint32(end))
        if(!seenHeader) require(type=="IHDR" && length==13L)
        when(type) {
            "IHDR" -> {require(!seenHeader && length==13L);seenHeader=true}
            "IDAT" -> seenData=true
            "IEND" -> {require(seenData && length==0L && end+4==bytes.size);seenEnd=true}
        }
        offset=end+4
        if(seenEnd) break
    }
    require(seenHeader && seenData && seenEnd && offset==bytes.size)
}

fun validateGuideContent(m: CommunityGuideMetadata) {
    val c=m.content ?: return
    require(c.steps.size in 1..128 && c.steps.any { it.parts.hasContent() }) { "Добавьте инструкцию" }
    require(c.related.size <= 32 && c.related.map {it.achievementApiName} == m.relatedAchievements)
    val blocks=c.steps+c.related.map {it.block}
    require(blocks.all {isGuideUuid(it.id)} && blocks.map {it.id}.distinct().size==blocks.size)
    val parts=c.parts()
    require(parts.size <= 512 && parts.all {isGuideUuid(it.id)} && parts.map {it.id}.distinct().size==parts.size)
    require(parts.count {it.kind==GuidePartKind.IMAGE}<=GUIDE_IMAGES_MAX_COUNT)
    parts.forEach { p -> when(p.kind) {
        GuidePartKind.TEXT -> require(p.attachmentId==null && p.text.length <= GUIDE_MAX_BYTES && p.text.none {it=='\u0000'})
        GuidePartKind.IMAGE -> require(p.text.isEmpty() && c.attachments.any {it.sha256==p.attachmentId})
    } }
    c.related.forEach { require(it.title.length<=500 && it.title.none(Char::isISOControl)) }
    require(c.attachments.size<=GUIDE_IMAGES_MAX_COUNT && c.attachments.sumOf {it.byteSize.toLong()}<=GUIDE_IMAGES_MAX_BYTES)
    require(c.attachments.map {it.sha256}.distinct().size==c.attachments.size)
    require(c.attachments.all { it.sha256.matches(Regex("[a-f0-9]{64}")) && it.byteSize in 1..GUIDE_IMAGE_MAX_BYTES &&
        it.width in 1..8192 && it.height in 1..8192 && it.width.toLong()*it.height<=24_000_000 })
    require(parts.mapNotNull {it.attachmentId}.toSet()==c.attachments.map {it.sha256}.toSet())
}

/** Markdown remains portable. Persistent checkbox identity lives in front matter, never in HTML comments. */
fun guideContentMarkdown(m: CommunityGuideMetadata): String = buildString {
    val c=requireNotNull(m.content)
    fun parts(parts: List<GuidePart>,indent: String="") { parts.forEach { p ->
        if(p.kind==GuidePartKind.TEXT) append(indent).append(CommunityGuideMarkdownParser.escapeText(p.text).replace("\n","\n$indent")).append("\n\n")
        else append(indent).append("![Иллюстрация](").append(guideAttachmentPath(m.id,requireNotNull(p.attachmentId))).append(")\n\n")
    } }
    fun block(b: GuideBlock,label: String) {
        append(if(b.checkable) "- [ ] " else "### ").append(CommunityGuideMarkdownParser.escapeText(label)).append("\n\n")
        parts(b.parts,if(b.checkable) "  " else "")
    }
    append("# ").append(CommunityGuideMarkdownParser.escapeText(m.title)).append("\n\n")
    if(m.summary.isNotBlank()) append(CommunityGuideMarkdownParser.escapeText(m.summary)).append("\n\n")
    append("## Пошаговая инструкция\n\n")
    c.steps.forEachIndexed {i,b->block(b,"Шаг ${i+1}")}
    if(c.tips.hasContent()) {append("## Полезные советы\n\n");parts(c.tips)}
    if(c.warnings.hasContent()) {append("## Предупреждения\n\n");parts(c.warnings)}
    if(c.related.isNotEmpty()) {append("## Связанные достижения\n\n");c.related.forEach {block(it.block,it.title.ifBlank {"Связанное достижение"})}}
    if(m.sourceUrls.isNotEmpty()) {append("## Источники\n\n");m.sourceUrls.forEachIndexed {i,url->
        append("- [Источник ${i+1}](").append(url.replace("(","%28").replace(")","%29")).append(")\n")}}
}.trim()
