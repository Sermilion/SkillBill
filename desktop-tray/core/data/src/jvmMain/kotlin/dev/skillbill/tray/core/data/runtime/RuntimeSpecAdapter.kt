package dev.skillbill.tray.core.data.runtime

import dev.skillbill.tray.core.common.di.DatabaseSessionScope
import dev.skillbill.tray.core.data.port.ControlRefusal
import dev.skillbill.tray.core.data.port.ControlResult
import dev.skillbill.tray.core.data.port.RuntimeSpecGateway
import dev.skillbill.tray.core.data.port.SpecPurgeOutcome
import dev.skillbill.tray.core.domain.model.SpecBundleControl
import dev.skillbill.tray.core.domain.model.SpecCounts
import dev.skillbill.tray.core.domain.model.SpecFileKind
import dev.skillbill.tray.core.domain.model.SpecGroup
import dev.skillbill.tray.core.domain.model.SpecNode
import dev.skillbill.tray.core.domain.model.SpecProject
import dev.skillbill.tray.core.domain.model.SpecRunState
import dev.skillbill.tray.core.domain.model.SpecSnapshot
import dev.skillbill.tray.core.domain.model.SpecStatus
import dev.skillbill.tray.core.domain.model.WorkIdentity
import me.tatarka.inject.annotations.Inject
import skillbill.di.core.RuntimeComponent
import skillbill.engine.goalrunner.model.GoalRunnerPurgeRequest
import skillbill.engine.specbrowser.model.SpecBrowserCounts
import skillbill.engine.specbrowser.model.SpecBrowserFileKind
import skillbill.engine.specbrowser.model.SpecBrowserNode
import skillbill.engine.specbrowser.model.SpecBrowserProject
import skillbill.engine.specbrowser.model.SpecBrowserSnapshot
import skillbill.engine.specbrowser.model.SpecBrowserStatus
import skillbill.engine.specbrowser.model.SpecBrowserStatusGroup
import skillbill.engine.specbrowser.model.SpecBundleRunState
import skillbill.engine.work.control.model.WorkControlResult
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.nio.file.InvalidPathException
import java.nio.file.Path
import skillbill.engine.specbrowser.model.SpecBundleControl as RuntimeSpecBundleControl

/** The runtime's spec browser, goal start and purge, mapped one-to-one into domain types; ordering is the runtime's. */
@Inject
@SingleIn(DatabaseSessionScope::class)
@ContributesBinding(DatabaseSessionScope::class)
class RuntimeSpecAdapter(private val runtime: RuntimeComponent) : RuntimeSpecGateway {
  override fun databasePath(): String? = try {
    runtime.databaseSession.resolveDbPath().toString()
  } catch (_: InvalidPathException) {
    null
  }

  override fun projects(): List<SpecProject> = runtime.specBrowserService.projects().map { it.toDomain() }

  override fun projectFor(repositoryRoot: String): SpecProject =
    runtime.specBrowserService.projectFor(Path.of(repositoryRoot)).toDomain()

  override fun snapshot(projectId: String, preferredRepoRoot: String?): SpecSnapshot =
    runtime.specBrowserService.snapshot(projectId, preferredRepoRoot?.let(Path::of)).toDomain()

  override fun control(projectId: String, bundlePath: String, preferredRepoRoot: String?): SpecBundleControl =
    runtime.specBrowserService.control(projectId, bundlePath, preferredRepoRoot?.let(Path::of)).toDomain()

  override fun startGoal(repoRoot: String, issueKey: String, agentId: String?): ControlResult =
    when (val result = runtime.workControlService.startGoal(Path.of(repoRoot), issueKey, agentId)) {
      WorkControlResult.Accepted -> ControlResult.Accepted
      is WorkControlResult.LaunchFailed -> ControlResult.LaunchFailed(result.reason)
      is WorkControlResult.Rejected -> ControlResult.Rejected(result.reason.name.lowercase(), result.reason.toRefusal())
      else -> ControlResult.Rejected("unexpected_start_result", ControlRefusal.OTHER)
    }

  override fun purgeGoal(repoRoot: String, issueKey: String): SpecPurgeOutcome {
    val result = runtime.goalRunnerStatusService.purge(GoalRunnerPurgeRequest(issueKey, Path.of(repoRoot)))
    return SpecPurgeOutcome(result.refusalReason, result.leftovers)
  }
}

