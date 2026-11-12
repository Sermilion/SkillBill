package dev.skillbill.desktop.feature.chat

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.designsystem.component.SbDialog

internal const val DELIVERY_RETRY_WARNING = "Claude may already have received this follow-up. Retrying may deliver it twice."

@Composable
internal fun ChatDeliveryRecovery(
  delivery: ChatDeliveryView,
  onRetry: (String) -> Unit,
  onDiscard: (String) -> Unit,
  onKeepOpen: () -> Unit,
) {
  BasicText(
    if (delivery.status == ChatDeliveryStatus.Pending) "Sending steering instructions..." else "Steering instructions have been sent",
    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgMuted),
  )
  if (delivery.blockedAction != null) {
    SbDialog(title = "Resolve steering delivery", onDismissRequest = onKeepOpen, actions = {}) {
      ChatDeliveryRecoveryDetails(delivery, onRetry, onDiscard, onKeepOpen)
    }
  }
}

@Composable
private fun ChatDeliveryRecoveryDetails(
  delivery: ChatDeliveryView,
  onRetry: (String) -> Unit,
  onDiscard: (String) -> Unit,
  onKeepOpen: () -> Unit,
) {
  var confirmation by remember(delivery.deliveryId) { mutableStateOf<String?>(null) }
  val bodyStyle = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fg2)
  Column(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
    SelectionContainer { BasicText(delivery.reason, style = bodyStyle) }
    Column(Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      if (delivery.text.isNotBlank()) SelectionContainer { BasicText(delivery.text, style = bodyStyle) }
      if (delivery.images.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        delivery.images.forEachIndexed { index, image -> ImagePreviewChip(index + 1, image) }
      }
    }
    delivery.blockedAction?.let { BasicText("Resolve this follow-up before you $it. Your original text and images remain here.", style = bodyStyle) }
    when (confirmation) {
      "retry" -> {
        BasicText(DELIVERY_RETRY_WARNING, style = bodyStyle)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          SbButton("Retry anyway", { confirmation = null; onRetry(delivery.deliveryId) }, size = SbControlSize.Small)
          SbButton("Cancel retry", { confirmation = null }, size = SbControlSize.Small)
        }
      }
      "discard" -> {
        BasicText("Discard the retained text and images? This does not undo any delivery Claude already received.", style = bodyStyle)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          SbButton("Discard follow-up", { confirmation = null; onDiscard(delivery.deliveryId) }, size = SbControlSize.Small)
          SbButton("Keep follow-up", { confirmation = null }, size = SbControlSize.Small)
        }
      }
      else -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (delivery.status == ChatDeliveryStatus.Uncertain) {
          SbButton("Retry", { confirmation = "retry" }, size = SbControlSize.Small)
        }
        SbButton("Discard", { confirmation = "discard" }, size = SbControlSize.Small)
        if (delivery.blockedAction != null) SbButton("Keep open", onKeepOpen, size = SbControlSize.Small)
      }
    }
  }
}
