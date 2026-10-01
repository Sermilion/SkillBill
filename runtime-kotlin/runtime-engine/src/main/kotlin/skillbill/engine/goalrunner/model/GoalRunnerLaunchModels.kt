package skillbill.engine.goalrunner.model

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.agentoutput.topLevelJsonObjectCandidates
import skillbill.goalrunner.goalContinuationTerminalStatus
import skillbill.goalrunner.model.GoalRunnerReconciledOutcome
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerWirePayload
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome

internal data class GoalRunnerLaunchReconciliation(
  val refreshed: GoalRunnerManifestState,
  val reconciled: GoalRunnerReconciledOutcome,
  val launchOutcome: AgentRunLaunchOutcome,
  val diagnostics: GoalRunnerLaunchDiagnostics? = null,
)

internal data class GoalRunnerLaunchDiagnostics(
  val diagnosticClass: String,
  val recoverableJsonPresent: Boolean,
  val nextSafeAction: String,
)

internal data class GoalRunnerMissingResultPrefixRecovery(
  val storedOutcome: GoalRunnerStoredOutcome?,
  val diagnostics: GoalRunnerLaunchDiagnostics,
)

internal data class GoalRunnerMissingResultPrefixCandidate(
  val output: GoalRunnerWirePayload,
  val lastResumableStep: String?,
  val workflowId: String?,
)

internal fun missingResultPrefixDiagnostics(lastResumableStep: String?): GoalRunnerLaunchDiagnostics =
  GoalRunnerLaunchDiagnostics(
    diagnosticClass = "missing_result_prefix",
    recoverableJsonPresent = true,
    nextSafeAction =
      if (lastResumableStep.isNullOrBlank()) {
        "continue_inline"
      } else {
        "resume_from_last_resumable_step"
      },
  )

internal fun missingPrefixRecoveryCandidate(
  reconciled: GoalRunnerReconciledOutcome,
  launchOutcome: AgentRunLaunchOutcome,
): GoalRunnerMissingResultPrefixCandidate? =
  (reconciled as? GoalRunnerReconciledOutcome.Stop)
    ?.takeIf { stop -> stop.reason == GoalRunnerStopReason.NO_TERMINAL_STORE_OUTCOME }
    ?.let { stop ->
      (launchOutcome as? AgentRunLaunchFacts)?.let { facts ->
        terminalJsonObjectWithoutResultPrefix(facts.stdout, facts.stderr)?.let { output ->
          GoalRunnerMissingResultPrefixCandidate(
            output = GoalRunnerWirePayload.from(output),
            lastResumableStep = stop.lastResumableStep,
            workflowId = facts.liveness?.workflowId?.takeIf(String::isNotBlank),
          )
        }
      }
    }

internal fun malformedResultJsonDiagnostics(
  reconciled: GoalRunnerReconciledOutcome,
  launchOutcome: AgentRunLaunchOutcome,
): GoalRunnerLaunchDiagnostics? =
  (reconciled as? GoalRunnerReconciledOutcome.Stop)
    ?.let { launchOutcome as? AgentRunLaunchFacts }
    ?.takeIf { facts -> childOutputHasJsonLikeContent(facts.stdout, facts.stderr) }
    ?.takeIf { facts -> terminalJsonObjectWithoutResultPrefix(facts.stdout, facts.stderr) == null }
    ?.let {
      GoalRunnerLaunchDiagnostics(
        diagnosticClass = "malformed_result_json",
        recoverableJsonPresent = false,
        nextSafeAction = "inspect_child_output_then_resume",
      )
    }

internal fun terminalJsonObjectWithoutResultPrefix(
  stdout: String,
  stderr: String,
): Map<String, Any?>? {
  val combined =
    listOf(stdout, stderr)
      .filter(String::isNotBlank)
      .joinToString("\n")
  val candidate =
    combined
      .takeUnless { it.contains("RESULT:") }
      ?.let(::topLevelJsonObjectCandidates)
      ?.singleOrNull()
  return candidate
    ?.let(JsonCodec::parseObjectOrNull)
    ?.let(JsonCodec::jsonElementToValue)
    ?.let(JsonCodec::anyToStringAnyMap)
    ?.takeIf { it.isImplementationReturnContract() || it.isRuntimeTerminalEnvelope() }
}

internal fun childOutputHasJsonLikeContent(
  stdout: String,
  stderr: String,
): Boolean =
  listOf(stdout, stderr).any { output -> output.contains('{') || output.contains('}') || output.contains("RESULT:") }

fun Map<String, Any?>.isImplementationReturnContract(): Boolean =
  keys.containsAll(
    setOf(
      ImplementationReturnContractPayloadKeys.TASKS_COMPLETED,
      ImplementationReturnContractPayloadKeys.FILES_CREATED,
      ImplementationReturnContractPayloadKeys.FILES_MODIFIED,
      ImplementationReturnContractPayloadKeys.TESTS_WRITTEN,
      ImplementationReturnContractPayloadKeys.PLAN_DEVIATION_NOTES,
      ImplementationReturnContractPayloadKeys.NOTES_FOR_REVIEW,
    ),
  )

fun Map<String, Any?>.isRuntimeTerminalEnvelope(): Boolean =
  goalContinuationTerminalStatus(this[SharedPayloadKeys.STATUS]?.toString()) != null &&
    this[SharedPayloadKeys.WORKFLOW_ID]?.toString().orEmpty().isNotBlank()
