package dev.skillbill.desktop.app.tray

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.skillbill.desktop.core.designsystem.icon.skillBillIcon
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.domain.model.TrayStatus
import dev.skillbill.desktop.core.designsystem.SkillBillTheme

object TrayIconRenderer {
  @Composable
  fun Content(state: TrayStatus, running: Int, showOverlay: Boolean) {
    val icon = skillBillIcon()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Image(icon, contentDescription = null, modifier = Modifier.fillMaxSize())
      if (showOverlay && state == TrayStatus.Running && running > 0) {
        Box(Modifier.align(Alignment.BottomEnd).size(18.dp).background(Color(0xFF3FB950), CircleShape), contentAlignment = Alignment.Center) {
          Text(if (running > 99) "99+" else running.toString(), color = Color.White, style = SkillBillTheme.type.tXs)
        }
      }
      if (showOverlay && state in setOf(TrayStatus.Failed, TrayStatus.NeedsYou)) {
        val color = if (state == TrayStatus.Failed) SkillBillTheme.colors.stFailed else SkillBillTheme.colors.stBlocked
        Box(Modifier.align(Alignment.TopEnd).size(6.dp).background(color, CircleShape))
      }
    }
  }
}
