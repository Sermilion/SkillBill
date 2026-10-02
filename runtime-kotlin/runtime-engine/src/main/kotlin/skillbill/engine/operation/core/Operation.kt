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
 * [pre] returns an [OperationRefusal]. A blocked refusal is reported as blocked with nothing changed; a usage refusal
 * is reported as a usage error. `null` means proceed.
 */
interface Operation {
  val id: String

  fun pre(context: OperationContext): OperationRefusal? = null

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
  fun currentAnchors(context: OperationContext): CurrentOperationAnchors

  /**
   * Checks the confirm invocation against the stored proposal before the token is consumed; returns a refusal to
   * reject it with the token still valid.
   */
  fun admit(
    context: OperationContext,
    proposal: ConfirmedOperationProposal,
  ): OperationRefusal? = null

  fun execute(
    context: OperationContext,
    proposal: ConfirmedOperationProposal,
  ): OperationOutcome
}

/** The operation-owned anchors read for a confirm check, or the refusal that explains why they could not be read. */
sealed interface CurrentOperationAnchors {
  data class Read(val values: Map<String, String>) : CurrentOperationAnchors

  data class Unreadable(val refusal: OperationOutcome.Blocked) : CurrentOperationAnchors
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
  val includePrereleases: Boolean = false,
  val format: OperationOutputFormat = OperationOutputFormat.TEXT,
)

enum class OperationOutputFormat(val wireValue: String) {
  TEXT("text"),
  JSON("json"),
}

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
