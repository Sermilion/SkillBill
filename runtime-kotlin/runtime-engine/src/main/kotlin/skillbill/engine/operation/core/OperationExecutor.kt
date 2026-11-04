package skillbill.engine.operation.core

import me.tatarka.inject.annotations.Inject
import skillbill.ports.projectstore.ProjectStorePort
import skillbill.ports.workflow.branchworktree.BranchWorktreeResources
import skillbill.ports.workflow.branchworktree.model.BranchWorktreeHolder
import skillbill.ports.workflow.branchworktree.model.BranchWorktreeHolderKind
import skillbill.ports.workflow.branchworktree.model.BranchWorktreeRelease
import java.nio.file.Path
import java.util.UUID

public data class OperationRequest(
  val operationId: String,
  val repoRoot: Path,
  val invokedAgentId: String?,
  val arguments: OperationArguments,
  val instructions: String?,
)

public data class OperationResult(
  val invocationId: String,
  val outcome: OperationOutcome,
)

@Inject
public class OperationExecutor(
  private val registry: OperationRegistry,
  private val gate: OperationConfirmationGate,
  private val steps: OperationStepRunner,
  private val projectStore: ProjectStorePort,
  private val branchWorktrees: BranchWorktreeResources,
) {
  public fun execute(request: OperationRequest): OperationResult {
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
        projectStoreRoot = projectStore.resolve(request.repoRoot).root,
        invokedAgentId = request.invokedAgentId,
        arguments = request.arguments,
        instructions = request.instructions,
        steps = steps,
      )
    val outcome =
      (operation as? OperationPrecheck)?.pre(context)
        ?: if (token != null && operation is ConfirmableOperation) {
          gate.confirm(operation, context, token)
        } else {
          proceed(operation, context)
        }
    return OperationResult(invocationId, settleBranchWorktree(request, outcome))
  }

  private fun settleBranchWorktree(
    request: OperationRequest,
    outcome: OperationOutcome,
  ): OperationOutcome {
    if (outcome !is OperationOutcome.Completed) return outcome
    val holder = BranchWorktreeHolder(BranchWorktreeHolderKind.OPERATION, request.operationId)
    return when (val released = branchWorktrees.release(request.repoRoot, holder)) {
      is BranchWorktreeRelease.Kept ->
        outcome.copy(text = "${outcome.text.trimEnd()}\n\nKept worktree ${released.path}: ${released.detail}.")
      else -> outcome
    }
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
