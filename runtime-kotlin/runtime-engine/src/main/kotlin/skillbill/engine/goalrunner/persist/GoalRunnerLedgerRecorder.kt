package skillbill.engine.goalrunner.persist

import skillbill.engine.goalrunner.model.GoalAttemptLedgerEntryDraft
import skillbill.engine.goalrunner.model.GoalRunnerAttemptLedgerRecordRequest
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.model.GoalRunnerWorkflowProgress
import skillbill.engine.goalrunner.telemetry.GoalRunnerBestEffortEmission
import skillbill.error.core.failureCodeLabel
import skillbill.goalrunner.model.GoalAttemptLaunchOutcome
import skillbill.goalrunner.model.GoalAttemptLedgerAction
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunTermination
import skillbill.ports.diagnostics.RuntimeDiagnostics
import java.time.Clock
import kotlin.coroutines.cancellation.CancellationException

class GoalRunnerLedgerRecorder(
  private val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  private val request: GoalRunnerRunRequest,
  private val clock: Clock,
  private val diagnostics: RuntimeDiagnostics,
) {
  private val cumulativeBackwardEdgeCounts: MutableMap<String, Int> =
    try {
      outcomeStore.ledgerSequenceWatermarks(request.issueKey).backwardEdgeCounts.toMutableMap()
    } catch (interrupted: InterruptedException) {
      Thread.currentThread().interrupt()
      throw interrupted
    }

  internal fun recordBackwardEdgeEntry(edge: GoalRunnerBackwardEdge) {
    val key = "${edge.subtaskId}:${edge.loopId}"
    val newCount = (cumulativeBackwardEdgeCounts[key] ?: 0) + edge.edgeIteration.coerceAtLeast(1)
    cumulativeBackwardEdgeCounts[key] = newCount
    recordLedgerEntry(
      GoalRunnerLedgerContext.BackwardEdgeEntry(
        workflowId = edge.workflowId,
        issueKey = edge.issueKey,
        subtaskId = edge.subtaskId,
        progress = edge.progress,
        loopId = edge.loopId,
        cumulativeLoopCount = newCount,
      ),
    )
  }

  internal fun recordLedgerEntry(context: GoalRunnerLedgerContext) {
    val targetWorkflowId = context.workflowId?.takeIf(String::isNotBlank) ?: return
    val details = context.details()
    val launchFacts = details.launchOutcome as? AgentRunLaunchFacts
    val draft = buildLedgerEntryDraft(context, targetWorkflowId, details, launchFacts)
    val result =
      runCatching {
        outcomeStore.recordAttemptLedgerEntry(
          GoalRunnerAttemptLedgerRecordRequest(
            workflowId = targetWorkflowId,
            issueKey = request.issueKey,
            draft = draft,
          ),
        )
      }
    when (val failure = result.exceptionOrNull()) {
      null ->
        if (!result.getOrThrow()) {
          logBestEffortMissingWorkflow(
            "attempt_ledger:${context.action.wireValue}",
            targetWorkflowId,
            context.subtaskId,
          )
        }
      is CancellationException -> throw failure
      is InterruptedException -> {
        Thread.currentThread().interrupt()
        throw failure
      }
      else ->
        logBestEffortFailure(
          "attempt_ledger:${context.action.wireValue}",
          targetWorkflowId,
          context.subtaskId,
          failure,
        )
    }
  }

  private fun buildLedgerEntryDraft(
    context: GoalRunnerLedgerContext,
    targetWorkflowId: String,
    details: GoalRunnerLedgerDetails,
    launchFacts: AgentRunLaunchFacts?,
  ): GoalAttemptLedgerEntryDraft =
    GoalAttemptLedgerEntryDraft(
      action = context.action,
      timestamp = clock.instant(),
      issueKey = context.issueKey.takeIf(String::isNotBlank),
      subtaskId = context.subtaskId.takeIf { it > 0 },
      previousWorkflowId = targetWorkflowId,
      previousStatus = details.progress?.workflowStatus,
      previousStep = details.progress?.currentStepId,
      blockedReason = details.blockedReason?.takeIf(String::isNotBlank),
      latestLiveness = details.progress?.latestLivenessSignal,
      launchOutcome = launchFacts?.let(::launchFinalStatus),
      timedOut = launchFacts?.let { it.termination == AgentRunTermination.TimedOut },
      interrupted = launchFacts?.let { it.termination == AgentRunTermination.Interrupted },
      childSessionPath = launchFacts?.childSessionPath,
      childSessionId = launchFacts?.childSessionId,
      finalReconciledResult = details.finalReconciledResult?.takeIf(String::isNotBlank),
      stopReason = details.stopReason?.takeIf(String::isNotBlank),
      diagnosticClass = details.diagnosticClass?.takeIf(String::isNotBlank),
      currentStep = details.progress?.currentStepId?.takeIf(String::isNotBlank),
      exitStatus = (launchFacts?.termination as? AgentRunTermination.Exited)?.code,
      recoverableJsonPresent = details.recoverableJsonPresent,
      nextSafeAction = details.nextSafeAction?.takeIf(String::isNotBlank),
      loopId = details.loopId?.takeIf(String::isNotBlank),
      cumulativeLoopCount = details.cumulativeLoopCount,
      attemptDurationMillis = details.attemptDurationMillis,
      causingLoopEntry = details.causingLoopEntry?.takeIf(String::isNotBlank),
      reAttemptCause = details.reAttemptCause?.takeIf(String::isNotBlank),
      findingsInScope = details.findingsInScope,
    )

  private fun logBestEffortFailure(
    action: String,
    workflowId: String,
    subtaskId: Int,
    error: Throwable,
  ) {
    GoalRunnerBestEffortEmission.recordWarning(
      diagnostics,
      "Best-effort goal ledger write failed: action='$action' workflowId='$workflowId' subtaskId=$subtaskId " +
        "errorType='${error.failureCodeLabel() ?: error::class.qualifiedName}' " +
        "message='${GoalRunnerBestEffortEmission.boundedMessage(error.message.orEmpty())}'",
      error,
    )
  }

  private fun logBestEffortMissingWorkflow(
    action: String,
    workflowId: String,
    subtaskId: Int,
  ) {
    GoalRunnerBestEffortEmission.recordWarning(
      diagnostics,
      "Best-effort goal ledger write skipped (workflow not found): action='$action' " +
        "workflowId='$workflowId' subtaskId=$subtaskId",
    )
  }

  private fun launchFinalStatus(facts: AgentRunLaunchFacts): GoalAttemptLaunchOutcome =
    when (val termination = facts.termination) {
      AgentRunTermination.SpawnFailed -> GoalAttemptLaunchOutcome.SpawnFailed
      AgentRunTermination.TimedOut -> GoalAttemptLaunchOutcome.TimedOut
      AgentRunTermination.Interrupted -> GoalAttemptLaunchOutcome.Interrupted
      is AgentRunTermination.Exited -> GoalAttemptLaunchOutcome.Exited(termination.code)
    }
}

