package dev.skillbill.desktop.feature.detail

import dev.skillbill.desktop.core.domain.model.QaRunFinding
import dev.skillbill.desktop.core.domain.model.VerifyCriterionGrade
import dev.skillbill.desktop.core.domain.model.VerifyRunDetails
import dev.skillbill.desktop.core.domain.model.VerifyReviewFinding
import dev.skillbill.desktop.core.domain.model.VerifyTestValueRow
import dev.skillbill.desktop.core.domain.settings.CriterionMark

enum class FixRoute { Chat, Goal }
enum class FixKind { Finding, Criterion, TestRow, QaFinding }
internal enum class SelectAllState { Off, Mixed, On }

internal sealed interface FixItem {
  val key: String
  val label: String
  val defaultRoute: FixRoute
  val locations: List<String>
  val kind: FixKind

  data class Finding(val finding: VerifyReviewFinding) : FixItem {
    override val key = "f:${finding.ref}"
    override val label = finding.ref
    override val defaultRoute = FixRoute.Chat
    override val locations = finding.locations
    override val kind = FixKind.Finding
  }

  data class Criterion(val grade: VerifyCriterionGrade, val text: String, val mark: CriterionMark?) : FixItem {
    override val key = "c:${grade.number}"
    override val label = "#${grade.number}"
    override val defaultRoute = if (grade.status == "FAIL") FixRoute.Goal else FixRoute.Chat
    override val locations = grade.locations
    override val kind = FixKind.Criterion
  }

  data class TestRow(val row: VerifyTestValueRow, override val key: String) : FixItem {
    override val label = row.area
    override val defaultRoute = FixRoute.Chat
    override val locations = row.locations
    override val kind = FixKind.TestRow
  }

  data class QaFinding(val finding: QaRunFinding, override val locations: List<String>) : FixItem {
    override val key = "qa:${finding.id}"
    override val label = finding.id
    override val defaultRoute = FixRoute.Chat
    override val kind = FixKind.QaFinding
  }
}

internal fun verifyFixItems(run: VerifyRunDetails, marks: Map<Int, CriterionMark>): List<FixItem> = buildList {
  run.reviewFindings?.items.orEmpty().filter { it.open }.forEach { add(FixItem.Finding(it)) }
  run.completenessAudit?.grades.orEmpty().filter { it.gap }.forEach { grade ->
    val text = run.criteria.firstOrNull { it.number == grade.number }?.text ?: grade.text
    add(FixItem.Criterion(grade, text, marks[grade.number]))
  }
  run.unitTestValue?.rows.orEmpty().forEachIndexed { index, row ->
    if (!row.action.trim().removeSuffix(".").equals("Keep", ignoreCase = true)) {
      add(FixItem.TestRow(row, "t:$index"))
    }
  }
  run.qa?.findings.orEmpty().forEach { add(FixItem.QaFinding(it, run.qaFindingLocations[it.id].orEmpty())) }
}

internal fun verifySelectionAllowed(run: VerifyRunDetails) = verifyRunState(run) in setOf(VerifyRunState.Finished, VerifyRunState.Stopped)

internal fun selectAllState(items: List<FixItem>, selection: Map<String, FixRoute>): SelectAllState? {
  val selectable = items.filterNot { it is FixItem.Criterion && it.mark != null }
  if (selectable.isEmpty()) return null
  val selected = selectable.count { it.key in selection }
  return when (selected) {
    0 -> SelectAllState.Off
    selectable.size -> SelectAllState.On
    else -> SelectAllState.Mixed
  }
}

internal fun toggleSelectAll(items: List<FixItem>, selection: Map<String, FixRoute>): Map<String, FixRoute> {
  val selectable = items.filterNot { it is FixItem.Criterion && it.mark != null }
  return if (selectable.isNotEmpty() && selectable.all { it.key in selection }) selection - selectable.map { it.key }.toSet()
  else selectable.fold(selection) { result, item -> if (item.key in result) result else result + (item.key to item.defaultRoute) }
}

internal fun toggleItem(selection: Map<String, FixRoute>, item: FixItem): Map<String, FixRoute> =
  if (item is FixItem.Criterion && item.mark != null) selection else if (item.key in selection) selection - item.key else selection + (item.key to item.defaultRoute)

internal fun setRoute(selection: Map<String, FixRoute>, key: String, route: FixRoute): Map<String, FixRoute> =
  if (key in selection) selection + (key to route) else selection

internal fun sharedHint(item: FixItem, items: List<FixItem>): String? {
  val location = item.locations.firstOrNull { candidate -> items.any { it.key != item.key && candidate in it.locations } } ?: return null
  val labels = items.filter { it.key != item.key && location in it.locations }.map { it.label }.distinct()
  return "Same code as ${labels.joinToString(", ")} · $location"
}

internal fun sharedLocationNote(selected: List<FixItem>): String? {
  val counts = linkedMapOf<String, Int>()
  selected.forEach { item -> item.locations.distinct().forEach { counts[it] = (counts[it] ?: 0) + 1 } }
  val best = counts.entries.filter { it.value > 1 }.maxByOrNull { it.value } ?: return null
  return "${best.value} of them touch ${best.key}, so one agent gets them together"
}

internal fun pruneSelection(selection: Map<String, FixRoute>, items: List<FixItem>, allowed: Boolean): Map<String, FixRoute> =
  if (!allowed) emptyMap() else selection.filterKeys { key -> items.any { it.key == key && (it !is FixItem.Criterion || it.mark == null) } }

internal fun orderedSelectedItems(items: List<FixItem>, selection: Map<String, FixRoute>, route: FixRoute? = null): List<FixItem> {
  val byKey = items.associateBy { it.key }
  return selection.entries.mapNotNull { (key, selectedRoute) ->
    if (route == null || selectedRoute == route) byKey[key] else null
  }
}

internal fun FixItem.kindLabel() = when (kind) {
  FixKind.Finding -> "finding"
  FixKind.Criterion -> "criterion"
  FixKind.TestRow -> "unit-test row"
  FixKind.QaFinding -> "QA finding"
}
