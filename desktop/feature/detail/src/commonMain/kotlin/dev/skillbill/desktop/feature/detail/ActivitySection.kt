package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.LocalReducedMotion
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.LocalUiTicker
import dev.skillbill.desktop.core.designsystem.detail.DocBlockGap
import dev.skillbill.desktop.core.designsystem.detail.SbBox
import dev.skillbill.desktop.core.domain.model.ActivityGroup
import dev.skillbill.desktop.core.domain.model.ActivityGroupKind
import dev.skillbill.desktop.core.domain.model.ActivityResult
import dev.skillbill.desktop.core.domain.model.ActivityStatus
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.DetailList
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.model.WorkKind
import dev.skillbill.desktop.core.ui.feedback.readProblemText
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

internal fun DetailSections.activitySection(item: WorkItem, detail: Detail, state: DetailUiState, onAction: (DetailAction) -> Unit) {
  val listState = scrollState
  item(key = "activity-timeline") {
    val reducedMotion = LocalReducedMotion.current
    val jump: suspend (Float) -> Unit = { offset ->
      val index = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "activity-timeline" }?.index
      if (index != null) {
        if (reducedMotion) listState.scrollToItem(index, offset.roundToInt())
        else listState.animateScrollToItem(index, offset.roundToInt())
      }
    }
    Column(Modifier.padding(top = DocBlockGap)) {
      if (item.kind != WorkKind.Goal) {
        ActivityTimeline(standaloneActivity(item, detail), item.id, blockReasonFallback = item.reason, onCopyReason = { onAction(DetailAction.Copy(it)) }, onJump = jump)
      } else when (val activity = state.activity) {
        is ActivityResult.Listed -> ActivityTimeline(activity.groups, item.id, blockReasonFallback = item.reason, onCopyReason = { onAction(DetailAction.Copy(it)) }, onJump = jump)
        ActivityResult.Loading -> ActivityText("Loading activity...", faint = true)
        ActivityResult.NotFound -> ActivityText("No recorded activity.", faint = true)
        is ActivityResult.Unavailable -> ActivityText("Activity is unavailable. ${readProblemText(activity.problem)}", faint = true)
      }
    }
  }
  if (item.kind != WorkKind.Goal) pageFooter(DetailList.Executions, state, onAction)
}

@Composable
internal fun ActivityTimeline(
  groups: List<ActivityGroup>,
  workId: String,
  modifier: Modifier = Modifier,
  blockReasonFallback: String? = null,
  onCopyReason: ((String) -> Unit)? = null,
  onJump: suspend (Float) -> Unit = {},
) {
  var blocksOnly by rememberSaveable(workId) { mutableStateOf(false) }
  var expanded by rememberSaveable(workId) { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
  val positions = remember(workId) { mutableMapOf<String, Float>() }
  val scope = rememberCoroutineScope()
  val ticker = LocalUiTicker.current
  DisposableEffect(ticker) { ticker.retain(); onDispose { ticker.release() } }
  val now = ticker.now.value
  val general = groups.filter { it.kind == ActivityGroupKind.GENERAL_BLOCKS }
  val ordered = groups.filterNot { it.kind == ActivityGroupKind.GENERAL_BLOCKS }.map { group ->
    group.copy(blocks = group.blocks.map { block ->
      if (block.reason == null && block.needsDecision && !blockReasonFallback.isNullOrBlank()) {
        block.copy(reason = blockReasonFallback)
      } else {
        block
      }
    })
  }
  val shown = ordered.filter { !blocksOnly || it.blocks.isNotEmpty() || it.status == ActivityStatus.BLOCKED }
  fun isOpen(group: ActivityGroup) = expanded[group.id] ?: (groups.size == 1 || group.status == ActivityStatus.RUNNING || group.status == ActivityStatus.BLOCKED)
  Column(modifier.fillMaxWidth()) {
    Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      ActivityText("Activity", size = 13f, sans = true, color = SkillBillTheme.colors.fg)
      ActivityText(groups.sumOf { it.phases.size + it.before.size + it.after.size }.toString(), size = 13f, sans = true, faint = true)
      Spacer(Modifier.weight(1f))
      val anyClosed = shown.any { !isOpen(it) }
      ActivityAction(if (anyClosed) "Expand all" else "Collapse all") { expanded = expanded + shown.associate { it.id to anyClosed } }
      ActivityFilter(blocksOnly, groups.sumOf { it.blocks.size }) { selected ->
        blocksOnly = selected
        if (selected) expanded = expanded + groups.filter { it.blocks.isNotEmpty() || it.status == ActivityStatus.BLOCKED }.associate { it.id to true }
      }
    }
    ActivityStats(groups, now)
    ActivityBar(groups, now) { id ->
      blocksOnly = false
      expanded = expanded + (id to true)
      scope.launch {
        withFrameNanos { }
        positions[id]?.let { onJump(it) }
      }
    }
    var day: String? = null
    shown.forEachIndexed { index, group ->
      val currentDay = group.startedAt?.let { formatInstant(it, "MMM d") }
      if (currentDay != null && day != currentDay) {
        ActivityText(currentDay.uppercase(), Modifier.padding(top = 14.dp, bottom = 8.dp), size = 10f, faint = true, weight = FontWeight.SemiBold)
        day = currentDay
      }
      val previous = shown.getOrNull(index - 1)?.finishedAt
      val started = group.startedAt
      if (!blocksOnly && previous != null && started != null && started - previous > activityLongGap) {
        ActivityWait((started - previous).inWholeMilliseconds)
      }
      key(group.id) {
        ActivityCard(group, isOpen(group), now, Modifier.onGloballyPositioned { positions[group.id] = it.positionInParent().y }.padding(bottom = 6.dp), onCopyReason) {
          expanded = expanded + (group.id to !isOpen(group))
        }
      }
    }
    if (general.isNotEmpty()) Column(Modifier.fillMaxWidth().onGloballyPositioned { coordinates ->
      general.forEach { positions[it.id] = coordinates.positionInParent().y }
    }.padding(top = DocBlockGap)) {
      Row(Modifier.padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ActivityText("General blocks", size = 13f, sans = true, color = SkillBillTheme.colors.fg)
        ActivityText(general.sumOf { it.blocks.size }.toString(), size = 13f, sans = true, faint = true)
      }
      general.forEach { group ->
        key(group.id) {
          SbBox {
            Column(Modifier.padding(vertical = 4.dp)) {
              group.blocks.forEach { ActivityBlockRow(it, now, onCopyReason = onCopyReason) }
            }
          }
        }
      }
    }
    if (shown.isEmpty() && general.isEmpty()) ActivityText(if (blocksOnly) "No blocks in this run." else "No recorded activity.", Modifier.padding(24.dp), sans = true, faint = true)
  }
}
