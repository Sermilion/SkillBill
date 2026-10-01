package skillbill.engine.work

import skillbill.ports.idestatus.model.IdeStatusFreshness
import skillbill.ports.idestatus.model.IdeStatusLifecycleState
import skillbill.ports.idestatus.model.IdeStatusProblem
import skillbill.ports.idestatus.model.IdeStatusProblemCode
import skillbill.ports.idestatus.model.IdeStatusSnapshot
import skillbill.ports.idestatus.model.IdeStatusStep
import java.time.Instant

object IdeStatusProblemSnapshots {
  fun invalidRepositoryInput(
    observedAt: Instant,
    message: String,
  ): IdeStatusSnapshot =
    problemSnapshot(
      ProblemParts(
        repositoryIdentity = "repo-root-realpath-v1:invalid",
        observedAt = observedAt,
        content =
          ProblemContent(
            code = IdeStatusProblemCode.INVALID_REPOSITORY_INPUT,
            message = message,
            summary = message,
            stepLabel = "Invalid repository",
          ),
      ),
    )

  fun missingRepositoryIdentity(
    observedAt: Instant,
    message: String,
  ): IdeStatusSnapshot =
    problemSnapshot(
      ProblemParts(
        repositoryIdentity = "repo-root-realpath-v1:missing",
        observedAt = observedAt,
        content =
          ProblemContent(
            code = IdeStatusProblemCode.MISSING_REPOSITORY_IDENTITY,
            message = message,
            summary = message,
            stepLabel = "Missing repository identity",
          ),
      ),
    )

  fun absentDatabase(
    repositoryIdentity: String,
    observedAt: Instant,
  ): IdeStatusSnapshot =
    problemSnapshot(
      ProblemParts(
        repositoryIdentity = repositoryIdentity,
        observedAt = observedAt,
        content =
          ProblemContent(
            code = IdeStatusProblemCode.ABSENT_DATABASE,
            message = "Skill Bill database is not present.",
            summary = "Skill Bill database is not present for this repository.",
            stepLabel = "Database unavailable",
          ),
        exitLifecycle = IdeStatusLifecycleState.IDLE,
        freshness = IdeStatusFreshness.UNKNOWN,
      ),
    )

  fun noMatchingWork(
    repositoryIdentity: String,
    observedAt: Instant,
    branch: String? = null,
  ): IdeStatusSnapshot {
    val message =
      if (branch == null) {
        "No matching Skill Bill work for this repository."
      } else {
        "No recent Skill Bill work for branch '$branch'."
      }
    return problemSnapshot(
      ProblemParts(
        repositoryIdentity = repositoryIdentity,
        observedAt = observedAt,
        content =
          ProblemContent(
            code = IdeStatusProblemCode.NO_MATCHING_WORK,
            message = message,
            summary = message,
            stepLabel = "No matching work",
          ),
      ),
    )
  }

  fun incompatibleRecord(
    repositoryIdentity: String,
    observedAt: Instant,
    message: String,
    workflowId: String? = null,
  ): IdeStatusSnapshot =
    problemSnapshot(
      ProblemParts(
        repositoryIdentity = repositoryIdentity,
        observedAt = observedAt,
        content =
          ProblemContent(
            code = IdeStatusProblemCode.INCOMPATIBLE_RECORD,
            message = message,
            summary = message,
            stepLabel = "Incompatible record",
          ),
        workflowId = workflowId,
        exitLifecycle = IdeStatusLifecycleState.IDLE,
        freshness = IdeStatusFreshness.UNKNOWN,
      ),
    )

  private fun problemSnapshot(parts: ProblemParts): IdeStatusSnapshot =
    IdeStatusSnapshot(
      repositoryIdentity = parts.repositoryIdentity,
      workflowId = parts.workflowId,
      lifecycleState = parts.exitLifecycle,
      currentStep = IdeStatusStep(id = "none", label = parts.content.stepLabel),
      updatedAt = parts.observedAt,
      freshness = parts.freshness,
      summary = parts.content.summary,
      problem = IdeStatusProblem(code = parts.content.code, message = parts.content.message),
    )

  private data class ProblemContent(
    val code: IdeStatusProblemCode,
    val message: String,
    val summary: String,
    val stepLabel: String,
  )

  private data class ProblemParts(
    val repositoryIdentity: String,
    val observedAt: Instant,
    val content: ProblemContent,
    val workflowId: String? = null,
    val exitLifecycle: IdeStatusLifecycleState = IdeStatusLifecycleState.IDLE,
    val freshness: IdeStatusFreshness = IdeStatusFreshness.FRESH,
  )
}

internal fun IdeStatusSnapshot.exitCode(): Int =
  when (problem?.code) {
    IdeStatusProblemCode.INVALID_REPOSITORY_INPUT,
    IdeStatusProblemCode.MISSING_REPOSITORY_IDENTITY,
    IdeStatusProblemCode.INCOMPATIBLE_RECORD,
    IdeStatusProblemCode.SCHEMA_INCOMPATIBLE,
    -> 1
    IdeStatusProblemCode.ABSENT_DATABASE,
    IdeStatusProblemCode.NO_MATCHING_WORK,
    null,
    -> 0
  }
