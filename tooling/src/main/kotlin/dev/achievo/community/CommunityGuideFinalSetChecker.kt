package dev.achievo.community

import java.nio.file.Path

/** Offline candidate-tree check, not a merge, moderation, or transition guard. */
object CommunityGuideFinalSetChecker {
    /** Raised only after the complete guide set has passed all other v1 checks. */
    class TitleConflict : IllegalArgumentException("Конфликт названий руководств в итоговом наборе")

    fun check(root: Path, registry: GuideAchievementRegistry): Int {
        val guides = CommunityGuideCatalogBuilder.read(root, registry)
        val keys = HashSet<CommunityGuideTitleKey>()
        guides.forEach { (_, guide) ->
            if (!keys.add(communityGuideTitleKey(guide.metadata))) throw TitleConflict()
        }
        return guides.size
    }
}
