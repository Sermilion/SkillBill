package skillbill.engine.goalrunner.telemetry

import skillbill.engine.goalrunner.model.GoalProgressEventDraft
import skillbill.engine.goalrunner.model.GoalRunnerProgressEventRecordRequest
import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore
import skillbill.error.core.failureCodeLabel
import skillbill.ports.agentrun.model.AgentRunProgressEmission
import skillbill.ports.agentrun.model.AgentRunProgressEmitter
import skillbill.ports.diagnostics.RuntimeDiagnostics
import java.time.Clock
import kotlin.coroutines.cancellation.CancellationException

internal class GoalRunnerProgressEventEmitter(
  private val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  private val resolveWorkflowId: () -> String?,
  private val issueKey: String,
  private val clock: Clock,
  private val diagnostics: RuntimeDiagnostics,
) : AgentRunProgressEmitter {
  override fun emit(emission: AgentRunProgressEmission) {
    val workflowId = resolveEmitWorkflowId() ?: return
    val draft =
      GoalProgressEventDraft(
        eventKind = emission.eventKind,
        workflowId = workflowId,
        workflowPhase = "goal_runner_supervision",
        processAlive = emission.processAlive,
        timestamp = clock.instant(),
        operationName = emission.operationName,
        operationKind = emission.operationKind,
        expectedLong = emission.expectedLong,
        outcome = emission.outcome,
      )
    GoalRunnerBestEffortEmission.record(
      diagnostics = diagnostics,
      write = {
        outcomeStore.recordProgressEvent(
          GoalRunnerProgressEventRecordRequest(
            workflowId = workflowId,
            issueKey = issueKey,
            draft = draft,
          ),
        )
      },
      missingMessage = {
        "Best-effort goal progress emit skipped (workflow not found): " +
          "action='${emission.eventKind.wireValue}' workflowId='$workflowId'"
      },
      failureMessage = { error ->
        "Best-effort goal progress emit failed: action='${emission.eventKind.wireValue}' " +
          "workflowId='$workflowId' errorType='${error.failureCodeLabel() ?: error::class.qualifiedName}' " +
          "message='${GoalRunnerBestEffortEmission.boundedMessage(error.message.orEmpty())}'"
      },
    )
  }

  private fun resolveEmitWorkflowId(): String? =
    try {
      resolveWorkflowId()?.takeIf(String::isNotBlank)
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (interrupted: InterruptedException) {
      Thread.currentThread().interrupt()
      throw interrupted
    }
}
