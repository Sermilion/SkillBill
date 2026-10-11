package dev.skillbill.tray.infrastructure

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.skillbill.tray.presentation.TrayStateKind

object TrayIconRenderer {
  @Composable
  fun Content(icon: ImageVector, state: TrayStateKind, running: Int, showOverlay: Boolean) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Image(icon, contentDescription = null, modifier = Modifier.fillMaxSize())
      if (showOverlay && state == TrayStateKind.RUNNING && running > 0) {
        Box(Modifier.align(Alignment.BottomEnd).size(18.dp).background(Color(0xFFB73336), CircleShape), contentAlignment = Alignment.Center) {
          Text(if (running > 99) "99+" else running.toString(), color = Color.White, style = MaterialTheme.typography.labelSmall)
        }
      }
    }
  }
}