internal data class GoalRunnerBackwardEdge(
  val workflowId: String,
  val issueKey: String,
  val subtaskId: Int,
  val loopId: String,
  val edgeIteration: Int,
  val progress: GoalRunnerWorkflowProgress?,
)

internal data class StoppedLedgerContextValues(
  val workflowId: String?,
  val issueKey: String,
  val subtaskId: Int,
  val progress: GoalRunnerWorkflowProgress?,
  val blockedReason: String?,
  val finalReconciledResult: String?,
  val stopReason: String?,
  val diagnosticClass: String?,
  val recoverableJsonPresent: Boolean?,
  val nextSafeAction: String?,
  val attemptDurationMillis: Long?,
  val causingLoopEntry: String?,
  val reAttemptCause: String?,
  val findingsInScope: Int?,
)

internal sealed interface GoalRunnerLedgerContext {
  val workflowId: String?
  val action: GoalAttemptLedgerAction
  val issueKey: String
  val subtaskId: Int

  data class ChildActivation(
    override val workflowId: String?,
    override val issueKey: String,
    override val subtaskId: Int,
    val progress: GoalRunnerWorkflowProgress? = null,
    val launchOutcome: AgentRunLaunchOutcome? = null,
    val diagnosticClass: String? = null,
    val recoverableJsonPresent: Boolean? = null,
    val nextSafeAction: String? = null,
    val causingLoopEntry: String? = null,
    val reAttemptCause: String? = null,
  ) : GoalRunnerLedgerContext {
    override val action: GoalAttemptLedgerAction = GoalAttemptLedgerAction.CHILD_ACTIVATION
  }

