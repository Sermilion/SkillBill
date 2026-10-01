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
    val operation = registry.get(request.operationId)
    val token = request.arguments.confirm
    if (token != null && operation !is ConfirmableOperation && operation !is SelfConfirmingOperation) {
      throw OperationConfirmationUnsupportedError(operation.id)
    }
    val context =
      OperationContext(
        invocationId = "$INVOCATION_ID_PREFIX${UUID.randomUUID()}",
        repoRoot = request.repoRoot,
        invokedAgentId = request.invokedAgentId,
        arguments = request.arguments,
        instructions = request.instructions,
        steps = steps,
      )
    val outcome =
      try {
        operation.pre(context)
        if (token != null && operation is ConfirmableOperation) {
          gate.confirm(operation, context, token)
        } else {
          proceed(operation, context)
        }
      } catch (refusal: OperationRefusalError) {
        OperationOutcome.Blocked(refusal.message.orEmpty())
      }
    operation.post(context, outcome)
    return OperationResult(context.invocationId, outcome)
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
