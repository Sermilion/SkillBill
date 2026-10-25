package skillbill.engine.specbrowser

import skillbill.engine.specbrowser.model.SpecBrowserCounts
import skillbill.engine.specbrowser.model.SpecBrowserNode
import skillbill.engine.specbrowser.model.SpecBrowserStatus
import skillbill.engine.specbrowser.model.SpecBrowserStatusGroup
import skillbill.idestatus.model.IdeStatusLifecycleState
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.model.DecompositionStatus
import java.time.Instant

private val IN_PROGRESS_STATUSES =
  setOf(SpecBrowserStatus.RUNNING, SpecBrowserStatus.PAUSING, SpecBrowserStatus.IN_PROGRESS)

internal data class LiveGoal(
  val lifecycle: IdeStatusLifecycleState,
  val pauseRequested: Boolean,
)

internal data class LiveCandidates(
  val unfinished: Set<String>,
  val finished: Set<String>,
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
  live: LiveGoal?,
  manifest: DecompositionManifest?,
): SpecBrowserStatus =
  live?.let { liveStatus(it.lifecycle, it.pauseRequested) }
    ?: manifest?.let { manifestStatus(it.status) }
    ?: SpecBrowserStatus.PENDING

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

internal fun liveCandidates(nodes: List<RawSpecNode>): LiveCandidates {
  val (finished, unfinished) = rawBundles(nodes).partition { it.finished() }
  return LiveCandidates(unfinished.keys(), finished.keys())
}

private fun RawSpecBundle.finished(): Boolean =
  manifest?.let {
    manifestStatus(it.status)
  } == SpecBrowserStatus.COMPLETE

private fun List<RawSpecBundle>.keys(): Set<String> = mapNotNullTo(HashSet()) { it.issueKey }

private fun rawBundles(nodes: List<RawSpecNode>): List<RawSpecBundle> =
  nodes.filterIsInstance<RawSpecDirectory>().flatMap { listOfNotNull(it.bundle) + rawBundles(it.children) }

internal fun resolveSpecTree(
  raw: List<RawSpecNode>,
  live: Map<String, LiveGoal>,
): ResolvedSpecTree {
  val resolved = ordered(raw.map { resolveNode(it, live) })
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
  val finishedAt: Instant? = null,
)

private fun ordered(nodes: List<ResolvedNode>): List<ResolvedNode> {
  val (bundles, rest) = nodes.partition { it.node is SpecBrowserNode.Bundle }
  return bundles.sortedWith(bundleOrder) + rest
}

private val bundleOrder: Comparator<ResolvedNode> =
  compareBy<ResolvedNode> { groupRank((it.node as SpecBrowserNode.Bundle).group) }.thenByDescending { it.finishedAt }

private fun groupRank(group: SpecBrowserStatusGroup): Int =
  when (group) {
    SpecBrowserStatusGroup.ACTIVE -> 0
    SpecBrowserStatusGroup.PENDING -> 1
    SpecBrowserStatusGroup.DONE -> 2
  }

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
  val children = ordered(raw.children.map { resolveNode(it, live) })
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
  val status = bundleStatus(bundle.issueKey?.let(live::get), bundle.manifest)
  val group = statusGroup(status)
  val subtasks = bundle.manifest?.subtasks
  val node =
    SpecBrowserNode.Bundle(
      name = raw.name,
      path = raw.path,
      absolutePath = raw.absolutePath,
      issueKey = bundle.issueKey,
      title = bundle.title,
      status = status,
      group = group,
      subtasksDone = subtasks?.count { manifestStatus(it.status) == SpecBrowserStatus.COMPLETE },
      subtasksTotal = subtasks?.size,
      children = childNodes,
    )
  val finishedAt = bundle.manifestModified.takeIf { group == SpecBrowserStatusGroup.DONE }
  return ResolvedNode(node, listOf(status) + below, finishedAt)
}
