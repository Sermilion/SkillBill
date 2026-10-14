package skillbill.review.plan

import skillbill.review.plan.model.ReviewRoutingChangedFile
import skillbill.review.plan.model.ReviewStackRoutingFallback
import skillbill.review.plan.model.ReviewStackRoutingResult
import skillbill.scaffold.model.PlatformManifest

object ReviewStackRouting {
  fun routeByPath(
    manifests: List<PlatformManifest>,
    paths: List<String>,
  ): ReviewStackRoutingResult = route(manifests, paths.map { ReviewRoutingChangedFile(it, "") })

  fun route(
    manifests: List<PlatformManifest>,
    files: List<ReviewRoutingChangedFile>,
  ): ReviewStackRoutingResult {
    val changedFiles = files.filterNot { ReviewPathMatcher.isIgnored(it.path) }
    val concreteManifests = manifests.filterNot { CODE_REVIEW_CAPABILITY in it.fallbackCapabilities }
    val contentSignals =
      concreteManifests.associateWith { manifest ->
        manifest.routingSignals.content.distinct().map(::contentSignalPattern)
      }
    val signalOwners =
      concreteManifests.flatMap { manifest ->
        manifest.routingSignals.path.distinct().map { it to manifest.slug }
      }.groupBy({ it.first }, { it.second })

    val fallback by lazy { ReviewFallbackResolver.resolveOptional(manifests) }
    if (changedFiles.isEmpty()) {
      return ReviewStackRoutingResult(emptySet(), emptyMap())
    }
    val routedSlugs = linkedSetOf<String>()
    val ownedPathsBySlug = linkedMapOf<String, LinkedHashSet<String>>()
    val missingPackFallbacks = mutableListOf<ReviewStackRoutingFallback>()

    changedFiles.forEach { changed ->
      val scores =
        concreteManifests.associateWith { manifest ->
          val pathScore =
            manifest.routingSignals.path.distinct().sumOf { signal ->
              if (!ReviewPathMatcher.matches(changed.path, signal)) {
                0
              } else if (signalOwners.getValue(signal).size == 1) {
                UNIQUE_PATH_SIGNAL_SCORE
              } else {
                1
              }
            }
          val contentScore =
            contentSignals.getValue(manifest).count { signal ->
              signal.containsMatchIn(changed.changedContent)
            } * CONTENT_SIGNAL_SCORE
          pathScore to contentScore
        }.filterValues { (pathScore, _) -> pathScore > 0 }

      val resolved =
        scores.takeIf { it.isNotEmpty() }?.let {
          val strongestPath = it.values.maxOf { score -> score.first }
          val pathWinners = it.filterValues { score -> score.first == strongestPath }
          val strongestContent = pathWinners.values.maxOf { score -> score.second }
          resolveComposition(pathWinners.filterValues { score -> score.second == strongestContent }.keys)
        }
      val missingBaselinePlatforms =
        resolved?.codeReviewComposition?.baselineLayers.orEmpty()
          .map { layer -> layer.platform }
          .filter { platform -> manifests.none { it.slug == platform } }
      val fallbackPack = fallback
      val owners =
        if (resolved == null || missingBaselinePlatforms.isNotEmpty()) {
          if (resolved != null && missingBaselinePlatforms.isNotEmpty() && fallbackPack != null) {
            missingPackFallbacks += missingPackFallback(resolved.slug, missingBaselinePlatforms, fallbackPack.slug)
          }
          fallbackPack?.let { setOf(it.slug) }.orEmpty()
        } else {
          linkedSetOf(resolved.slug).apply {
            resolved.codeReviewComposition?.baselineLayers?.mapTo(this) { it.platform }
          }
        }
      owners.forEach { slug ->
        routedSlugs += slug
        ownedPathsBySlug.getOrPut(slug, ::linkedSetOf) += changed.path
      }
    }

    return ReviewStackRoutingResult(routedSlugs, ownedPathsBySlug, missingPackFallbacks.toList())
  }

  private fun resolveComposition(winners: Set<PlatformManifest>): PlatformManifest? {
    if (winners.size == 1) return winners.single()
    val survivors =
      winners.filterNot { candidate ->
        candidate.codeReviewComposition?.baselineLayers?.any { baseline ->
          winners.any { it.slug == baseline.platform }
        } == true
      }
    return survivors.singleOrNull()
  }

  private fun contentSignalPattern(signal: String): Regex {
    val start = if (signal.firstOrNull()?.isIdentifierCharacter() == true) IDENTIFIER_START_BOUNDARY else ""
    val end = if (signal.lastOrNull()?.isIdentifierCharacter() == true) IDENTIFIER_END_BOUNDARY else ""
    return Regex(start + Regex.escape(signal) + end, RegexOption.IGNORE_CASE)
  }

  private fun Char.isIdentifierCharacter(): Boolean = isLetterOrDigit() || this == '_'

  private fun missingPackFallback(
    routedSlug: String,
    missingPlatforms: List<String>,
    fallbackSlug: String,
  ): ReviewStackRoutingFallback =
    ReviewStackRoutingFallback(routedSlug, fallbackSlug, missingPlatforms.distinct().sorted())

  private const val CODE_REVIEW_CAPABILITY = "code-review"
  private const val UNIQUE_PATH_SIGNAL_SCORE = 10
  private const val CONTENT_SIGNAL_SCORE = 20
  private const val IDENTIFIER_START_BOUNDARY = "(?<![\\p{L}\\p{N}_])"
  private const val IDENTIFIER_END_BOUNDARY = "(?![\\p{L}\\p{N}_])"
}
