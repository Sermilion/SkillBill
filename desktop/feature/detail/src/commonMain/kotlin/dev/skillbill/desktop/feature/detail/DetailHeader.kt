package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.IdTag
import dev.skillbill.desktop.core.designsystem.component.ReasonBlock
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbButtonVariant
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.designsystem.component.SbLabel
import dev.skillbill.desktop.core.designsystem.component.SbLabelStyle
import dev.skillbill.desktop.core.designsystem.detail.DocHeader
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.model.WorkKind

/**
 * The document header (HANDOFF 9.2 items 1 to 4): chips, title, repository location, then [actions] (the run actions), all in
 * one `.sb-doc-hd` 10 apart. It reads only the row it is given, so it paints in the frame the route opens.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DetailHeader(
  item: WorkItem,
  sectionsStale: Boolean,
  onRevealFolder: () -> Unit,
  onOpenBranch: () -> Unit,
  actions: (@Composable () -> Unit)? = null,
) {
  val colors = SkillBillTheme.colors
  DocHeader(
    title = item.title,
    eyebrow = {
      SbLabel(lifecycleLabel(item.lifecycle), SbLabelStyle.Tint(lifecycleColor(item.lifecycle)))
      if (item.stalledSince != null) SbLabel("Stalled", SbLabelStyle.Tint(colors.stBlocked))
      IdTag(rowKey(item))
      if (item.kind != WorkKind.Goal) item.issueKey?.let { IdTag(it) }
      SbLabel(kindWord(item.kind), SbLabelStyle.Plain)
      val fresh = item.fresh && !sectionsStale
      SbLabel(if (fresh) "fresh" else "stale", SbLabelStyle.Dot(if (fresh) colors.stDone else colors.fgFaint))
    },
    // `.l-row g-1 l-wrap`: the folder and branch buttons, 4 apart, wrapping.
    sub = {
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
      ) {
        if (item.repositoryRoot.isNotBlank()) {
          SbButton(abbreviateHome(item.repositoryRoot), onRevealFolder, variant = SbButtonVariant.Ghost, size = SbControlSize.Small, icon = LucideIcons.folder)
        }
        val branch = item.branch
        if (branch != null) {
          SbButton(branch, onOpenBranch, variant = SbButtonVariant.Ghost, size = SbControlSize.Small, icon = LucideIcons.branch)
        } else {
          BasicText("no branch", style = SkillBillTheme.type.tSm.copy(color = colors.fgFaint))
        }
      }
    },
    actions = actions?.let { { it() } },
  )
}

/** HANDOFF 9.2 item 6; the left rule takes the lifecycle color, as the chip does. */
@Composable
internal fun DetailReason(item: WorkItem, onCopy: (String) -> Unit) {
  val reason = item.reason ?: return
  ReasonBlock(label = "Run reason", text = reason, onCopy = onCopy, color = lifecycleColor(item.lifecycle))
}
