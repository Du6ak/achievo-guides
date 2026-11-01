package dev.achievo.community

import java.text.Normalizer
import java.util.Locale

/** Shared with the editor and mediator; NFKC is an implementation choice, not an approved policy. */
fun normalizedGuideTitle(value: String): String =
    Normalizer.normalize(value,Normalizer.Form.NFKC).trim().replace(Regex("[\\s\\p{Z}]+")," ").lowercase(Locale.ROOT)

data class CommunityGuideTitleKey(val appId: Int, val achievementApiName: String, val language: String, val title: String)
fun communityGuideTitleKey(metadata: CommunityGuideMetadata) = CommunityGuideTitleKey(
    metadata.appId,
    metadata.achievementApiName,
    metadata.language,
    normalizedGuideTitle(metadata.title),
)
