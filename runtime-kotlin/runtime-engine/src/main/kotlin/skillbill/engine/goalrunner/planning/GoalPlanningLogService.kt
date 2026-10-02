package skillbill.engine.goalrunner.planning

import me.tatarka.inject.annotations.Inject
import skillbill.application.diagnostics.RejectedOutputDiagnosticService
import skillbill.application.diagnostics.model.RejectedOutputDiagnosticSelection
import skillbill.application.getOrElseUnlessCooperative
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore
import skillbill.engine.goalrunner.planning.attempt.diagnosticPhaseId
import skillbill.engine.goalrunner.planning.model.GoalPlanningAttemptOutcome
import skillbill.engine.goalrunner.planning.model.GoalPlanningLog
import skillbill.engine.goalrunner.planning.model.GoalPlanningLogAttempt
import skillbill.engine.goalrunner.planning.model.GoalPlanningLogRequest
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RejectedOutputDiagnosticMetadataValidator
import skillbill.ports.diagnostics.model.RejectedOutputDiagnostic
import skillbill.ports.diagnostics.model.RejectedOutputDiagnosticSelector
import skillbill.workflow.model.goalobservability.GoalProgressEvent
import skillbill.workflow.model.goalobservability.GoalProgressEventKind
import skillbill.workflow.model.goalobservability.GoalProgressOutcome
import java.time.Clock
import java.time.Instant

private const val GOAL_PLANNING_WORKFLOW_PHASE = "goal_planning"
private const val OPERATION_NAME_SEGMENTS = 4
private const val OPERATION_PHASE_INDEX = 0
private const val OPERATION_SUBTASK_INDEX = 1
private const val OPERATION_LITERAL_INDEX = 2
private const val OPERATION_ATTEMPT_INDEX = 3

@Inject
class GoalPlanningLogService(
  private val manifestStore: GoalRunnerManifestStore,
  private val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  private val database: DatabaseSessionFactory,
  private val diagnosticMetadataValidator: RejectedOutputDiagnosticMetadataValidator,
  private val clock: Clock,
) {
  fun log(request: GoalPlanningLogRequest): GoalPlanningLog {
    val parentWorkflowId =
      manifestStore
        .readByIssueKey(request.issueKey, request.repoRoot)
        ?.parentWorkflowId
        ?: return GoalPlanningLog(request.issueKey, null)

    val events =
      outcomeStore.progressEvents(parentWorkflowId)
        .filter { event -> event.workflowPhase == GOAL_PLANNING_WORKFLOW_PHASE }
    val rejections = readRejections(parentWorkflowId)

    val attempts =
      assembleAttempts(events, rejections)
        .filter { attempt -> request.subtaskId == null || attempt.subtaskId == request.subtaskId }
        .filter { attempt -> !request.failuresOnly || attempt.outcome == GoalPlanningAttemptOutcome.FAILED }

    return GoalPlanningLog(
      issueKey = request.issueKey,
      parentWorkflowId = parentWorkflowId,
      attempts = attempts,
    )
  }

  private fun readRejections(parentWorkflowId: String): Map<String, RejectedOutputDiagnostic> =
    runCatching {
      database.transaction { unitOfWork ->
        RejectedOutputDiagnosticService(
          unitOfWork.rejectedOutputDiagnostics,
          unitOfWork.rejectedOutputDiagnosticPermissions,
          diagnosticMetadataValidator,
          clock = clock,
        )
          .inspect(RejectedOutputDiagnosticSelector(workflowId = parentWorkflowId))
      }
    }
      .getOrElseUnlessCooperative { RejectedOutputDiagnosticSelection.Selected(emptyList()) }
      .let { selection ->
        when (selection) {
          is RejectedOutputDiagnosticSelection.Selected -> selection.diagnostics
          is RejectedOutputDiagnosticSelection.InvalidRequest -> emptyList()
        }
      }
      .associateBy { record -> rejectionKey(record.phaseId, record.attempt) }

  private fun assembleAttempts(
    events: List<GoalProgressEvent>,
    rejections: Map<String, RejectedOutputDiagnostic>,
  ): List<GoalPlanningLogAttempt> {
    val occurrences = mutableListOf<AttemptOccurrence>()
    val open = mutableMapOf<String, MutableList<AttemptOccurrence>>()

    events.forEach { event ->
      val operation = event.operationName?.takeIf(String::isNotBlank) ?: return@forEach
      val eventKind = event.eventKind
      when (eventKind) {
        GoalProgressEventKind.OPERATION_STARTED -> {
          val occurrence = AttemptOccurrence(operation, event.timestamp)
          occurrences += occurrence
          open.getOrPut(operation) { mutableListOf() } += occurrence
        }

        GoalProgressEventKind.OPERATION_COMPLETED -> {
          val pending =
            open[operation]?.removeLastOrNull()
              ?: AttemptOccurrence(operation, startedAt = null).also { occurrences += it }
          pending.settle(event.timestamp, outcomeWire(event.outcome))
        }

        GoalProgressEventKind.PHASE_STARTED,
        GoalProgressEventKind.PHASE_COMPLETED,
        GoalProgressEventKind.OPERATION_HEARTBEAT,
        -> Unit
      }
    }

    return occurrences.mapNotNull { occurrence ->
      val parsed = parseOperation(occurrence.operation) ?: return@mapNotNull null
      val rejection = rejections[rejectionKey(parsed.diagnosticPhaseId, parsed.attempt)]
      GoalPlanningLogAttempt(
        phaseId = parsed.diagnosticPhaseId,
        subtaskId = parsed.subtaskId,
        attempt = parsed.attempt,
        startedAt = occurrence.startedAt,
        finishedAt = occurrence.finishedAt,
        outcome = occurrence.outcome,
        rule = rejection?.rule,
        reason = rejection?.reason,
        agentId = rejection?.agentId,
        rejectedOutputIdentity = rejection?.identity,
        rejectedOutputBytes = rejection?.byteSize,
      )
    }.sortedBy { attempt -> attempt.startedAt ?: attempt.finishedAt ?: Instant.EPOCH }
  }

  private class AttemptOccurrence(val operation: String, val startedAt: Instant?) {
    var finishedAt: Instant? = null
      private set
    var outcome: GoalPlanningAttemptOutcome = GoalPlanningAttemptOutcome.IN_FLIGHT
      private set

    fun settle(
      finishedAt: Instant?,
      outcome: String?,
    ) {
      this.finishedAt = finishedAt

      this.outcome = outcome?.let(GoalPlanningAttemptOutcome::fromWire) ?: GoalPlanningAttemptOutcome.IN_FLIGHT
    }
  }

  private fun outcomeWire(outcome: GoalProgressOutcome): String? =
    if (outcome == GoalProgressOutcome.NONE) null else outcome.wireValue

  private data class ParsedOperation(val diagnosticPhaseId: String, val subtaskId: Int, val attempt: Int)

  private fun parseOperation(operation: String): ParsedOperation? {
    val parts = operation.split(":")
    if (parts.size != OPERATION_NAME_SEGMENTS || parts[OPERATION_LITERAL_INDEX] != "attempt") return null
    val subtaskId = parts[OPERATION_SUBTASK_INDEX].toIntOrNull() ?: return null
    val attempt = parts[OPERATION_ATTEMPT_INDEX].toIntOrNull() ?: return null
    val phase = parts[OPERATION_PHASE_INDEX]
    return ParsedOperation(if (subtaskId == 0) phase else "$phase:$subtaskId", subtaskId, attempt)
  }

  private fun rejectionKey(
    phaseId: String,
    attempt: Int,
  ): String = "$phaseId#$attempt"
}
