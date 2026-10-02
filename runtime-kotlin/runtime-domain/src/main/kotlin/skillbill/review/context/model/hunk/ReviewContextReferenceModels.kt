package skillbill.review.context.model.hunk

import skillbill.error.shellcontent.ReviewLearningRuleTextTooLongError
import skillbill.error.shellcontent.ReviewLearningTitleTooLongError
import skillbill.review.model.requireRepositoryRelativePath
import skillbill.text.sha256HexUtf8

const val REVIEW_RULE_EXCERPT_MAX_CHARS: Int = 2_000

internal const val REVIEW_LEARNING_TITLE_MAX_CHARS: Int = 2_000

data class ReviewRevision(val sessionId: String, val runRevision: Int) {
  init {
    require(sessionId.isNotBlank()) { "Review revision session id must not be blank." }
    require(runRevision >= 1) { "Review run revision must be positive." }
  }

  val canonical: String get() = canonicalFields(sessionId, runRevision)
}

data class ReviewRuleReference(
  val ruleId: String,
  val sourcePath: String,
  val excerpt: String,
  val digest: String,
) {
  init {
    require(ruleId.isNotBlank()) { "Matched rule id must not be blank." }
    requireRepositoryRelativePath(sourcePath)
    require(excerpt.isNotBlank()) { "Matched rule excerpt must not be blank." }
    require(excerpt.length <= REVIEW_RULE_EXCERPT_MAX_CHARS) {
      "Matched rule excerpt exceeds the bounded projection limit of $REVIEW_RULE_EXCERPT_MAX_CHARS characters."
    }
    require(digest.matches(SHA256_HEX)) { "Matched rule digest must be lowercase SHA-256." }
    require(digest == digestOf(excerpt)) {
      "Matched rule '$ruleId' digest does not cover its excerpt; the excerpt is not attested."
    }
  }

  companion object {
    fun digestOf(excerpt: String): String = sha256HexUtf8(excerpt.replace("\r\n", "\n"))
  }

  val canonical: String
    get() =
      listOf(ruleId, sourcePath, excerpt.replace("\r\n", "\n"), digest)
        .let { canonicalFieldList(it) }
}

data class ReviewLearningsReference(
  val learningId: String,
  val source: String,
  val scope: String,
  val title: String,
  val ruleText: String,
  val digest: String,
) {
  init {
    require(learningId.isNotBlank() && source.isNotBlank()) { "Learnings reference identity must not be blank." }
    require(scope.isNotBlank()) { "Learnings reference scope must not be blank." }
    require(title.isNotBlank()) { "Learnings reference title must not be blank." }
    if (title.length > REVIEW_LEARNING_TITLE_MAX_CHARS) {
      throw ReviewLearningTitleTooLongError(learningId, title.length, REVIEW_LEARNING_TITLE_MAX_CHARS)
    }
    require(ruleText.isNotBlank()) { "Learnings reference rule text must not be blank." }
    if (ruleText.length > REVIEW_RULE_EXCERPT_MAX_CHARS) {
      throw ReviewLearningRuleTextTooLongError(learningId, ruleText.length, REVIEW_RULE_EXCERPT_MAX_CHARS)
    }
    require(digest.matches(SHA256_HEX)) { "Learnings reference digest must be lowercase SHA-256." }
    require(digest == digestOf(ruleText)) {
      "Learnings reference '$learningId' digest does not cover its rule text; the rule text is not attested."
    }
  }

  companion object {
    fun digestOf(ruleText: String): String = sha256HexUtf8(ruleText.replace("\r\n", "\n"))
  }

  val canonical: String
    get() =
      listOf(learningId, source, scope, title, ruleText.replace("\r\n", "\n"), digest)
        .let { canonicalFieldList(it) }
}

data class ReviewBuildTestFact(val kind: String, val command: String, val outcome: String) {
  init {
    require(kind.isNotBlank() && command.isNotBlank() && outcome.isNotBlank()) {
      "Build/test facts must carry a kind, command, and outcome."
    }
  }

  val canonical: String get() = canonicalFields(kind, command, outcome)
}

data class ReviewDependencyAllowlist(val paths: List<String>) {
  init {
    paths.forEach(::requireRepositoryRelativePath)
    require(normalized.distinct().size == normalized.size) { "Dependency allowlist paths must be unique." }
  }

  val normalized: List<String> get() = paths
  val canonical: String get() = canonicalFieldList(normalized.sorted())

  companion object {
    val EMPTY: ReviewDependencyAllowlist = ReviewDependencyAllowlist(emptyList())
  }
}

data class ReviewBaselineUntrackedPolicy(
  val includedPaths: List<String> = emptyList(),
  val excludedPaths: List<String> = emptyList(),
) {
  init {
    includedPaths.forEach(::requireRepositoryRelativePath)
    excludedPaths.forEach(::requireRepositoryRelativePath)
    require(includedPaths.distinct().size == includedPaths.size)
    require(excludedPaths.distinct().size == excludedPaths.size)
    require(includedPaths.intersect(excludedPaths.toSet()).isEmpty()) {
      "A baseline-untracked path cannot be both included and excluded."
    }
  }

  val canonical: String get() =
    canonicalFields(
      canonicalFieldList(includedPaths.sorted()),
      canonicalFieldList(excludedPaths.sorted()),
    )

  companion object {
    val EMPTY = ReviewBaselineUntrackedPolicy()
  }
}

data class ReviewEvidenceTarget(val targetId: String, val path: String, val hunkIds: List<String>) {
  init {
    require(targetId.isNotBlank()) { "Evidence target id must not be blank." }
    requireRepositoryRelativePath(path)
    require(hunkIds.distinct().size == hunkIds.size) { "Evidence target hunk ids must be unique." }
    require(hunkIds.all { it.matches(SHA256_HEX) }) { "Evidence target hunk ids must be content-addressed." }
  }

  val canonical: String
    get() = canonicalFields(targetId, path, canonicalFieldList(hunkIds.sorted()))
}
