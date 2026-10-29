package dev.skillbill.desktop.core.data.runtime

import dev.skillbill.desktop.core.domain.model.ReadProblem
import dev.skillbill.desktop.core.domain.model.SpecBundleControlView
import dev.skillbill.desktop.core.domain.model.SpecCounts
import dev.skillbill.desktop.core.domain.model.SpecFileKind
import dev.skillbill.desktop.core.domain.model.SpecGroup
import dev.skillbill.desktop.core.domain.model.SpecNode
import dev.skillbill.desktop.core.domain.model.SpecProject
import dev.skillbill.desktop.core.domain.model.SpecRunState
import dev.skillbill.desktop.core.domain.model.SpecStatus
import dev.skillbill.desktop.core.domain.model.SpecTree
import dev.skillbill.desktop.core.domain.model.SpecTreeState
import skillbill.engine.specbrowser.model.SpecBrowserFileKind
import skillbill.engine.specbrowser.model.SpecBrowserNode
import skillbill.engine.specbrowser.model.SpecBrowserProject
import skillbill.engine.specbrowser.model.SpecBrowserSnapshot
import skillbill.engine.specbrowser.model.SpecBrowserStatus
import skillbill.engine.specbrowser.model.SpecBrowserStatusGroup
import skillbill.engine.specbrowser.model.SpecBundleControl
import skillbill.engine.specbrowser.model.SpecBundleRunState

/** The runtime's spec browser mapped one to one, as desktop-tray's `RuntimeSpecAdapter` does; ordering is the runtime's. */
internal fun SpecBrowserProject.toDomain() =
  SpecProject(projectId, displayName, specsRoot?.toString().orEmpty(), repoRoot?.toString())

/** A failure or a missing project is unavailable, no checkout comes next, then a tree without bundles. */
internal fun SpecBrowserSnapshot.toState(): SpecTreeState {
  val root = project.specsRoot
  return when {
    failure != null -> SpecTreeState.Unavailable(ReadProblem.Runtime(failure.orEmpty()))
    root == null -> SpecTreeState.Unavailable(ReadProblem.Runtime("The project ${project.projectId} has no spec folder."))
    project.repoRoot == null -> SpecTreeState.NoCheckout
    counts.all == 0 -> SpecTreeState.NoSpecs(root.toString().takeIf(String::isNotBlank))
    else -> SpecTreeState.Loaded(
      SpecTree(project.toDomain(), roots.map { it.toDomain() }, SpecCounts(counts.all, counts.active, counts.pending, counts.done), anyInProgress),
      storePath = root.toString(),
    )
  }
}

internal fun SpecBundleControl.toDomain() = SpecBundleControlView(
  issueKey = issueKey,
  runState = runState.toDomain(),
  everStarted = everStarted,
  workflowId = workflowId,
  repositoryIdentity = repositoryIdentity,
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

private fun SpecBrowserStatus.toDomain(): SpecStatus = when (this) {
  SpecBrowserStatus.COMPLETE -> SpecStatus.Complete
  SpecBrowserStatus.IN_PROGRESS -> SpecStatus.InProgress
  SpecBrowserStatus.RUNNING -> SpecStatus.Running
  SpecBrowserStatus.PAUSING -> SpecStatus.Pausing
  SpecBrowserStatus.PAUSED -> SpecStatus.Paused
  SpecBrowserStatus.PENDING -> SpecStatus.Pending
  SpecBrowserStatus.BLOCKED -> SpecStatus.Blocked
  SpecBrowserStatus.CANCELED -> SpecStatus.Canceled
}

private fun SpecBrowserStatusGroup.toDomain(): SpecGroup = when (this) {
  SpecBrowserStatusGroup.ACTIVE -> SpecGroup.Active
  SpecBrowserStatusGroup.PENDING -> SpecGroup.Pending
  SpecBrowserStatusGroup.DONE -> SpecGroup.Done
}

private fun SpecBrowserFileKind.toDomain(): SpecFileKind = when (this) {
  SpecBrowserFileKind.PARENT_SPEC -> SpecFileKind.ParentSpec
  SpecBrowserFileKind.SUBTASK_SPEC -> SpecFileKind.SubtaskSpec
  SpecBrowserFileKind.OTHER -> SpecFileKind.Other
}

private fun SpecBundleRunState.toDomain(): SpecRunState = when (this) {
  SpecBundleRunState.NOT_STARTED -> SpecRunState.NotStarted
  SpecBundleRunState.RUNNING -> SpecRunState.Running
  SpecBundleRunState.PAUSE_REQUESTED -> SpecRunState.PauseRequested
  SpecBundleRunState.PAUSED -> SpecRunState.Paused
  SpecBundleRunState.BLOCKED -> SpecRunState.Blocked
  SpecBundleRunState.CANCELED -> SpecRunState.Canceled
  SpecBundleRunState.COMPLETE -> SpecRunState.Complete
  SpecBundleRunState.UNAVAILABLE -> SpecRunState.Unavailable
}
