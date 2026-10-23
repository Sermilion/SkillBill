package dev.skillbill.tray.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skillbill.tray.core.designsystem.amber
import dev.skillbill.tray.core.designsystem.dim
import dev.skillbill.tray.core.designsystem.display
import dev.skillbill.tray.core.designsystem.ink
import dev.skillbill.tray.core.designsystem.mint
import dev.skillbill.tray.core.designsystem.mono
import dev.skillbill.tray.core.designsystem.muted
import dev.skillbill.tray.core.designsystem.orange
import dev.skillbill.tray.core.designsystem.raised
import dev.skillbill.tray.core.designsystem.sidebar
import dev.skillbill.tray.core.domain.model.Lifecycle
import dev.skillbill.tray.core.domain.model.WorkItem
import dev.skillbill.tray.core.domain.model.WorkFamily
import dev.skillbill.tray.core.domain.model.finished
import dev.skillbill.tray.core.domain.model.needsAttention
import dev.skillbill.tray.core.domain.model.showsAsStandalonePhase
import dev.skillbill.tray.core.domain.model.stalled

/** One work item in a sidebar list; the work and history screens share it so both read the same. */
@Composable
fun SidebarWorkRow(item: WorkItem, selected: Boolean, onSelect: () -> Unit) {
  Row(Modifier.padding(horizontal = 8.dp).fillMaxWidth().background(if (selected) raised else sidebar, RoundedCornerShape(8.dp)).clickable(onClick = onSelect).heightIn(min = 47.dp)) {
    Box(Modifier.width(2.dp).height(47.dp).background(if (selected) mint else sidebar))
    Row(Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
      WorkDot(item.lifecycle, stalled = item.stalled())
      Column(Modifier.weight(1f)) {
        Text(item.repositoryTitle(), color = ink, fontFamily = display, fontWeight = FontWeight.Bold, fontSize = 12.5.sp, lineHeight = 16.25.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val status = item.outcomeLabel().takeIf { item.showsAsStandalonePhase() || item.finished() || item.stalled() }
        val step = item.currentStepLabel.takeUnless { item.lifecycle == Lifecycle.TERMINAL || item.lifecycle == Lifecycle.CANCELED || status != null && it == item.phaseId }
        Text(
          listOfNotNull(item.issueKey ?: item.phaseId ?: "Verify".takeIf { item.family == WorkFamily.FEATURE_VERIFY }, status, step).joinToString(" · "),
          color = if (item.lifecycle == Lifecycle.CANCELED) lifecycleColor(item.lifecycle) else if (item.needsAttention()) orange else if (item.stalled()) amber else muted,
          fontFamily = mono, fontSize = 10.5.sp, lineHeight = 14.7.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        val branch = item.branch?.takeIf { item.showsAsStandalonePhase() }
        if (branch != null) {
          Text(branch, color = dim, fontFamily = mono, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
      }
      item.progressLabel()?.let { progress ->
        Text(progress, color = if (selected) amber else dim, style = MaterialTheme.typography.labelSmall)
      }
    }
  }
}