  data class Resume(
    override val workflowId: String?,
    override val issueKey: String,
    override val subtaskId: Int,
    val progress: GoalRunnerWorkflowProgress? = null,
    val launchOutcome: AgentRunLaunchOutcome? = null,
    val diagnosticClass: String? = null,
    val recoverableJsonPresent: Boolean? = null,
    val nextSafeAction: String? = null,
    val causingLoopEntry: String? = null,
    val reAttemptCause: String? = null,
  ) : GoalRunnerLedgerContext {
    override val action: GoalAttemptLedgerAction = GoalAttemptLedgerAction.RESUME
  }

  data class Retry(
    override val workflowId: String?,
    override val issueKey: String,
    override val subtaskId: Int,
    val progress: GoalRunnerWorkflowProgress?,
    val blockedReason: String?,
    val finalReconciledResult: String?,
    val stopReason: String?,
    val diagnosticClass: String?,
    val recoverableJsonPresent: Boolean?,
    val nextSafeAction: String?,
    val attemptDurationMillis: Long?,
    val causingLoopEntry: String?,
    val reAttemptCause: String?,
    val findingsInScope: Int?,
  ) : GoalRunnerLedgerContext {
    override val action: GoalAttemptLedgerAction = GoalAttemptLedgerAction.RETRY

    constructor(values: StoppedLedgerContextValues) : this(
      values.workflowId,
      values.issueKey,
      values.subtaskId,
      values.progress,
      values.blockedReason,
      values.finalReconciledResult,
      values.stopReason,
      values.diagnosticClass,
      values.recoverableJsonPresent,
      values.nextSafeAction,
      values.attemptDurationMillis,
      values.causingLoopEntry,
      values.reAttemptCause,
      values.findingsInScope,
    )
  }

  data class TerminalDoneCheck(
    override val workflowId: String?,
    override val issueKey: String,
    override val subtaskId: Int,
    val progress: GoalRunnerWorkflowProgress?,
    val finalReconciledResult: String?,
    val attemptDurationMillis: Long?,
  ) : GoalRunnerLedgerContext {
    override val action: GoalAttemptLedgerAction = GoalAttemptLedgerAction.TERMINAL_DONE_CHECK
  }

  data class PolicyBlock(
    override val workflowId: String?,
    override val issueKey: String,
    override val subtaskId: Int,
    val progress: GoalRunnerWorkflowProgress?,
    val blockedReason: String?,
    val stopReason: String?,
  ) : GoalRunnerLedgerContext {
    override val action: GoalAttemptLedgerAction = GoalAttemptLedgerAction.POLICY_BLOCK
  }

  data class Timeout(
    override val workflowId: String?,
    override val issueKey: String,
    override val subtaskId: Int,
    val progress: GoalRunnerWorkflowProgress?,
    val blockedReason: String?,
    val finalReconciledResult: String?,
    val stopReason: String?,
    val diagnosticClass: String?,
    val recoverableJsonPresent: Boolean?,
    val nextSafeAction: String?,
    val attemptDurationMillis: Long?,
    val causingLoopEntry: String?,
    val reAttemptCause: String?,
    val findingsInScope: Int?,
  ) : GoalRunnerLedgerContext {
    override val action: GoalAttemptLedgerAction = GoalAttemptLedgerAction.TIMEOUT

    constructor(values: StoppedLedgerContextValues) : this(
      values.workflowId,
      values.issueKey,
      values.subtaskId,
      values.progress,
      values.blockedReason,
      values.finalReconciledResult,
      values.stopReason,
      values.diagnosticClass,
      values.recoverableJsonPresent,
      values.nextSafeAction,
      values.attemptDurationMillis,
      values.causingLoopEntry,
      values.reAttemptCause,
      values.findingsInScope,
    )
  }

