package skillbill.engine.specbrowser

import skillbill.engine.specbrowser.model.SpecBrowserCounts
import skillbill.engine.specbrowser.model.SpecBrowserNode
import skillbill.engine.specbrowser.model.SpecBrowserStatus
import skillbill.engine.specbrowser.model.SpecBrowserStatusGroup
import skillbill.idestatus.model.IdeStatusLifecycleState
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.model.DecompositionStatus

private const val ARCHIVE_SEGMENT = "done"

private val IN_PROGRESS_STATUSES =
  setOf(SpecBrowserStatus.RUNNING, SpecBrowserStatus.PAUSING, SpecBrowserStatus.IN_PROGRESS)

internal data class LiveGoal(
  val lifecycle: IdeStatusLifecycleState,
  val pauseRequested: Boolean,
)

internal data class ResolvedSpecTree(
  val roots: List<SpecBrowserNode>,
  val counts: SpecBrowserCounts,
  val anyInProgress: Boolean,
)

internal fun manifestStatus(wire: String): SpecBrowserStatus =
  when (DecompositionStatus.fromWire(wire)) {
    DecompositionStatus.IN_PROGRESS -> SpecBrowserStatus.IN_PROGRESS
    DecompositionStatus.COMPLETE,
    DecompositionStatus.SKIPPED,
    DecompositionStatus.COMPLETED_NO_CHANGE,
    -> SpecBrowserStatus.COMPLETE
    DecompositionStatus.BLOCKED -> SpecBrowserStatus.BLOCKED
    DecompositionStatus.PENDING, null -> SpecBrowserStatus.PENDING
  }

internal fun liveStatus(
  lifecycle: IdeStatusLifecycleState,
  pauseRequested: Boolean,
): SpecBrowserStatus? =
  when (lifecycle) {
    IdeStatusLifecycleState.ACTIVE -> if (pauseRequested) SpecBrowserStatus.PAUSING else SpecBrowserStatus.RUNNING
    IdeStatusLifecycleState.PAUSED -> SpecBrowserStatus.PAUSED
    IdeStatusLifecycleState.BLOCKED, IdeStatusLifecycleState.FAILED -> SpecBrowserStatus.BLOCKED
    IdeStatusLifecycleState.CANCELED -> SpecBrowserStatus.CANCELED
    IdeStatusLifecycleState.TERMINAL, IdeStatusLifecycleState.IDLE -> null
  }

internal fun bundleStatus(
  underDone: Boolean,
  live: LiveGoal?,
  manifest: DecompositionManifest?,
): SpecBrowserStatus =
  if (underDone) {
    SpecBrowserStatus.COMPLETE
  } else {
    live?.let { liveStatus(it.lifecycle, it.pauseRequested) }
      ?: manifest?.let { manifestStatus(it.status) }
      ?: SpecBrowserStatus.PENDING
  }

internal fun statusGroup(status: SpecBrowserStatus): SpecBrowserStatusGroup =
  when (status) {
    SpecBrowserStatus.RUNNING,
    SpecBrowserStatus.PAUSING,
    SpecBrowserStatus.IN_PROGRESS,
    SpecBrowserStatus.PAUSED,
    SpecBrowserStatus.BLOCKED,
    -> SpecBrowserStatusGroup.ACTIVE
    SpecBrowserStatus.PENDING -> SpecBrowserStatusGroup.PENDING
    SpecBrowserStatus.COMPLETE, SpecBrowserStatus.CANCELED -> SpecBrowserStatusGroup.DONE
  }

internal fun isUnderDone(bundlePath: String): Boolean = bundlePath.split('/').dropLast(1).contains(ARCHIVE_SEGMENT)

internal fun liveCandidateKeys(nodes: List<RawSpecNode>): Set<String> =
  nodes.filterIsInstance<RawSpecDirectory>().flatMap { directory ->
    val own = directory.bundle?.issueKey?.takeIf { !isUnderDone(directory.path) }
    listOfNotNull(own) + liveCandidateKeys(directory.children)
  }.toSet()

internal fun resolveSpecTree(
  raw: List<RawSpecNode>,
  live: Map<String, LiveGoal>,
): ResolvedSpecTree {
  val resolved = raw.map { resolveNode(it, live) }
  val statuses = resolved.flatMap { it.bundleStatuses }
  val groups = statuses.map(::statusGroup)
  val counts =
    SpecBrowserCounts(
      all = statuses.size,
      active = groups.count { it == SpecBrowserStatusGroup.ACTIVE },
      pending = groups.count { it == SpecBrowserStatusGroup.PENDING },
      done = groups.count { it == SpecBrowserStatusGroup.DONE },
    )
  return ResolvedSpecTree(resolved.map { it.node }, counts, statuses.any { it in IN_PROGRESS_STATUSES })
}

private class ResolvedNode(
  val node: SpecBrowserNode,
  val bundleStatuses: List<SpecBrowserStatus>,
)

private fun resolveNode(
  raw: RawSpecNode,
  live: Map<String, LiveGoal>,
): ResolvedNode =
  when (raw) {
    is RawSpecFile ->
      ResolvedNode(
        SpecBrowserNode.File(
          name = raw.name,
          path = raw.path,
          absolutePath = raw.absolutePath,
          kind = raw.kind,
          subtaskId = raw.subtask?.id,
          subtaskName = raw.subtask?.name,
          status = raw.subtask?.let { manifestStatus(it.status) },
        ),
        emptyList(),
      )
    is RawSpecDirectory -> resolveDirectory(raw, live)
  }

private fun resolveDirectory(
  raw: RawSpecDirectory,
  live: Map<String, LiveGoal>,
): ResolvedNode {
  val children = raw.children.map { resolveNode(it, live) }
  val below = children.flatMap { it.bundleStatuses }
  val childNodes = children.map { it.node }
  val bundle = raw.bundle
  if (bundle == null) {
    val folder =
      SpecBrowserNode.Folder(
        name = raw.name,
        path = raw.path,
        absolutePath = raw.absolutePath,
        children = childNodes,
        bundleCount = below.size,
        statusCounts = below.groupingBy { it }.eachCount(),
      )
    return ResolvedNode(folder, below)
  }
  val underDone = isUnderDone(raw.path)
  val liveGoal = bundle.issueKey?.takeIf { !underDone }?.let(live::get)
  val status = bundleStatus(underDone, liveGoal, bundle.manifest)
  val subtasks = bundle.manifest?.subtasks
  val node =
    SpecBrowserNode.Bundle(
      name = raw.name,
      path = raw.path,
      absolutePath = raw.absolutePath,
      issueKey = bundle.issueKey,
      title = bundle.title,
      status = status,
      group = statusGroup(status),
      subtasksDone = subtasks?.count { manifestStatus(it.status) == SpecBrowserStatus.COMPLETE },
      subtasksTotal = subtasks?.size,
      children = childNodes,
    )
  return ResolvedNode(node, listOf(status) + below)
}
