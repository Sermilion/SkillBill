package dev.skillbill.tray.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
internal fun LoadNextPageOnScroll(scroll: LazyListState, hasMore: Boolean, loading: Boolean, boundaryKey: String? = null, load: () -> Unit) {
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

@Composable
internal fun LoadNextPageOnScroll(scroll: ScrollState, hasMore: Boolean, loading: Boolean, load: () -> Unit) {
  LaunchedEffect(scroll, hasMore, loading) {
    if (hasMore && !loading) snapshotFlow {
      scroll.isScrollInProgress && scroll.value >= (scroll.maxValue - 200).coerceAtLeast(1)
    }.distinctUntilChanged().collect { if (it) load() }
  }
}

@Composable
internal fun PageFooter(hasMore: Boolean, loading: Boolean, load: () -> Unit) = PageFooter(hasMore, loading, false, load)

@Composable
internal fun PageFooter(hasMore: Boolean, loading: Boolean, failed: Boolean, load: () -> Unit) {
  if (hasMore) {
    if (loading) Text("Loading...", color = muted)
    else TextButton(onClick = load) { Text(if (failed) "Retry page" else "Load more", color = mint) }
  }
}
