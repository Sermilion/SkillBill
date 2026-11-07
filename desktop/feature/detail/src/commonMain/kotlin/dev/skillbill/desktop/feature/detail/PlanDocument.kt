package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillSpace
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbButtonVariant
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.designsystem.detail.sbBoxSegment
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.DetailList
import dev.skillbill.desktop.core.domain.model.WorkItem
import kotlinx.coroutines.launch

internal fun DetailSections.planDocument(
  item: WorkItem,
  detail: Detail,
  state: DetailUiState,
  view: RunActionsView,
  onAction: (DetailAction) -> Unit,
) {
  val sections = this
  val screen = requireNotNull(planScreenState)
  val plan = detail.standalonePlan
  val blockedIds = blockedSubtaskIds(plan)
  item(key = "plan-stage-strip") {
    PlanStageStrip(item, detail) { phaseId ->
      screen.closedActivityGroups.remove(phaseId)
      screen.follow = false
      screen.activityJumpPhaseId = phaseId
      if (sections.collapse.isCollapsed("plan-activity")) sections.collapse.toggle("plan-activity")
      screen.scrollScope.launch { sections.indexOf("plan-activity")?.let { sections.scrollState.animateScrollToItem(it) } }
    }
  }
  if (blockedIds.isNotEmpty()) {
    item(key = "plan-blocked") {
      val incidentIndex = sections.indexOf("incident") ?: sections.indexOf("decision")
      PlanBlockedCallout(
        blockedIds,
        blockedIds.mapNotNull { id ->
          val reason = plan?.units?.firstOrNull { it.subtaskId == id }?.blockedReason?.takeIf(String::isNotBlank)
            ?: plan?.blockedReason?.takeIf(String::isNotBlank)
          reason?.let { if (blockedIds.size == 1) it else "Subtask $id: $it" }
        },
        view,
        onRetry = { onAction(ControlsAction.Recover) },
        onOpenIncident = incidentIndex?.let { index ->
          { screen.scrollScope.launch { sections.scrollState.animateScrollToItem(index) }; Unit }
        },
      )
    }
  } else {
    item(key = "plan-now") { PlanNowBlock(item, detail) }
  }
  if (blockedIds.isNotEmpty() || detail.incident != null || view.decision != null) incident(state, view, onAction, planHeader = true)
  planActivitySection(item, detail, onAction) { phaseId -> revealPlanBriefing(screen, phaseId) }
  planBundle(item, detail, onAction)
  planTranscript(item, detail, state, screen, onAction)
  planBriefingSection(item, detail, screen, onAction)
  pageFooter(DetailList.Executions, state, onAction)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlanBlockedCallout(
  ids: List<Int>,
  reasons: List<String>,
  view: RunActionsView,
  onRetry: () -> Unit,
  onOpenIncident: (() -> Unit)?,
) {
  val colors = SkillBillTheme.colors
  val label = ids.joinToString(", ")
  Column(
    Modifier.fillMaxWidth().padding(top = SkillBillSpace.s3)
      .border(1.dp, colors.stBlocked.copy(alpha = .40f), RoundedCornerShape(SkillBillRadius.md))
      .background(colors.stBlocked.copy(alpha = .06f), RoundedCornerShape(SkillBillRadius.md)).padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      CompositionLocalProvider(LocalContentColor provides colors.stBlocked) { SbIcon(LucideIcons.warn, 14.dp) }
      BasicText("Subtask${if (ids.size == 1) "" else "s"} $label need${if (ids.size == 1) "s" else ""} your decision", style = SkillBillTheme.type.tSmMedium.copy(color = colors.stBlocked))
    }
    BasicText(
      if (ids.size == 1) buildBlockedReason("Planning stopped at subtask $label", reasons.firstOrNull())
      else buildBlockedReason("Planning stopped at subtasks $label", null),
      style = SkillBillTheme.type.tSm.copy(color = colors.fg2, lineHeight = 1.5.em),
    )
    if (ids.size > 1) reasons.forEach { BasicText(buildBlockedReason("", it), style = SkillBillTheme.type.tSm.copy(color = colors.fg2)) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      view.recover?.let { recover -> SbButton("Retry subtask${if (ids.size == 1) "" else "s"} $label", onRetry, variant = SbButtonVariant.Secondary, size = SbControlSize.Small, enabled = recover.enabled) }
      if (onOpenIncident != null) SbButton("Open incident", onOpenIncident, variant = SbButtonVariant.Ghost, size = SbControlSize.Small)
    }
  }
}

@Composable
private fun buildBlockedReason(prefix: String, reason: String?) = buildAnnotatedString {
  if (prefix.isNotBlank()) append("$prefix${if (reason.isNullOrBlank()) "." else ". "}")
  if (!reason.isNullOrBlank()) {
    val parts = Regex("`([^`]+)`").findAll(reason)
    var index = 0
    parts.forEach { match ->
      append(reason.substring(index, match.range.first))
      withStyle(SpanStyle(color = SkillBillTheme.colors.accentText, fontFamily = FontFamily.Monospace)) { append(match.groupValues[1]) }
      index = match.range.last + 1
    }
    append(reason.substring(index))
  }
}

