package skillbill.application.review.snapshot

import skillbill.learnings.model.CreateLearningRequest
import skillbill.learnings.model.LearningRecord
import skillbill.learnings.model.LearningScope
import skillbill.learnings.model.LearningSourceValidation
import skillbill.learnings.model.UpdateLearningRequest
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.learning.LearningRepository
import skillbill.ports.learning.model.LearningResolution
import skillbill.ports.repository.RepositoryOriginScopeKeyPort
import skillbill.ports.repository.model.OriginScopeKey

const val ACTIVE_LEARNING_STATUS: String = "active"

val HARNESS_ORIGIN_UNAVAILABLE: RepositoryOriginScopeKeyPort =
  RepositoryOriginScopeKeyPort { OriginScopeKey.Unavailable("no origin remote in the review recording harness") }

fun harnessOrigin(scopeKey: String): RepositoryOriginScopeKeyPort =
  RepositoryOriginScopeKeyPort { OriginScopeKey.Resolved(scopeKey) }

fun harnessLearning(
  id: Int,
  scope: LearningScope,
  scopeKey: String,
  title: String,
  ruleText: String,
  status: String = ACTIVE_LEARNING_STATUS,
): LearningRecord =
  LearningRecord(
    id = id,
    scope = scope.wireName,
    scopeKey = scopeKey,
    title = title,
    ruleText = ruleText,
    rationale = "promoted from a rejected finding",
    status = status,
    sourceReviewRunId = null,
    sourceFindingId = null,
    createdAt = "2026-04-02T00:00:00Z",
    updatedAt = "2026-04-02T00:00:00Z",
  )

internal fun recordingDiagnostics(recorder: ReviewRecorder): RuntimeDiagnostics =
  object : RuntimeDiagnostics {
    override fun warning(
      message: String,
      error: Throwable?,
    ) {
      recorder.diagnosticWarnings += message
    }

    override fun error(
      message: String,
      error: Throwable?,
    ) {
      recorder.diagnosticErrors += message
    }
  }

fun recordingLearnings(
  recorder: ReviewRecorder = ReviewRecorder(),
  seeded: List<LearningRecord> = emptyList(),
): LearningRepository =
  object : LearningRepository {
    override fun resolve(
      repoScopeKey: String?,
      skillName: String?,
    ): LearningResolution {
      recorder.learningResolutions += repoScopeKey to skillName
      val matched =
        seeded
          .filter { it.status == ACTIVE_LEARNING_STATUS }
          .filter {
            when (LearningScope.fromWireName(it.scope)) {
              LearningScope.GLOBAL -> true
              LearningScope.REPO -> it.scopeKey == repoScopeKey
              LearningScope.SKILL -> it.scopeKey == skillName
            }
          }
      return LearningResolution(repoScopeKey, skillName, matched)
    }

    override fun saveSessionLearnings(
      reviewSessionId: String,
      learningsJson: String,
    ) {
      recorder.savedSessionLearnings += reviewSessionId to learningsJson
    }

    override fun list(status: String) = error("Unexpected learnings call: list")

    override fun get(id: Int) = error("Unexpected learnings call: get")

    override fun add(
      request: CreateLearningRequest,
      sourceValidation: LearningSourceValidation,
    ) = error("Unexpected learnings call: add")

    override fun edit(request: UpdateLearningRequest) = error("Unexpected learnings call: edit")

    override fun setStatus(
      id: Int,
      status: String,
    ) = error("Unexpected learnings call: setStatus")

    override fun delete(id: Int) = error("Unexpected learnings call: delete")
  }
