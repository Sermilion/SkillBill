package skillbill.engine.goalrunner.plan

import me.tatarka.inject.annotations.Inject
import skillbill.engine.goal.goalRepositoryIdentity
import skillbill.idestatus.model.IdeStatusLaunchScope
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.goalrunner.model.GoalPlanningOwnership
import skillbill.ports.goalrunner.model.GoalPlanningOwnershipWriteResult
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.goalworktree.GoalWorktreeResources
import skillbill.ports.workflow.goalworktree.model.GoalWorktreeResult
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.model.FeatureTaskExecutionIdentityPolicy
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.UUID

internal sealed interface GoalPlanningLaunch {
  data class Ready(val launch: StandalonePlanLaunch) : GoalPlanningLaunch

  data class Refused(val reason: String, val workflowId: String? = null) : GoalPlanningLaunch
}

internal data class GoalPlanningSelection(
  val existingPlan: Boolean,
  val selectedWorkflowId: String?,
  val supersedableWorkflowId: String?,
)

@Inject
public class GoalPlanningCheckoutOwnership(
  private val database: DatabaseSessionFactory,
  private val repositories: RepositoryEnclosingRootPort,
  private val worktrees: GoalWorktreeResources,
  private val gitOperations: WorkflowGitOperations,
) {
  internal fun prepare(
    issueKey: String,
    repoRoot: Path,
    launch: StandalonePlanLaunch,
    selection: GoalPlanningSelection,
  ): GoalPlanningLaunch {
    if (launch.scope != IdeStatusLaunchScope.GOAL) return GoalPlanningLaunch.Ready(launch)
    val selectedWorkflowId = selection.selectedWorkflowId
    val repositoryIdentity = goalRepositoryIdentity(repoRoot, repositories)
    val normalizedIssue = FeatureTaskExecutionIdentityPolicy.canonicalIssueKey(issueKey)
    val ownership =
      database.read { unit ->
        selectedWorkflowId?.let { workflowId -> unit.goalPlanningOwnership.findByWorkflow(workflowId) }
          ?: unit.goalPlanningOwnership.findByIssue(repositoryIdentity, normalizedIssue)
      }
    if (ownership != null) {
      resolveExistingOwner(issueKey, repoRoot, launch, selection, ownership)?.let { return it }
    }
    return reserveAndAcquire(issueKey, repoRoot, launch, selectedWorkflowId)
  }

  private fun resolveExistingOwner(
    issueKey: String,
    repoRoot: Path,
    launch: StandalonePlanLaunch,
    selection: GoalPlanningSelection,
    ownership: GoalPlanningOwnership,
  ): GoalPlanningLaunch? =
    when (ownership.state) {
      GoalPlanningOwnership.State.SUPERSEDED ->
        resolveSuperseded(issueKey, repoRoot, launch, selection, ownership)
      GoalPlanningOwnership.State.TRANSFERRED ->
        if (selection.selectedWorkflowId == ownership.workflowId ||
          (selection.selectedWorkflowId == null && !workflowExists(ownership.workflowId))
        ) {
          existingOwner(issueKey, repoRoot, launch, ownership)
        } else if (selection.existingPlan) {
          GoalPlanningLaunch.Ready(launch)
        } else {
          null
        }
      else -> resolveActiveOwner(issueKey, repoRoot, launch, selection, ownership)
    }

  private fun resolveSuperseded(
    issueKey: String,
    repoRoot: Path,
    launch: StandalonePlanLaunch,
    selection: GoalPlanningSelection,
    ownership: GoalPlanningOwnership,
  ): GoalPlanningLaunch? {
    if (selection.selectedWorkflowId != ownership.workflowId) {
      return if (selection.existingPlan) GoalPlanningLaunch.Ready(launch) else null
    }
    val result =
      database.transaction {
        it.goalPlanningOwnership.reactivate(ownership.reservationId, ownership.workflowId, ownership.ownedPath)
      }
    return if (result is GoalPlanningOwnershipWriteResult.Stored) {
      existingOwner(issueKey, repoRoot, launch, result.ownership)
    } else {
      refused(
        "Planning workflow ${ownership.workflowId} no longer owns ${ownership.ownedPath}. Preserve that path " +
          "and resolve the competing ownership record before resuming it.",
        ownership.workflowId,
      )
    }
  }

  private fun resolveActiveOwner(
    issueKey: String,
    repoRoot: Path,
    launch: StandalonePlanLaunch,
    selection: GoalPlanningSelection,
    ownership: GoalPlanningOwnership,
  ): GoalPlanningLaunch? {
    val canResume =
      selection.selectedWorkflowId == ownership.workflowId ||
        (selection.selectedWorkflowId == null && isUnlaunchedReservation(ownership))
    if (canResume) return existingOwner(issueKey, repoRoot, launch, ownership)
    if (selection.supersedableWorkflowId != ownership.workflowId) {
      return refused(
        "Planning checkout ${ownership.ownedPath} is reserved for workflow ${ownership.workflowId}. Preserve it " +
          "and resume that recorded workflow before starting another plan.",
        ownership.workflowId,
      )
    }
    val result =
      database.transaction {
        it.goalPlanningOwnership.supersede(ownership.reservationId, ownership.workflowId, ownership.ownedPath)
      }
    return if (result is GoalPlanningOwnershipWriteResult.Stored) {
      null
    } else {
      refused(
        "Planning workflow ${ownership.workflowId} still owns ${ownership.ownedPath}. Preserve it and retry " +
          "after its agent has stopped.",
        ownership.workflowId,
      )
    }
  }

  private fun isUnlaunchedReservation(owner: GoalPlanningOwnership): Boolean =
    owner.state == GoalPlanningOwnership.State.RESERVED || !workflowExists(owner.workflowId)

  private fun workflowExists(workflowId: String): Boolean =
    database.read { it.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId) != null }

  private fun existingOwner(
    issueKey: String,
    repoRoot: Path,
    launch: StandalonePlanLaunch,
    ownership: GoalPlanningOwnership,
  ): GoalPlanningLaunch {
    if (ownership.state == GoalPlanningOwnership.State.RESERVED) {
      reconcileReservation(issueKey, repoRoot, ownership)?.let { return it }
    } else if (!Files.isDirectory(ownership.ownedPath)) {
      return refused(
        "Owned planning checkout ${ownership.ownedPath} is missing and its work cannot be proven recoverable. " +
          "Preserve the ownership record and restore or inspect that path before retrying.",
        ownership.workflowId,
      )
    } else {
      when (
        val access =
          worktrees.createOrAdopt(
            repoRoot,
            issueKey,
            ownership.ownedPath,
            ownership.initialCommit,
            ownership,
          )
      ) {
        is GoalWorktreeResult.Failed ->
          return refused(
            "Could not access owned planning checkout ${ownership.ownedPath}: ${access.reason} " +
              "Preserve the path and inspect its Git worktree registration.",
            ownership.workflowId,
          )
        else -> Unit
      }
    }
    return GoalPlanningLaunch.Ready(
      launch.copy(executionRoot = ownership.ownedPath, workflowIdForCreation = ownership.workflowId),
    )
  }

  private fun reconcileReservation(
    issueKey: String,
    repoRoot: Path,
    ownership: GoalPlanningOwnership,
  ): GoalPlanningLaunch.Refused? {
    val created = worktrees.createOrAdopt(repoRoot, issueKey, ownership.ownedPath, ownership.initialCommit, ownership)
    if (created is GoalWorktreeResult.Failed) {
      return refused(
        "Could not reconcile reserved planning checkout ${ownership.ownedPath}: ${created.reason} " +
          "Preserve the path and rerun after resolving the Git worktree state.",
        ownership.workflowId,
      )
    }
    reservedCheckoutProblem(ownership)?.let { return refused(it, ownership.workflowId) }
    val gitWorktreeIdentity =
      when (created) {
        is GoalWorktreeResult.Created -> created.gitWorktreeIdentity
        is GoalWorktreeResult.Adopted -> created.gitWorktreeIdentity
        is GoalWorktreeResult.Failed -> null
      }
        ?: return refused(
          "Reserved planning checkout ${ownership.ownedPath} has no verifiable Git worktree identity. " +
            "Preserve it and inspect its registration before retrying.",
          ownership.workflowId,
        )
    val initialized =
      database.transaction {
        it.goalPlanningOwnership.markInitialized(ownership.reservationId, ownership.ownedPath, gitWorktreeIdentity)
      }
    return when (initialized) {
      is GoalPlanningOwnershipWriteResult.Stored -> null
      else ->
        refused(
          "Could not mark planning checkout ${ownership.ownedPath} initialized. Preserve it and retry after " +
            "checking the ownership record.",
          ownership.workflowId,
        )
    }
  }

  private fun reservedCheckoutProblem(ownership: GoalPlanningOwnership): String? {
    val head = gitOperations.headCommitSha(ownership.ownedPath)
    if (head !is WorkflowGitOperationResult.Ok || head.value.trim() != ownership.initialCommit) {
      return "Reserved planning checkout ${ownership.ownedPath} does not match ${ownership.initialCommit}. " +
        "Preserve it and inspect the Git worktree before retrying."
    }
    val status = gitOperations.worktreeStatus(ownership.ownedPath)
    return if (status !is WorkflowGitOperationResult.Ok || status.value.isNotBlank()) {
      "Reserved planning checkout ${ownership.ownedPath} contains work that cannot be proven recoverable. " +
        "Preserve it and inspect its Git status before retrying."
    } else {
      null
    }
  }

  private fun reserveAndAcquire(
    issueKey: String,
    repoRoot: Path,
    launch: StandalonePlanLaunch,
    selectedWorkflowId: String?,
  ): GoalPlanningLaunch {
    val repositoryIdentity = goalRepositoryIdentity(repoRoot, repositories)
    val normalizedIssue = FeatureTaskExecutionIdentityPolicy.canonicalIssueKey(issueKey)
    val revision = gitOperations.headCommitSha(repoRoot)
    if (revision !is WorkflowGitOperationResult.Ok || revision.value.isBlank()) {
      val reason = (revision as? WorkflowGitOperationResult.Failed)?.error ?: "Git returned a blank revision"
      return refused(
        "Could not read the initial commit for planning checkout ${repoRoot.toAbsolutePath().normalize()}: " +
          "$reason. Resolve Git access and retry.",
      )
    }
    val reservationId = UUID.randomUUID().toString()
    val path = worktrees.plannedPath(repoRoot, issueKey, reservationId).toAbsolutePath().normalize()
    if (path == repoRoot.toAbsolutePath().normalize()) {
      return GoalPlanningLaunch.Ready(launch.copy(executionRoot = path))
    }
    if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
      return refused(
        "Planning checkout path $path already exists without an ownership record. Preserve it and move it aside " +
          "only after identifying its owner.",
      )
    }
    val reservation =
      GoalPlanningOwnership(
        repositoryIdentity = repositoryIdentity,
        normalizedIssueKey = normalizedIssue,
        workflowId =
          selectedWorkflowId ?: "${WorkflowFamily.TASK_RUNTIME.definition.workflowIdPrefix}-${UUID.randomUUID()}",
        reservationId = reservationId,
        ownedPath = path,
        initialCommit = revision.value.trim(),
        state = GoalPlanningOwnership.State.RESERVED,
      )
    return when (val stored = database.transaction { it.goalPlanningOwnership.reserve(reservation) }) {
      is GoalPlanningOwnershipWriteResult.Stored -> existingOwner(issueKey, repoRoot, launch, stored.ownership)
      is GoalPlanningOwnershipWriteResult.Conflict -> {
        val owner = stored.ownership
        refused(
          "Another planning launch owns ${owner?.ownedPath ?: path}. Preserve that path and resume its recorded " +
            "workflow before starting a competing launch.",
          owner?.workflowId,
        )
      }
      is GoalPlanningOwnershipWriteResult.Missing ->
        refused(
          "Could not reserve planning checkout ${stored.path}. Preserve the path and retry after checking the " +
            "ownership database.",
        )
    }
  }

  private fun refused(
    reason: String,
    workflowId: String? = null,
  ) = GoalPlanningLaunch.Refused(reason, workflowId)
}
