package skillbill.engine.goalrunner.assessment

import skillbill.application.decomposition.resolvedParentSpecPath
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.projectstore.ProjectStorePort
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.review.spec.InteractionScenarioSectionParser
import skillbill.review.spec.model.InteractionScenarioCatalog
import skillbill.review.spec.model.InteractionScenarioParseResult
import skillbill.review.spec.model.InteractionScenarioSpecContext
import skillbill.text.sha256HexUtf8
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.decompositionStatus
import java.io.IOException
import java.nio.file.Path

internal class GoalAssessmentInputAssembler(
  private val gitOperations: WorkflowGitOperations,
  private val fileStore: DecompositionManifestStore,
  private val projectStore: ProjectStorePort,
  private val diagnostics: RuntimeDiagnostics,
) {
  fun assemble(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
  ): GoalAssessmentAssembly =
    when (val git = gitEvidence(request.repoRoot, state.manifest.baseBranch)) {
      is GitEvidence.Missing -> unavailable(git.reason)
      is GitEvidence.Read -> assembleAt(request.repoRoot, state.manifest, git)
    }

  private fun assembleAt(
    repoRoot: Path,
    manifest: DecompositionManifest,
    git: GitEvidence.Read,
  ): GoalAssessmentAssembly {
    val store = projectStore.resolve(repoRoot)
    val specAt = { specPath: String -> readSpec(resolvedParentSpecPath(repoRoot, Path.of(specPath), store)) }
    val parentSpec = specAt(manifest.parentSpecPath) ?: return unavailable("the parent spec could not be read")
    val subtasks = subtaskInputs(repoRoot, manifest.subtasks, git.mergeBase, specAt)
    if (subtasks.none { it.range is GoalAssessmentCommitRange.Committed }) {
      return unavailable("no subtask recorded a commit to assess")
    }
    return GoalAssessmentAssembly.Ready(
      GoalAssessmentBundle(
        issueKey = manifest.issueKey,
        headSha = git.head,
        scopeFiles = git.scope,
        parentSpec = parentSpec,
        declared = declaredScenarios(parentSpec, manifest.subtasks.map(DecompositionSubtask::id).toSet()),
        subtasks = subtasks,
      ),
    )
  }

  private fun gitEvidence(
    repoRoot: Path,
    baseBranch: String,
  ): GitEvidence {
    val head =
      gitOperations.headCommitSha(repoRoot).valueOrNull()
        ?: return GitEvidence.Missing("the head commit could not be read")
    val mergeBase =
      gitOperations.mergeBaseWithHead(repoRoot, baseBranch).valueOrNull()
        ?: return GitEvidence.Missing("the merge base with '$baseBranch' could not be read")
    val changed = gitOperations.runtimePhaseChangedPathsBetweenCommits(repoRoot, mergeBase, head)
    val scope =
      (changed as? WorkflowGitNameListResult.Listed)?.names
        ?: return GitEvidence.Missing("the files changed since '$baseBranch' could not be listed")
    return GitEvidence.Read(head, mergeBase, scope)
  }

  private fun subtaskInputs(
    repoRoot: Path,
    subtasks: List<DecompositionSubtask>,
    mergeBase: String,
    readSpecText: (String) -> String?,
  ): List<GoalAssessmentSubtaskInput> {
    var previousSha = mergeBase
    return subtasks.map { subtask ->
      val sha = subtask.commitSha?.trim().orEmpty()
      val range =
        when {
          sha.isNotEmpty() ->
            GoalAssessmentCommitRange.Committed(previousSha, sha).also { previousSha = sha }
          subtask.status.decompositionStatus() in NO_CHANGE_STATUSES -> GoalAssessmentCommitRange.NoCommit
          else -> GoalAssessmentCommitRange.Unavailable("the manifest records no commit for subtask ${subtask.id}")
        }
      GoalAssessmentSubtaskInput(
        subtask.id,
        subtask.name,
        readSpecText(subtask.specPath),
        range,
        rangeFilesOf(repoRoot, range),
      )
    }
  }

  private fun rangeFilesOf(
    repoRoot: Path,
    range: GoalAssessmentCommitRange,
  ): List<String> {
    if (range !is GoalAssessmentCommitRange.Committed) return emptyList()
    val changed = gitOperations.runtimePhaseChangedPathsBetweenCommits(repoRoot, range.fromSha, range.toSha)
    return (changed as? WorkflowGitNameListResult.Listed)?.names
      ?: run {
        warn("the files changed by ${range.toSha} could not be listed; scoped reassessment treats it as untouched.")
        emptyList()
      }
  }

  private fun declaredScenarios(
    parentSpec: String,
    subtaskIds: Set<Int>,
  ): InteractionScenarioCatalog? =
    when (
      val parsed =
        InteractionScenarioSectionParser.parse(
          parentSpec,
          sha256HexUtf8(parentSpec),
          InteractionScenarioSpecContext.Parent(subtaskIds),
        )
    ) {
      is InteractionScenarioParseResult.Parsed -> parsed.catalog
      is InteractionScenarioParseResult.Rejected -> {
        warn("the parent spec's interaction scenarios were passed as absent: ${parsed.issues.firstOrNull()?.reason}")
        null
      }
    }

  private fun readSpec(path: Path): String? =
    try {
      fileStore.readText(path)
    } catch (failure: IOException) {
      warn("spec '$path' could not be read: ${failure.message}", failure)
      null
    }

  private fun unavailable(reason: String): GoalAssessmentAssembly.Unavailable {
    warn("assessment input unavailable: $reason.")
    return GoalAssessmentAssembly.Unavailable(reason)
  }

  private fun warn(
    message: String,
    cause: Throwable? = null,
  ) = RuntimeDiagnosticsBestEffortWarning.record(diagnostics, "goal_assessment_gate: $message", cause)

  private fun WorkflowGitOperationResult.valueOrNull(): String? =
    (this as? WorkflowGitOperationResult.Ok)?.value?.trim()?.takeIf(String::isNotEmpty)

  private sealed interface GitEvidence {
    data class Read(val head: String, val mergeBase: String, val scope: List<String>) : GitEvidence

    data class Missing(val reason: String) : GitEvidence
  }

  private companion object {
    val NO_CHANGE_STATUSES = setOf(DecompositionStatus.SKIPPED, DecompositionStatus.COMPLETED_NO_CHANGE)
  }
}
