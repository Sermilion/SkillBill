package skillbill.engine.goalrunner.assessment

import me.tatarka.inject.annotations.Inject
import skillbill.application.idestatus.AgentActivityStampWriter
import skillbill.engine.diagnostics.containableFailure
import skillbill.engine.featuretask.phaserun.PhaseRunEntry
import skillbill.engine.goal.GoalRunnerManifestState
import skillbill.engine.goalrunner.execution.support.GoalFinalizationRoutingBinder
import skillbill.engine.goalrunner.model.GoalAssessmentRecordRead
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore
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
  diagnostics: RuntimeDiagnostics,
  finalizationRouting: GoalFinalizationRoutingBinder,
) : GoalRunnerAssessmentGate {
  private val assembler = GoalAssessmentInputAssembler(gitOperations, fileStore, projectStore, diagnostics)
  private val services =
    GoalAssessmentRunServices(
      phaseRunEntry,
      outcomeStore,
      activityStampWriter,
      GoalAssessmentProgress(outcomeStore, clock, diagnostics),
      diagnostics,
      finalizationRouting,
    )
  private val loop =
    GoalAssessmentLoop(services, GoalAssessmentFixStep(services, gitOperations), this::runRound)

  override fun run(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
  ): GoalAssessmentStageResult =
    catchingNonCancellation { loop.run(state, request) }.getOrElse { failure ->
      val reason = warnContained("the assessment stage could not complete", failure)
      GoalAssessmentStageResult.Completed(persistUnavailable(state, reason))
    }

  private fun persistUnavailable(
    state: GoalRunnerManifestState,
    reason: String,
  ): GoalAssessmentRecord {
    val exited = GoalAssessmentLoopRules.unavailable(savedOrEmpty(state), reason)
    services.persist(state, exited)
    return exited
  }

  private fun savedOrEmpty(state: GoalRunnerManifestState): GoalAssessmentRecord =
    catchingNonCancellation {
      (outcomeStore.readGoalAssessmentRecord(state.parentWorkflowId) as? GoalAssessmentRecordRead.Recorded)?.record
    }.getOrElse { failure ->
      warnContained("the saved assessment record could not be read", failure)
      null
    } ?: emptyRecord(state, null)

  override fun skipNoChanges(state: GoalRunnerManifestState) {
    catchingNonCancellation { loop.skipNoChanges(state) }.onFailure { failure ->
      warnContained("the no-change skip could not be recorded", failure)
    }
  }

  private fun warnContained(
    what: String,
    failure: Throwable,
  ): String {
    val contained = containableFailure(failure)
    val reason = "$what: ${contained.javaClass.simpleName}: ${contained.message}"
    warn(reason, contained)
    return reason
  }

  override fun runRound(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    round: Int,
  ): GoalAssessmentRoundResult =
    catchingNonCancellation { assess(state, request, round) }.getOrElse { failure ->
      val reason = warnContained("assessment could not complete", failure)
      GoalAssessmentRoundResult.Unavailable(GoalAssessmentLoopRules.unavailable(savedOrEmpty(state), reason), reason)
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
    val existing = resumeRound(prior.rounds.getOrNull(round - 1), round, bundle.headSha)
    val problem = resumeProblem(prior, existing, round, bundle.headSha)
    if (problem != null) {
      return unavailable(prior.copy(exitReason = GoalAssessmentExitReason.ASSESSMENT_UNAVAILABLE), problem)
    }
    return GoalAssessmentRoundRun(services, state, request, bundle, prior, existing, round).execute()
  }

  private fun resumeRound(
    existing: GoalAssessmentRound?,
    round: Int,
    headSha: String,
  ): GoalAssessmentRound? {
    if (existing == null || round != 1) return existing
    if (existing.assessorRuns.isNotEmpty()) return existing
    val hasResults =
      existing.findings.isNotEmpty() || existing.scenarios.isNotEmpty() || existing.regressions.isNotEmpty()
    val repairStarted = existing.fix != null || existing.repairPlan != null || existing.targetRef != null
    return if (hasResults || repairStarted) existing else existing.copy(headSha = headSha)
  }

  private fun resumeProblem(
    prior: GoalAssessmentRecord,
    existing: GoalAssessmentRound?,
    round: Int,
    headSha: String,
  ): String? =
    when {
      round < 1 -> "round $round must be positive"
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
