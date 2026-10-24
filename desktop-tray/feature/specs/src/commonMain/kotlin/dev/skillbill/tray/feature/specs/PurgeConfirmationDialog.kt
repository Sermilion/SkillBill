package dev.skillbill.tray.feature.specs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skillbill.tray.core.designsystem.DesignButton
import dev.skillbill.tray.core.designsystem.DesignLabel
import dev.skillbill.tray.core.designsystem.body
import dev.skillbill.tray.core.designsystem.muted
import dev.skillbill.tray.core.designsystem.orange

private val DELETED = listOf("goal and subtask run history", "run evidence and task tracking", "checkpoint refs", "subtask progress, back to not started")
private val KEPT = listOf("every spec file in the folder", "your branch and its commits", "open pull requests")

/**
 * The modal behind Purge. "Keep run history" holds initial focus and is what Escape, an outside click and a dismiss mean, so the
 * destructive button is only ever reached on purpose.
 */
@Composable
internal fun PurgeConfirmationDialog(key: String, onKeep: () -> Unit, onConfirm: () -> Unit) {
  val keepFocus = remember { FocusRequester() }
  var keepAttached by remember { mutableStateOf(false) }
  LaunchedEffect(keepAttached) {
    if (keepAttached) keepFocus.requestFocus()
  }
  AlertDialog(
    onDismissRequest = onKeep,
    modifier = Modifier.onPreviewKeyEvent { event ->
      (event.type == KeyEventType.KeyDown && event.key == Key.Escape).also { if (it) onKeep() }
    },
    title = { Text("Purge $key?") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Purge deletes everything Skill Bill recorded while running this spec, so it can start again from scratch.", color = muted, fontFamily = body, fontSize = 13.sp)
        ListSection("Deleted", DELETED)
        ListSection("Kept", KEPT)
        Text("This can't be undone. Afterwards the spec shows as not started and Start runs it from the first subtask.", color = orange, fontFamily = body, fontSize = 13.sp)
      }
    },
    confirmButton = { DesignButton("Purge $key", danger = true, onClick = onConfirm) },
    dismissButton = {
      DesignButton("Keep run history", modifier = Modifier.focusRequester(keepFocus).onGloballyPositioned { keepAttached = true }, onClick = onKeep)
    },
  )
}

@Composable
private fun ListSection(label: String, items: List<String>) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    DesignLabel(label)
    items.forEach { item ->
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("•", color = muted, fontFamily = body, fontSize = 13.sp)
        Text(item, color = muted, fontFamily = body, fontSize = 13.sp)
      }
    }
  }
}
