package skillbill.engine.operation.core

import me.tatarka.inject.annotations.Inject
import java.nio.file.Path
import java.util.UUID

data class OperationRequest(
  val operationId: String,
  val repoRoot: Path,
  val invokedAgentId: String?,
  val arguments: OperationArguments,
  val instructions: String?,
)

data class OperationResult(
  val invocationId: String,
  val outcome: OperationOutcome,
)

@Inject
class OperationExecutor(
  private val registry: OperationRegistry,
  private val gate: OperationConfirmationGate,
  private val steps: OperationStepRunner,
) {
  fun execute(request: OperationRequest): OperationResult {
    val invocationId = "$INVOCATION_ID_PREFIX${UUID.randomUUID()}"
    val operation =
      registry.find(request.operationId)
        ?: return OperationResult(
          invocationId,
          OperationOutcome.Usage(
            "Unknown operation '${request.operationId}'; expected one of ${registry.ids.joinToString(", ")}.",
          ),
        )
    val token = request.arguments.confirm
    if (token != null && operation !is ConfirmableOperation && operation !is SelfConfirmingOperation) {
      return OperationResult(
        invocationId,
        OperationOutcome.Usage("Operation '${operation.id}' takes no confirm: token."),
      )
    }
    val context =
      OperationContext(
        invocationId = invocationId,
        repoRoot = request.repoRoot,
        invokedAgentId = request.invokedAgentId,
        arguments = request.arguments,
        instructions = request.instructions,
        steps = steps,
      )
    val outcome =
      operation.pre(context)
        ?: if (token != null && operation is ConfirmableOperation) {
          gate.confirm(operation, context, token)
        } else {
          proceed(operation, context)
        }
    if (outcome !is OperationOutcome.Usage) operation.post(context, outcome)
    return OperationResult(invocationId, outcome)
  }

  private fun proceed(
    operation: Operation,
    context: OperationContext,
  ): OperationOutcome =
    when (val result = operation.run(context)) {
      is OperationRunResult.Finished -> result.outcome
      is OperationRunResult.Proposed ->
        (operation as? ConfirmableOperation)?.let { confirmable -> gate.propose(confirmable, context, result) }
          ?: OperationOutcome.Failed("Operation '${operation.id}' proposed a change but takes no confirmation.")
    }
}

private const val INVOCATION_ID_PREFIX = "opr-"
