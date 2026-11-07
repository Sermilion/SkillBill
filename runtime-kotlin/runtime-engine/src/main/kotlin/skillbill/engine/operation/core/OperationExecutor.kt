package skillbill.engine.operation.core

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.operationcard.OperationLaunchContext
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
  val launchId: String? = null,
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
  private val cards: OperationCardLifecycle,
) {
  public fun execute(request: OperationRequest): OperationResult {
    val invocationId = "$INVOCATION_ID_PREFIX${UUID.randomUUID()}"
    return OperationResult(invocationId, outcomeOf(request, invocationId))
  }

  private fun outcomeOf(
    request: OperationRequest,
    invocationId: String,
  ): OperationOutcome {
    val operation =
      registry.find(request.operationId)
        ?: return OperationOutcome.Usage(
          "Unknown operation '${request.operationId}'; expected one of ${registry.ids.joinToString(", ")}.",
        )
    val token = request.arguments.confirm
    if (token != null && operation !is ConfirmableOperation && operation !is SelfConfirmingOperation) {
      return OperationOutcome.Usage("Operation '${operation.id}' takes no confirm: token.")
    }
    val launch =
      when (val resolution = cards.resolve(request, request.repoRoot)) {
        is OperationLaunchResolution.Refused -> return resolution.outcome
        is OperationLaunchResolution.Bound -> resolution.launch
        OperationLaunchResolution.Unbound -> null
      }
    return runResolved(operation, request, invocationId, launch)
  }

  private fun runResolved(
    operation: Operation,
    request: OperationRequest,
    invocationId: String,
    launch: OperationLaunchContext?,
  ): OperationOutcome {
    val token = request.arguments.confirm
    val context =
      OperationContext(
        invocationId = invocationId,
        repoRoot = request.repoRoot,
        projectStoreRoot = projectStore.resolve(request.repoRoot).root,
        invokedAgentId = request.invokedAgentId,
        arguments = request.arguments,
        instructions = request.instructions,
        steps = steps,
        desktopLaunchId = launch?.launchId,
      )
    if (token != null && launch != null && !cards.claim(token, launch.launchId, invocationId)) {
      return OperationOutcome.Blocked("Operation confirmation '$token' is already claimed or no longer admitted.")
    }
    val outcome =
      (operation as? OperationPrecheck)?.pre(context)
        ?: when {
          token != null && operation is ConfirmableOperation -> gate.confirm(operation, context, token)
          token != null && operation is SelfConfirmingOperation ->
            gate.confirmBound(operation, context, token) { proceed(operation, context) }
          else -> proceed(operation, context)
        }
    val published = publishCard(operation, context, launch, outcome)
    val released =
      if (published is OperationOutcome.AwaitingConfirmation) {
        null
      } else {
        cards.settle(launch, token, invocationId, published)
      }
    return settleBranchWorktree(request, published, launch, released)
  }

  private fun publishCard(
    operation: Operation,
    context: OperationContext,
    launch: OperationLaunchContext?,
    outcome: OperationOutcome,
  ): OperationOutcome {
    if (launch == null || outcome !is OperationOutcome.AwaitingConfirmation) return outcome
    val authority =
      if (operation is ConfirmableOperation) {
        OperationAuthority.Stored
      } else {
        OperationAuthority.Bind(operation.id, context)
      }
    return cards.publish(launch, context.invocationId, outcome, authority)
  }

  private fun settleBranchWorktree(
    request: OperationRequest,
    outcome: OperationOutcome,
    launch: OperationLaunchContext?,
    launchRelease: BranchWorktreeRelease?,
  ): OperationOutcome {
    if (outcome !is OperationOutcome.Completed) return outcome
    val holder = BranchWorktreeHolder(BranchWorktreeHolderKind.OPERATION, request.operationId)
    val release = if (launch != null) launchRelease else branchWorktrees.release(request.repoRoot, holder)
    return when (val released = release) {
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
