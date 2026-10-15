package dev.skillbill.tray.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.WindowScope
import dev.skillbill.tray.core.designsystem.dim
import dev.skillbill.tray.core.designsystem.display
import dev.skillbill.tray.core.designsystem.ink
import dev.skillbill.tray.core.designsystem.mint
import dev.skillbill.tray.core.domain.model.WorkIdentity
import dev.skillbill.tray.feature.work.TrayPopoverRoute
import dev.skillbill.tray.feature.work.TrayViewModel

/** The tray popover window: the work feature's content under a header that drags the window and closes it. */
@Composable
fun WindowScope.TrayPopover(
  tray: TrayViewModel,
  selected: WorkIdentity?,
  onClose: () -> Unit,
  openMain: () -> Unit,
  openSettings: () -> Unit,
  onQuit: () -> Unit,
) {
  TrayPopoverRoute(tray, selected, openMain, openSettings, onQuit, Modifier.fillMaxSize()) {
    WindowDraggableArea {
      Row(
        Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(mint.copy(alpha = .07f), Color.Transparent)))
          .padding(start = 16.dp, end = 16.dp, top = 13.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Image(painterResource("icons/skill-bill.svg"), "", Modifier.size(18.dp).clip(RoundedCornerShape(4.dp)))
        Text("Skill Bill", Modifier.weight(1f), color = ink, fontFamily = display, fontWeight = FontWeight.Bold, fontSize = 15.sp, letterSpacing = (-.3).sp)
        IconButton(onClick = onClose, modifier = Modifier.size(24.dp)) {
          Icon(Icons.Default.Close, contentDescription = "Close tray window", tint = dim, modifier = Modifier.size(16.dp))
        }
      }
    }
  }
}