private fun DetailSections.planBundle(item: WorkItem, detail: Detail, onAction: (DetailAction) -> Unit) {
  val plan = detail.standalonePlan
  val units = plan?.units.orEmpty()
  val parents = planParentFiles(item, detail)
  val count = if (units.isNotEmpty()) "${units.count { it.state == "saved" }}/${units.size} subtask specs saved"
    else "${parents.count { it.saved }} of 2 files"
  val total = parents.size + 1 + units.size
  planSection("plan-bundle", "Spec bundle", count = count) {
    parents.forEachIndexed { index, file ->
      item(key = "plan-bundle-file-${file.name}") {
        PlanParentRow(file, Modifier.sbBoxSegment(index, total)) { onAction(DetailAction.OpenSpecFile(it)) }
      }
    }
    if (units.isNotEmpty()) {
      val wave = if (planFinished(item, detail)) null else planWaveLabel(requireNotNull(plan))
      val label = "SUBTASK SPECS" + (wave?.let { " · ${it.uppercaseInvariant()}" } ?: "")
      item(key = "plan-bundle-divider") { PlanBundleDivider(label, Modifier.sbBoxSegment(parents.size, total)) }
      itemsIndexed(units, key = { _, unit -> "plan-unit-${unit.subtaskId}" }) { index, unit ->
        PlanSubtaskRow(unit, item, detail, Modifier.sbBoxSegment(parents.size + 1 + index, total)) {
          onAction(DetailAction.OpenSpecFile(unit.specPath))
        }
      }
    } else {
      item(key = "plan-bundle-hint") { PlanBundleHint(Modifier.sbBoxSegment(parents.size, total)) }
    }
  }
}

internal data class PlanParentFile(val name: String, val path: String?, val saved: Boolean)

internal fun planParentFiles(item: WorkItem, detail: Detail): List<PlanParentFile> {
  val plan = detail.standalonePlan
  val units = plan?.units.orEmpty()
  val bundle = plan?.report?.bundleFiles.orEmpty()
  val writes = planActivity(item, detail).filter { it.kind == "write" }
  return PLAN_PARENT_FILES.map { name ->
    val matched = bundle.firstOrNull { parentFileMatches(fileName(it), name) }
      ?: writes.firstOrNull { event ->
        val named = event.path ?: event.openPath
        named != null && parentFileMatches(fileName(named), name)
      }?.let { event -> event.openPath ?: event.path }
    val path = matched ?: units.firstOrNull()?.specPath?.let { derivedParentPath(it, name) }
    PlanParentFile(name, path, matched != null || units.isNotEmpty())
  }
}

private val PLAN_PARENT_FILES = listOf("spec.md", "decomposition-manifest.yaml")

private fun parentFileMatches(file: String, name: String): Boolean =
  file == name || name == "decomposition-manifest.yaml" && file == "decomposition-manifest.yml"

private fun String.uppercaseInvariant(): String = buildString(length) {
  for (ch in this@uppercaseInvariant) append(ch.uppercaseChar())
}

private fun derivedParentPath(specPath: String, name: String): String {
  val directory = specPath.replace('\\', '/').substringBeforeLast('/', "")
  return if (directory.isEmpty()) name else "$directory/$name"
}

@Composable
private fun PlanParentRow(file: PlanParentFile, modifier: Modifier, onOpen: (String) -> Unit) {
  val colors = SkillBillTheme.colors
  val path = file.path
  Row(
    modifier.fillMaxWidth()
      .then(if (file.saved && path != null) Modifier.clickable(role = Role.Button, onClickLabel = "Open ${file.name}") { onOpen(path) } else Modifier)
      .padding(horizontal = 12.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
      if (file.saved) CompositionLocalProvider(LocalContentColor provides colors.stDone) { SbIcon(LucideIcons.check, 12.dp) }
      else PlanDashedRing(colors.fgFaint)
    }
    BasicText(
      file.name,
      Modifier.weight(1f),
      style = SkillBillTheme.type.tXs.copy(fontFamily = SkillBillTheme.type.t2xs.fontFamily, color = colors.fg),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    if (file.saved && path != null) CompositionLocalProvider(LocalContentColor provides colors.fgFaint) { SbIcon(LucideIcons.external, 12.dp) }
    else if (!file.saved) BasicText("not yet", style = SkillBillTheme.type.tXs.copy(color = colors.fgFaint))
  }
}

@Composable
internal fun PlanBundleDivider(label: String, modifier: Modifier = Modifier) {
  Box(modifier.fillMaxWidth().background(SkillBillTheme.colors.bgInset).padding(horizontal = 12.dp, vertical = 4.dp)) {
    BasicText(
      label,
      Modifier.semantics { heading() },
      style = SkillBillTheme.type.t2xs.copy(color = SkillBillTheme.colors.fgMuted, fontWeight = FontWeight.Medium, letterSpacing = 0.02.em),
    )
  }
}

@Composable
private fun PlanBundleHint(modifier: Modifier) {
  Column(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    BasicText("Subtask specs appear here when the work is split.", style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgFaint))
    Box(Modifier.fillMaxWidth(.72f).height(8.dp).background(SkillBillTheme.colors.bgInset, RoundedCornerShape(4.dp)))
    Box(Modifier.fillMaxWidth(.58f).height(8.dp).background(SkillBillTheme.colors.bgInset, RoundedCornerShape(4.dp)))
  }
}
