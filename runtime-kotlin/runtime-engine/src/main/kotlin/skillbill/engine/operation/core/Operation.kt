package skillbill.engine.operation.core

import java.nio.file.Path

/**
 * One runtime operation: a non-feature-task command with its own wire id.
 *
 * The executor runs [pre], then [run], then [post]. Pre and post are in-process. Run is a sequence of steps; an
 * agent step runs only through [OperationContext.steps], which launches through the generic `PhaseRunner` under a
 * step name local to the operation, never a skeleton step id. Run never opens a feature-task workflow and never
 * launches an agent any other way.
 *
 * Wire ids: `update-check` and `release` (SKILL-382 subtask 1). Subtasks 2-4 add the checklist, `pr-review-fix`,
 * and `verify` operations to the same registry.
 *
 * A pre failure throws an `OperationRefusalError` (reported as blocked, nothing changed) or an
 * `OperationUsageError` (reported as a usage error).
 */
interface Operation {
  val id: String

  fun pre(context: OperationContext) = Unit

  fun run(context: OperationContext): OperationRunResult

  fun post(
    context: OperationContext,
    outcome: OperationOutcome,
  ) = Unit
}

/**
 * An operation that requires confirmation. Its [run] returns [OperationRunResult.Proposed]; a later invocation with
 * `confirm:<token>` calls [execute] with exactly the stored proposal instead of running [run] again.
 */
interface ConfirmableOperation : Operation {
  /** Operation-owned anchors as they stand now; confirm refuses a proposal once any of them moved. */
  fun currentAnchors(context: OperationContext): Map<String, String>

  /**
   * Checks the confirm invocation against the stored proposal before the token is consumed; throws a usage or
   * refusal error to reject it with the token still valid.
   */
  fun admit(
    context: OperationContext,
    proposal: ConfirmedOperationProposal,
  ) = Unit

  fun execute(
    context: OperationContext,
    proposal: ConfirmedOperationProposal,
  ): OperationOutcome
}

/**
 * An operation whose own durable workflow holds the pending proposal. The token is that workflow's id, so [run]
 * reads `confirm:<token>` itself and the confirmation gate stores nothing.
 */
interface SelfConfirmingOperation : Operation

data class OperationContext(
  val invocationId: String,
  val repoRoot: Path,
  val invokedAgentId: String?,
  val arguments: OperationArguments,
  val instructions: String?,
  val steps: OperationStepRunner,
) {
  val confirming: Boolean get() = arguments.confirm != null
}

data class OperationArguments(
  val bump: String? = null,
  val confirm: String? = null,
  val select: String? = null,
  val mode: String? = null,
  val scope: String? = null,
  val push: String? = null,
  val replies: String? = null,
  val spec: String? = null,
  val target: String? = null,
)

sealed interface OperationRunResult {
  data class Finished(val outcome: OperationOutcome) : OperationRunResult

  /**
   * A proposal awaiting confirmation. [value] is stored and executed verbatim on confirm; [operationValues] holds the
   * operation's anchors plus any value it pins for confirm.
   */
  data class Proposed(
    val value: String,
    val summary: String,
    val operationValues: Map<String, String>,
  ) : OperationRunResult
}

data class ConfirmedOperationProposal(
  val token: String,
  val value: String,
  val operationValues: Map<String, String>,
)
