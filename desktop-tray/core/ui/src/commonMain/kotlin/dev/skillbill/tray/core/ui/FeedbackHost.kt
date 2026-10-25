package dev.skillbill.tray.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.skillbill.tray.core.designsystem.ink
import dev.skillbill.tray.core.designsystem.raised

val LocalFeedbackHost = staticCompositionLocalOf<SnackbarHostState?> { null }

@Composable
fun FeedbackHost(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
  val host = remember { SnackbarHostState() }
  CompositionLocalProvider(LocalFeedbackHost provides host) {
    Box(modifier) {
      content()
      SnackbarHost(host, Modifier.align(Alignment.BottomCenter).widthIn(max = 520.dp).padding(12.dp)) { data ->
        Snackbar(data, containerColor = raised, contentColor = ink)
      }
    }
  }
}

@Composable
fun FeedbackMessages(pending: List<Feedback>, acknowledge: (Long) -> Unit) {
  val host = LocalFeedbackHost.current
  val first = pending.firstOrNull()
  val onAcknowledge = rememberUpdatedState(acknowledge)
  LaunchedEffect(host, first?.id) {
    if (host != null && first != null) {
      try {
        host.showSnackbar(first.message, withDismissAction = true, duration = SnackbarDuration.Short)
      } finally {
        onAcknowledge.value(first.id)
      }
    }
  }
}

@Composable
fun ProblemMessage(message: String?) {
  val host = LocalFeedbackHost.current
  LaunchedEffect(host, message) {
    if (message != null) host?.showSnackbar(message, withDismissAction = true, duration = SnackbarDuration.Short)
  }
}
