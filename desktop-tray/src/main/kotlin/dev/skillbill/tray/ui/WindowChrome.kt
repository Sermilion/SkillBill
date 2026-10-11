package dev.skillbill.tray.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.WindowPlacement

@Composable
fun WindowScope.DesignWindow(state: WindowState, onClose: () -> Unit, content: @Composable () -> Unit) {
  Column(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(canvas).border(1.dp, border, RoundedCornerShape(12.dp))) {
    WindowDraggableArea {
      Row(Modifier.fillMaxWidth().height(36.dp).background(titlebar).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(60.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
          Text("Skill Bill", color = muted, fontFamily = body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 12.sp)
        }
        Row(Modifier.width(60.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
          ChromeControl("–", "Minimize") { state.isMinimized = true }
          ChromeControl("□", "Maximize") { state.placement = if (state.placement == WindowPlacement.Maximized) WindowPlacement.Floating else WindowPlacement.Maximized }
          ChromeControl("×", "Hide to tray", onClose)
        }
      }
    }
    HorizontalDivider(color = border)
    Box(Modifier.weight(1f).fillMaxWidth()) { content() }
  }
}

@Composable
private fun ChromeControl(symbol: String, description: String, action: () -> Unit) {
  Box(Modifier.width(15.dp).height(30.dp).clickable(onClickLabel = description, onClick = action), contentAlignment = Alignment.Center) {
    Text(symbol, color = dim, fontFamily = mono, fontSize = 13.sp)
  }
}
