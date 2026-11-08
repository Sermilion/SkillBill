package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

@Stable
internal class PlanScreenState(val activityFeed: LazyListState, val scrollScope: CoroutineScope, val revealScope: CoroutineScope) {
  var follow by mutableStateOf(true)
  var filter by mutableStateOf(PlanActivityFilter.All)
  val closedActivityGroups = mutableStateSetOf<String>()
  val openFoldKeys = mutableStateSetOf<String>()
  var transcriptExpanded by mutableStateOf(false)
  var activityJumpPhaseId by mutableStateOf<String?>(null)
  var briefingReveal by mutableStateOf<String?>(null)
  var briefingRevealJob: Job? = null
  fun toggleFold(key: String) { if (!openFoldKeys.add(key)) openFoldKeys.remove(key) }
}

@Composable
internal fun rememberPlanScreenState(itemId: String): PlanScreenState {
  return key(itemId) {
    val scope = rememberCoroutineScope()
    remember(scope) { PlanScreenState(LazyListState(), scope, scope) }
  }
}
