package dev.skillbill.tray.feature.specs

import dev.skillbill.tray.core.domain.model.SpecGroup
import dev.skillbill.tray.core.domain.model.SpecNode

/**
 * The tree as the pane shows it: a depth-first walk in the runtime's order, which this never changes. [persisted] is the saved
 * expansion. While a filter is active a node with a visible descendant is shown expanded, for display only; the saved set is
 * not touched, so clearing the filter restores it. [shown] is what the user expanded or collapsed under the current filter: it
 * overrides that filter-made expansion for display only, is never saved, and is ignored while no filter is active.
 */
internal fun visibleRows(
  roots: List<SpecNode>,
  persisted: Set<String>,
  filterText: String,
  statusFilter: SpecStatusFilter,
  shown: Map<String, Boolean> = emptyMap(),
): List<SpecRow> {
  val walk = RowWalk(persisted, filterText, statusFilter, shown)
  return roots.flatMap { walk.node(it, depth = 0, all = !walk.filtering) }
}

private class RowWalk(
  private val persisted: Set<String>,
  private val needle: String,
  private val statusFilter: SpecStatusFilter,
  private val shown: Map<String, Boolean>,
) {
  private val textActive = needle.isNotBlank()
  val filtering = textActive || statusFilter != SpecStatusFilter.ALL

  /** [all] says this node's contents are visible regardless of the text: no filter, or an enclosing bundle matched. */
  fun node(node: SpecNode, depth: Int, all: Boolean): List<SpecRow> = when (node) {
    is SpecNode.Folder -> folder(node, depth, all)
    is SpecNode.Bundle -> bundle(node, depth)
    is SpecNode.File -> if (all || matches(node.name)) listOf(SpecRow(node, depth, hasChildren = false, expanded = false)) else emptyList()
  }

  private fun folder(folder: SpecNode.Folder, depth: Int, all: Boolean): List<SpecRow> {
    val saved = folder.path in persisted
    val children = if (filtering || saved) folder.children.flatMap { node(it, depth + 1, all) } else emptyList()
    if (filtering && children.isEmpty()) return emptyList()
    val expanded = (if (filtering) shown[folder.path] else null) ?: (filtering || saved)
    return listOf(SpecRow(folder, depth, folder.children.isNotEmpty(), expanded)) + if (expanded) children else emptyList()
  }

  private fun bundle(bundle: SpecNode.Bundle, depth: Int): List<SpecRow> {
    if (!passesStatus(bundle.group)) return emptyList()
    val byName = !textActive || matches(bundle.issueKey.orEmpty()) || matches(bundle.title)
    val expanded = (if (filtering) shown[bundle.path] else null) ?: (textActive || bundle.path in persisted)
    val children = if (textActive || expanded) bundle.children.flatMap { node(it, depth + 1, all = byName) } else emptyList()
    if (textActive && !byName && children.isEmpty()) return emptyList()
    return listOf(SpecRow(bundle, depth, bundle.children.isNotEmpty(), expanded)) + if (expanded) children else emptyList()
  }

  private fun passesStatus(group: SpecGroup): Boolean = when (statusFilter) {
    SpecStatusFilter.ALL -> true
    SpecStatusFilter.ACTIVE -> group == SpecGroup.ACTIVE
    SpecStatusFilter.PENDING -> group == SpecGroup.PENDING
    SpecStatusFilter.DONE -> group == SpecGroup.DONE
  }

  private fun matches(value: String): Boolean = textActive && value.contains(needle, ignoreCase = true)
}