  data class Interruption(
    override val workflowId: String?,
    override val issueKey: String,
    override val subtaskId: Int,
    val progress: GoalRunnerWorkflowProgress?,
    val blockedReason: String?,
    val finalReconciledResult: String?,
    val stopReason: String?,
    val diagnosticClass: String?,
    val recoverableJsonPresent: Boolean?,
    val nextSafeAction: String?,
    val attemptDurationMillis: Long?,
    val causingLoopEntry: String?,
    val reAttemptCause: String?,
    val findingsInScope: Int?,
  ) : GoalRunnerLedgerContext {
    override val action: GoalAttemptLedgerAction = GoalAttemptLedgerAction.INTERRUPTION

    constructor(values: StoppedLedgerContextValues) : this(
      values.workflowId,
      values.issueKey,
      values.subtaskId,
      values.progress,
      values.blockedReason,
      values.finalReconciledResult,
      values.stopReason,
      values.diagnosticClass,
      values.recoverableJsonPresent,
      values.nextSafeAction,
      values.attemptDurationMillis,
      values.causingLoopEntry,
      values.reAttemptCause,
      values.findingsInScope,
    )
  }

  data class FinalReconciledOutcome(
    override val workflowId: String?,
    override val issueKey: String,
    override val subtaskId: Int,
    val progress: GoalRunnerWorkflowProgress?,
    val blockedReason: String?,
    val finalReconciledResult: String?,
    val stopReason: String?,
    val diagnosticClass: String?,
    val recoverableJsonPresent: Boolean?,
    val nextSafeAction: String?,
    val attemptDurationMillis: Long?,
    val causingLoopEntry: String?,
    val reAttemptCause: String?,
    val findingsInScope: Int?,
  ) : GoalRunnerLedgerContext {
    override val action: GoalAttemptLedgerAction = GoalAttemptLedgerAction.FINAL_RECONCILED_OUTCOME

    constructor(values: StoppedLedgerContextValues) : this(
      values.workflowId,
      values.issueKey,
      values.subtaskId,
      values.progress,
      values.blockedReason,
      values.finalReconciledResult,
      values.stopReason,
      values.diagnosticClass,
      values.recoverableJsonPresent,
      values.nextSafeAction,
      values.attemptDurationMillis,
      values.causingLoopEntry,
      values.reAttemptCause,
      values.findingsInScope,
    )
  }

  data class DiagnosticInspection(
    override val workflowId: String?,
    override val issueKey: String,
    override val subtaskId: Int,
    val progress: GoalRunnerWorkflowProgress?,
  ) : GoalRunnerLedgerContext {
    override val action: GoalAttemptLedgerAction = GoalAttemptLedgerAction.DIAGNOSTIC_INSPECTION
  }

  data class BackwardEdgeEntry(
    override val workflowId: String?,
    override val issueKey: String,
    override val subtaskId: Int,
    val progress: GoalRunnerWorkflowProgress?,
    val loopId: String,
    val cumulativeLoopCount: Int,
  ) : GoalRunnerLedgerContext {
    override val action: GoalAttemptLedgerAction = GoalAttemptLedgerAction.BACKWARD_EDGE_ENTRY
  }
}

private data class GoalRunnerLedgerDetails(
  val progress: GoalRunnerWorkflowProgress? = null,
  val launchOutcome: AgentRunLaunchOutcome? = null,
  val blockedReason: String? = null,
  val finalReconciledResult: String? = null,
  val stopReason: String? = null,
  val diagnosticClass: String? = null,
  val recoverableJsonPresent: Boolean? = null,
  val nextSafeAction: String? = null,
  val loopId: String? = null,
  val cumulativeLoopCount: Int? = null,
  val attemptDurationMillis: Long? = null,
  val causingLoopEntry: String? = null,
  val reAttemptCause: String? = null,
  val findingsInScope: Int? = null,
)

private fun GoalRunnerLedgerContext.details(): GoalRunnerLedgerDetails =
  when (this) {
    is GoalRunnerLedgerContext.ChildActivation -> detailsForLedger()
    is GoalRunnerLedgerContext.Resume -> detailsForLedger()
    is GoalRunnerLedgerContext.Retry -> detailsForLedger()
    is GoalRunnerLedgerContext.TerminalDoneCheck ->
      GoalRunnerLedgerDetails(
        progress = progress,
        finalReconciledResult = finalReconciledResult,
        attemptDurationMillis = attemptDurationMillis,
      )
    is GoalRunnerLedgerContext.PolicyBlock ->
      GoalRunnerLedgerDetails(
        progress = progress,
        blockedReason = blockedReason,
        stopReason = stopReason,
      )
    is GoalRunnerLedgerContext.Timeout -> detailsForLedger()
    is GoalRunnerLedgerContext.Interruption -> detailsForLedger()
    is GoalRunnerLedgerContext.FinalReconciledOutcome -> detailsForLedger()
    is GoalRunnerLedgerContext.DiagnosticInspection -> GoalRunnerLedgerDetails(progress = progress)
    is GoalRunnerLedgerContext.BackwardEdgeEntry ->
      GoalRunnerLedgerDetails(
        progress = progress,
        loopId = loopId,
        cumulativeLoopCount = cumulativeLoopCount,
      )
  }

