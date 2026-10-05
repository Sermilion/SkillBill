package skillbill.application.review.parallel.planning

import skillbill.review.attribution.GENERIC_REVIEW_SKILL_NAME
import skillbill.scaffold.model.PlatformManifest
import java.util.UUID

internal const val REVIEW_SESSION_ID_PREFIX: String = "rvs-"

internal fun mintReviewSessionId(): String = REVIEW_SESSION_ID_PREFIX + UUID.randomUUID()

internal fun routedReviewSkillName(routedManifests: List<PlatformManifest>): String =
  routedManifests.firstNotNullOfOrNull(PlatformManifest::routedSkillName) ?: GENERIC_REVIEW_SKILL_NAME
