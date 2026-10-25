package dev.skillbill.tray.feature.specs

import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import dev.skillbill.tray.core.domain.repository.Outcome
import dev.skillbill.tray.core.ui.FeedbackMessages
import dev.skillbill.tray.core.ui.LocalFeedbackHost
import dev.skillbill.tray.core.ui.LocalRepositoryNavigation

/** The pane bound to its ViewModel: it opens files and reveals the spec folder, and reports a file that would not open. */
@Composable
fun SpecsRoute(viewModel: SpecsViewModel, modifier: Modifier = Modifier) {
  val state by viewModel.state.collectAsState()
  val navigation = LocalRepositoryNavigation.current
  val feedback = LocalFeedbackHost.current
  FeedbackMessages(state.pendingFeedback) { viewModel.onAction(SpecsAction.AcknowledgeFeedback(it)) }
  LaunchedEffect(viewModel, navigation) {
    viewModel.effects.collect { effect ->
      when (effect) {
        is SpecsEffect.OpenFile -> {
          val result = navigation.openFile(effect.absolutePath)
          if (result is Outcome.Failure) viewModel.onAction(SpecsAction.FileOpenFailed(effect.absolutePath, result.problem.summary))
        }
        is SpecsEffect.RevealDirectory -> {
          val result = navigation.openDirectory(effect.path)
          if (result is Outcome.Failure) feedback?.showSnackbar(result.problem.summary)
        }
      }
    }
  }
  SpecsPane(state, viewModel::onAction, modifier) { SpecRunToolbar(state.toolbar, viewModel::onAction) }
}
