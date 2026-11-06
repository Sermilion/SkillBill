package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbButtonVariant
import dev.skillbill.desktop.core.designsystem.detail.SectionCollapse
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Stable
internal class ValidationPageState(
  val shape: ValidationReportShape?,
  private val open: androidx.compose.runtime.snapshots.SnapshotStateList<Boolean>,
  val openAndScroll: (Int) -> Unit,
) {
  fun isOpen(index: Int) = open.getOrElse(index) { false }
  fun toggle(index: Int) { if (index in open.indices) open[index] = !open[index] }
  val allOpen: Boolean get() = open.isNotEmpty() && open.all { it }
  fun setAll(value: Boolean) { open.indices.forEach { open[it] = value } }
}

@Composable
internal fun rememberValidationPage(
  shape: ValidationReportShape?,
  invocationId: String?,
  listState: LazyListState,
  collapse: SectionCollapse,
  keys: LazyKeyIndex,
  forceClosed: Boolean = false,
): ValidationPageState {
  val parts = shape?.parts.orEmpty()
  val open = remember(invocationId, shape) { mutableStateListOf<Boolean>().apply { addAll(parts.map { it.openByDefault && !forceClosed }) } }
  val parentScope = rememberCoroutineScope()
  val navigationJob = remember(invocationId, shape) { Job(parentScope.coroutineContext[Job]) }
  DisposableEffect(navigationJob) {
    onDispose { navigationJob.cancel() }
  }
  val navigationScope = remember(parentScope, navigationJob) {
    CoroutineScope(parentScope.coroutineContext + navigationJob)
  }
  return remember(shape, open, navigationScope, listState, collapse, keys) {
    var pendingNavigation: Job? = null
    ValidationPageState(shape, open) { index ->
      if (index in parts.indices) {
        open[index] = true
        if (collapse.isCollapsed("validation-report")) collapse.toggle("validation-report")
        pendingNavigation?.cancel()
        pendingNavigation = navigationScope.launch {
          val key = validationPartKey(index)
          while (isActive) {
            withFrameNanos { }
            val target = keys.indexOf(key)
            if (target != null && target < listState.layoutInfo.totalItemsCount) {
              withFrameNanos { }
              val currentTarget = keys.indexOf(key)
              if (currentTarget != null && currentTarget < listState.layoutInfo.totalItemsCount) {
                listState.animateScrollToItem(currentTarget)
                if (listState.layoutInfo.visibleItemsInfo.any { it.key == key }) return@launch
              }
            }
          }
        }
      }
    }
  }
}

internal fun validationPartKey(index: Int) = "validation-part-$index"

internal fun DetailSections.validationFlagStrip(page: ValidationPageState) {
  val flagged = page.shape?.parts?.withIndex()?.filter { it.value.flagged }.orEmpty()
  if (flagged.isEmpty()) return
  item(key = "validation-flags") {
    Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      BasicText(
        if (flagged.size == 1) "The report flags one thing to read" else "The report flags ${flagged.size} things to read",
        style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fg2),
      )
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        flagged.forEach { (index, part) ->
          SbButton(part.title, { page.openAndScroll(index) }, variant = SbButtonVariant.Ghost)
        }
      }
    }
  }
}

internal fun DetailSections.validationReportSection(page: ValidationPageState) {
  val parts = page.shape?.parts.orEmpty()
  if (page.shape == null || parts.isEmpty()) return
  section(
    "validation-report", "Report", count = "${parts.size} parts",
    actions = {
      SbButton(if (page.allOpen) "Collapse all" else "Expand all", { page.setAll(!page.allOpen) }, variant = SbButtonVariant.Ghost)
    },
  ) {
    itemsIndexed(parts, key = { index, _ -> validationPartKey(index) }) { index, part ->
      val colors = SkillBillTheme.colors
      Column(Modifier.fillMaxWidth().clickable { page.toggle(index) }.padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          SbIcon(if (page.isOpen(index)) LucideIcons.chevronDown else LucideIcons.chevronRight, 12.dp)
          BasicText(part.title, style = SkillBillTheme.type.tSmMedium.copy(color = colors.fg))
          if (!page.isOpen(index)) BasicText(part.preview, Modifier.weight(1f), style = SkillBillTheme.type.tXs.copy(color = colors.fgMuted), maxLines = 1)
          if (part.listItemCount > 0) BasicText("${part.listItemCount}", style = SkillBillTheme.type.tXs.copy(color = colors.fgFaint))
        }
        if (page.isOpen(index)) ReportText(part.text, Modifier.padding(start = 20.dp, top = 8.dp))
      }
    }
  }
}