private fun GoalRunnerLedgerContext.ChildActivation.detailsForLedger(): GoalRunnerLedgerDetails =
  GoalRunnerLedgerDetails(
    progress = progress,
    launchOutcome = launchOutcome,
    diagnosticClass = diagnosticClass,
    recoverableJsonPresent = recoverableJsonPresent,
    nextSafeAction = nextSafeAction,
    causingLoopEntry = causingLoopEntry,
    reAttemptCause = reAttemptCause,
  )

private fun GoalRunnerLedgerContext.Resume.detailsForLedger(): GoalRunnerLedgerDetails =
  GoalRunnerLedgerDetails(
    progress = progress,
    launchOutcome = launchOutcome,
    diagnosticClass = diagnosticClass,
    recoverableJsonPresent = recoverableJsonPresent,
    nextSafeAction = nextSafeAction,
    causingLoopEntry = causingLoopEntry,
    reAttemptCause = reAttemptCause,
  )

private fun GoalRunnerLedgerContext.Retry.detailsForLedger(): GoalRunnerLedgerDetails =
  GoalRunnerLedgerDetails(
    progress = progress,
    blockedReason = blockedReason,
    finalReconciledResult = finalReconciledResult,
    stopReason = stopReason,
    diagnosticClass = diagnosticClass,
    recoverableJsonPresent = recoverableJsonPresent,
    nextSafeAction = nextSafeAction,
    attemptDurationMillis = attemptDurationMillis,
    causingLoopEntry = causingLoopEntry,
    reAttemptCause = reAttemptCause,
    findingsInScope = findingsInScope,
  )

private fun GoalRunnerLedgerContext.Timeout.detailsForLedger(): GoalRunnerLedgerDetails =
  GoalRunnerLedgerDetails(
    progress = progress,
    blockedReason = blockedReason,
    finalReconciledResult = finalReconciledResult,
    stopReason = stopReason,
    diagnosticClass = diagnosticClass,
    recoverableJsonPresent = recoverableJsonPresent,
    nextSafeAction = nextSafeAction,
    attemptDurationMillis = attemptDurationMillis,
    causingLoopEntry = causingLoopEntry,
    reAttemptCause = reAttemptCause,
    findingsInScope = findingsInScope,
  )

private fun GoalRunnerLedgerContext.Interruption.detailsForLedger(): GoalRunnerLedgerDetails =
  GoalRunnerLedgerDetails(
    progress = progress,
    blockedReason = blockedReason,
    finalReconciledResult = finalReconciledResult,
    stopReason = stopReason,
    diagnosticClass = diagnosticClass,
    recoverableJsonPresent = recoverableJsonPresent,
    nextSafeAction = nextSafeAction,
    attemptDurationMillis = attemptDurationMillis,
    causingLoopEntry = causingLoopEntry,
    reAttemptCause = reAttemptCause,
    findingsInScope = findingsInScope,
  )

private fun GoalRunnerLedgerContext.FinalReconciledOutcome.detailsForLedger(): GoalRunnerLedgerDetails =
  GoalRunnerLedgerDetails(
    progress = progress,
    blockedReason = blockedReason,
    finalReconciledResult = finalReconciledResult,
    stopReason = stopReason,
    diagnosticClass = diagnosticClass,
    recoverableJsonPresent = recoverableJsonPresent,
    nextSafeAction = nextSafeAction,
    attemptDurationMillis = attemptDurationMillis,
    causingLoopEntry = causingLoopEntry,
    reAttemptCause = reAttemptCause,
    findingsInScope = findingsInScope,
  )
