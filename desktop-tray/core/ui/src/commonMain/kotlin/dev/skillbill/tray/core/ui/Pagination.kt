package dev.skillbill.tray.core.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import dev.skillbill.tray.core.designsystem.mint
import dev.skillbill.tray.core.designsystem.muted
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Asks for the next page once the user scrolls to [boundaryKey] (or, without one, near the end of the list). It only reacts
 * to a scroll the user is performing, so a short list never requests pages on its own.
 */
@Composable
fun LoadNextPageOnScroll(scroll: LazyListState, hasMore: Boolean, loading: Boolean, boundaryKey: String? = null, load: () -> Unit) {
  LaunchedEffect(scroll, boundaryKey, hasMore, loading) {
    if (hasMore && !loading) snapshotFlow {
      scroll.isScrollInProgress && if (boundaryKey != null) {
        scroll.layoutInfo.visibleItemsInfo.any { it.key == boundaryKey }
      } else {
        scroll.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= scroll.layoutInfo.totalItemsCount - 2 } == true
      }
    }.distinctUntilChanged().collect { if (it) load() }
  }
}

/** The footer of a paged list for its [append] state; nothing is shown when no further page exists. */
@Composable
fun PageFooter(append: AppendState, load: () -> Unit) {
  when (append) {
    AppendState.Exhausted -> Unit
    AppendState.Loading -> Text("Loading...", color = muted)
    AppendState.Idle -> TextButton(onClick = load) { Text("Load more", color = mint) }
    is AppendState.Failed -> {
      ProblemMessage(append.problem.summary)
      TextButton(onClick = load) { Text("Retry page", color = mint) }
    }
  }
}
