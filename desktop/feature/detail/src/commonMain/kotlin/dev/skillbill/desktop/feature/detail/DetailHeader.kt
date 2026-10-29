package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import dev.skillbill.desktop.core.ui.status.statusKind

/** The document header. It reads only the row it is given, so it paints in the frame the route opens. */
@Composable
internal fun DetailHeader(item: WorkItem, sectionsStale: Boolean, onRevealFolder: () -> Unit, onOpenBranch: () -> Unit) {
  val colors = SkillBillTheme.colors
  val status = statusKind(item.lifecycle, item.planningPrepared, stalledSince = null)
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    DocHeader(
      title = item.title,
      eyebrow = {
        SbLabel(status.label, SbLabelStyle.Tint(statusColor(status)))
        if (item.stalledSince != null) SbLabel("Stalled", SbLabelStyle.Tint(colors.stBlocked))
        IdTag(rowKey(item))
        if (item.kind != WorkKind.Goal) item.issueKey?.let { IdTag(it) }
        SbLabel(kindWord(item.kind), SbLabelStyle.Dot(null))
        val fresh = item.fresh && !sectionsStale
        SbLabel(if (fresh) "fresh" else "stale", SbLabelStyle.Dot(if (fresh) colors.stDone else colors.fgFaint))
      },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
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
  }
}

@Composable
internal fun DetailReason(item: WorkItem, onCopy: (String) -> Unit) {
  val reason = item.reason ?: return
  ReasonBlock(label = "Run reason", text = reason, onCopy = onCopy, color = statusColor(statusKind(item.lifecycle, item.planningPrepared, item.stalledSince)))
}
