package dev.skillbill.desktop.feature.specs

import dev.skillbill.desktop.core.domain.model.SpecGroup
import dev.skillbill.desktop.core.domain.model.SpecNode

internal fun visibleRows(
  roots: List<SpecNode>,
  persisted: Set<String>,
  filterText: String,
  statusFilter: SpecStatusFilter,
  shown: Map<String, Boolean> = emptyMap(),
): List<SpecRow> = RowWalk(persisted, filterText, statusFilter, shown).let { walk ->
  roots.flatMap { walk.node(it, 0, all = !walk.filtering) }
}

private class RowWalk(
  private val persisted: Set<String>,
  private val needle: String,
  private val statusFilter: SpecStatusFilter,
  private val shown: Map<String, Boolean>,
) {
  private val textActive = needle.isNotBlank()
  val filtering = textActive || statusFilter != SpecStatusFilter.ALL

  fun node(node: SpecNode, depth: Int, all: Boolean): List<SpecRow> = when (node) {
    is SpecNode.Folder -> folder(node, depth, all)
    is SpecNode.Bundle -> bundle(node, depth)
    is SpecNode.File -> if (all || matches(node.name)) listOf(SpecRow(node, depth, false, false)) else emptyList()
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

  private fun passesStatus(group: SpecGroup) = when (statusFilter) {
    SpecStatusFilter.ALL -> true
    SpecStatusFilter.ACTIVE -> group == SpecGroup.Active
    SpecStatusFilter.PENDING -> group == SpecGroup.Pending
    SpecStatusFilter.DONE -> group == SpecGroup.Done
  }

  private fun matches(value: String) = textActive && value.contains(needle, ignoreCase = true)
}

internal fun toolbarSelection(roots: List<SpecNode>, path: String): ToolbarSelection =
  selectionAt(roots, path)?.toolbar ?: ToolbarSelection.None

internal data class SpecPathSelection(
  val node: SpecNode,
  val bundle: SpecNode.Bundle?,
  val toolbar: ToolbarSelection,
)

internal fun selectionAt(roots: List<SpecNode>, path: String): SpecPathSelection? {
  val node = findNode(roots, path) ?: return null
  val bundle = when (node) {
    is SpecNode.Bundle -> node
    is SpecNode.File -> bundleFor(roots, path)
    is SpecNode.Folder -> null
  }
  val toolbar = when {
    node is SpecNode.Bundle -> ToolbarSelection.Bundle(node.path)
    node is SpecNode.File && bundle != null -> ToolbarSelection.FileInBundle(bundle.issueKey ?: bundle.name)
    else -> ToolbarSelection.None
  }
  return SpecPathSelection(node, bundle, toolbar)
}

internal fun bundleFor(roots: List<SpecNode>, path: String): SpecNode.Bundle? {
  fun find(nodes: List<SpecNode>, parent: SpecNode.Bundle?): SpecNode.Bundle? = nodes.firstNotNullOfOrNull { node ->
    when {
      node.path == path -> parent
      node is SpecNode.Bundle -> find(node.children, node)
      node is SpecNode.Folder -> find(node.children, parent)
      else -> null
    }
  }
  return find(roots, null)
}

internal fun findNode(roots: List<SpecNode>, path: String): SpecNode? = roots.firstNotNullOfOrNull { node ->
  when {
    node.path == path -> node
    node is SpecNode.Folder -> findNode(node.children, path)
    node is SpecNode.Bundle -> findNode(node.children, path)
    else -> null
  }
}