private fun SpecBrowserProject.toDomain() =
  SpecProject(projectId, displayName, specsRoot?.toString().orEmpty(), repoRoot?.toString())

private fun SpecBrowserSnapshot.toDomain() =
  SpecSnapshot(project.toDomain(), roots.map { it.toDomain() }, counts.toDomain(), anyInProgress, failure)

private fun SpecBrowserCounts.toDomain() = SpecCounts(all, active, pending, done)

private fun SpecBrowserNode.toDomain(): SpecNode = when (this) {
  is SpecBrowserNode.Folder -> SpecNode.Folder(
    name = name,
    path = path,
    absolutePath = absolutePath.toString(),
    children = children.map { it.toDomain() },
    bundleCount = bundleCount,
    statusCounts = statusCounts.entries.associate { it.key.toDomain() to it.value },
  )
  is SpecBrowserNode.Bundle -> SpecNode.Bundle(
    name = name,
    path = path,
    absolutePath = absolutePath.toString(),
    issueKey = issueKey,
    title = title,
    status = status.toDomain(),
    group = group.toDomain(),
    subtasksDone = subtasksDone,
    subtasksTotal = subtasksTotal,
    children = children.map { it.toDomain() },
  )
  is SpecBrowserNode.File -> SpecNode.File(
    name = name,
    path = path,
    absolutePath = absolutePath.toString(),
    kind = kind.toDomain(),
    subtaskId = subtaskId,
    subtaskName = subtaskName,
    status = status?.toDomain(),
  )
}

private fun RuntimeSpecBundleControl.toDomain() = SpecBundleControl(
  issueKey = issueKey,
  runState = runState.toDomain(),
  everStarted = everStarted,
  work = workflowId?.let { id -> repositoryIdentity?.let { WorkIdentity(id, it) } },
  canStart = canStart,
  startLabel = startLabel,
  canPause = canPause,
  pauseRequested = pauseRequested,
  canCancel = canCancel,
  purgeVisible = purgeVisible,
  canPurge = canPurge,
  agentId = agentId,
  agentChoices = agentChoices,
  unavailableReason = unavailableReason,
)

private fun SpecBrowserStatus.toDomain(): SpecStatus = when (this) {
  SpecBrowserStatus.COMPLETE -> SpecStatus.COMPLETE
  SpecBrowserStatus.IN_PROGRESS -> SpecStatus.IN_PROGRESS
  SpecBrowserStatus.RUNNING -> SpecStatus.RUNNING
  SpecBrowserStatus.PAUSING -> SpecStatus.PAUSING
  SpecBrowserStatus.PAUSED -> SpecStatus.PAUSED
  SpecBrowserStatus.PENDING -> SpecStatus.PENDING
  SpecBrowserStatus.BLOCKED -> SpecStatus.BLOCKED
  SpecBrowserStatus.CANCELED -> SpecStatus.CANCELED
}

private fun SpecBrowserStatusGroup.toDomain(): SpecGroup = when (this) {
  SpecBrowserStatusGroup.ACTIVE -> SpecGroup.ACTIVE
  SpecBrowserStatusGroup.PENDING -> SpecGroup.PENDING
  SpecBrowserStatusGroup.DONE -> SpecGroup.DONE
}

private fun SpecBrowserFileKind.toDomain(): SpecFileKind = when (this) {
  SpecBrowserFileKind.PARENT_SPEC -> SpecFileKind.PARENT_SPEC
  SpecBrowserFileKind.SUBTASK_SPEC -> SpecFileKind.SUBTASK_SPEC
  SpecBrowserFileKind.OTHER -> SpecFileKind.OTHER
}

private fun SpecBundleRunState.toDomain(): SpecRunState = when (this) {
  SpecBundleRunState.NOT_STARTED -> SpecRunState.NOT_STARTED
  SpecBundleRunState.RUNNING -> SpecRunState.RUNNING
  SpecBundleRunState.PAUSE_REQUESTED -> SpecRunState.PAUSE_REQUESTED
  SpecBundleRunState.PAUSED -> SpecRunState.PAUSED
  SpecBundleRunState.BLOCKED -> SpecRunState.BLOCKED
  SpecBundleRunState.CANCELED -> SpecRunState.CANCELED
  SpecBundleRunState.COMPLETE -> SpecRunState.COMPLETE
  SpecBundleRunState.UNAVAILABLE -> SpecRunState.UNAVAILABLE
}
