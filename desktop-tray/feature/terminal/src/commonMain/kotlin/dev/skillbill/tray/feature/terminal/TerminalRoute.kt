package dev.skillbill.tray.feature.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skillbill.tray.core.designsystem.body
import dev.skillbill.tray.core.designsystem.canvas
import dev.skillbill.tray.core.designsystem.dim
import dev.skillbill.tray.core.designsystem.mono
import dev.skillbill.tray.core.designsystem.muted

/**
 * The bottom pane chrome around a platform-supplied terminal [surface]. The surface receives the directory a session should
 * start in, or null when none is known. The shell draws the divider above the pane.
 */
@Composable
fun TerminalRoute(
  viewModel: TerminalViewModel,
  surface: @Composable (workingDirectory: String?) -> Unit,
  modifier: Modifier = Modifier,
) {
  val state by viewModel.state.collectAsState()
  if (!state.paneOpen) return
  Column(modifier.fillMaxSize()) {
    Row(
      Modifier.fillMaxWidth().height(28.dp).background(canvas).padding(horizontal = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text("Terminal", color = muted, fontFamily = body, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
      Spacer(Modifier.weight(1f))
      CloseButton { viewModel.onAction(TerminalAction.ClosePane) }
    }
    Box(Modifier.weight(1f).fillMaxWidth()) { surface(state.workingDirectory) }
  }
}

@Composable
private fun CloseButton(onClick: () -> Unit) {
  Box(
    Modifier.size(24.dp).clickable(role = Role.Button, onClick = onClick).clearAndSetSemantics {
      contentDescription = "Close terminal"
      role = Role.Button
    },
    contentAlignment = Alignment.Center,
  ) {
    Text("×", color = dim, fontFamily = mono, fontSize = 13.sp)
  }
}
