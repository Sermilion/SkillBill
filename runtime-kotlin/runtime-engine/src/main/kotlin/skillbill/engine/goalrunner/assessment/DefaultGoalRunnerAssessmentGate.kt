package skillbill.engine.goalrunner.assessment

import me.tatarka.inject.annotations.Inject
import skillbill.application.idestatus.AgentActivityStampWriter
import skillbill.engine.diagnostics.containableFailure
import skillbill.engine.featuretask.phaserun.PhaseRunEntry
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore
import skillbill.engine.goalrunner.persist.assessment.GoalAssessmentRecordRead
import skillbill.engine.goalrunner.qa.GoalRunnerPostChangeValidation
import skillbill.error.core.catchingNonCancellation
import skillbill.goalrunner.assessment.GoalAssessmentLoopRules
import skillbill.goalrunner.assessment.model.GoalAssessmentExitReason
import skillbill.goalrunner.assessment.model.GoalAssessmentRecord
import skillbill.goalrunner.assessment.model.GoalAssessmentRound
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.projectstore.ProjectStorePort
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import java.time.Clock

@Inject
public class DefaultGoalRunnerAssessmentGate(
  phaseRunEntry: PhaseRunEntry,
  private val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  gitOperations: WorkflowGitOperations,
  fileStore: DecompositionManifestStore,
  projectStore: ProjectStorePort,
  clock: Clock,
  activityStampWriter: AgentActivityStampWriter,
  validation: GoalRunnerPostChangeValidation,
  diagnostics: RuntimeDiagnostics,
) : GoalRunnerAssessmentGate {
  private val assembler = GoalAssessmentInputAssembler(gitOperations, fileStore, projectStore, diagnostics)
  private val services =
    GoalAssessmentRunServices(
      phaseRunEntry,
      outcomeStore,
      activityStampWriter,
      GoalAssessmentProgress(outcomeStore, clock, diagnostics),
      diagnostics,
    )
  private val loop =
    GoalAssessmentLoop(services, GoalAssessmentFixStep(services, gitOperations, validation), this::runRound)

  override fun run(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
  ): GoalAssessmentRecord =
    catchingNonCancellation { loop.run(state, request) }.getOrElse { failure ->
      warnContained("the assessment stage could not complete", failure)
      persistUnavailable(state)
    }

  private fun persistUnavailable(state: GoalRunnerManifestState): GoalAssessmentRecord {
    val fallback = emptyRecord(state, GoalAssessmentExitReason.ASSESSMENT_UNAVAILABLE)
    return catchingNonCancellation {
      val read = outcomeStore.readGoalAssessmentRecord(state.parentWorkflowId)
      val base = (read as? GoalAssessmentRecordRead.Recorded)?.record ?: fallback
      val exited =
        base.copy(
          exitReason = GoalAssessmentExitReason.ASSESSMENT_UNAVAILABLE,
          openFindingRefs = GoalAssessmentLoopRules.openFindings(base).map { it.findingRef },
        )
      services.persist(state, exited)
      exited
    }.getOrElse { failure ->
      warnContained("the unavailable exit could not be recorded", failure)
      fallback
    }
  }

  override fun skipNoChanges(state: GoalRunnerManifestState) {
    catchingNonCancellation { loop.skipNoChanges(state) }.onFailure { failure ->
      warnContained("the no-change skip could not be recorded", failure)
    }
  }

  private fun warnContained(
    what: String,
    failure: Throwable,
  ) {
    val contained = containableFailure(failure)
    warn("$what: ${contained.javaClass.simpleName}: ${contained.message}", contained)
  }

  override fun runRound(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    round: Int,
  ): GoalAssessmentRoundResult =
    catchingNonCancellation { assess(state, request, round) }.getOrElse { failure ->
      val contained = containableFailure(failure)
      val reason = "assessment could not complete: ${contained.javaClass.simpleName}: ${contained.message}"
      warn(reason, contained)
      val record = emptyRecord(state, GoalAssessmentExitReason.ASSESSMENT_UNAVAILABLE)
      GoalAssessmentRoundResult.Unavailable(record, reason)
    }

  private fun assess(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    round: Int,
  ): GoalAssessmentRoundResult {
    val bundle =
      when (val assembly = assembler.assemble(state, request)) {
        is GoalAssessmentAssembly.Ready -> assembly.bundle
        is GoalAssessmentAssembly.Unavailable ->
          return unavailable(
            emptyRecord(state, GoalAssessmentExitReason.ASSESSMENT_UNAVAILABLE),
            "the assessment input is unavailable: ${assembly.reason}",
          )
      }
    val prior =
      when (val read = outcomeStore.readGoalAssessmentRecord(state.parentWorkflowId)) {
        GoalAssessmentRecordRead.Absent -> emptyRecord(state, null)
        is GoalAssessmentRecordRead.Recorded -> read.record
        is GoalAssessmentRecordRead.Corrupt ->
          return unavailable(
            emptyRecord(state, GoalAssessmentExitReason.ASSESSMENT_UNAVAILABLE),
            "the stored assessment record is unreadable: ${read.reason}",
          )
      }
    val existing = prior.rounds.getOrNull(round - 1)
    val problem = resumeProblem(prior, existing, round, bundle.headSha)
    if (problem != null) {
      return unavailable(prior.copy(exitReason = GoalAssessmentExitReason.ASSESSMENT_UNAVAILABLE), problem)
    }
    return GoalAssessmentRoundRun(services, state, request, bundle, prior, existing, round).execute()
  }

  private fun resumeProblem(
    prior: GoalAssessmentRecord,
    existing: GoalAssessmentRound?,
    round: Int,
    headSha: String,
  ): String? =
    when {
      round !in 1..GoalAssessmentRecord.MAX_ROUNDS ->
        "round $round is outside the ${GoalAssessmentRecord.MAX_ROUNDS} rounds a goal allows"
      prior.rounds.size > round -> "round $round was superseded by round ${prior.rounds.size}"
      prior.rounds.size < round - 1 -> "round $round cannot start before round ${prior.rounds.size + 1}"
      existing != null && !existing.headSha.equals(headSha, ignoreCase = true) ->
        "round $round was assessed at ${existing.headSha}, but the goal head is now $headSha"
      else -> null
    }

  private fun unavailable(
    record: GoalAssessmentRecord,
    reason: String,
  ): GoalAssessmentRoundResult.Unavailable {
    warn(reason)
    return GoalAssessmentRoundResult.Unavailable(record, reason)
  }

  private fun emptyRecord(
    state: GoalRunnerManifestState,
    exitReason: GoalAssessmentExitReason?,
  ) = GoalAssessmentRecord(issueKey = state.manifest.issueKey, exitReason = exitReason)

  private fun warn(
    message: String,
    cause: Throwable? = null,
  ) = services.warn(message, cause)
}
