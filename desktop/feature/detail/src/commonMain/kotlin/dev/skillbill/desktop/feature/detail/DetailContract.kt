package dev.skillbill.desktop.feature.detail

import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.DetailList
import dev.skillbill.desktop.core.domain.model.ReadProblem
import dev.skillbill.desktop.core.domain.model.RunOptions
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.ui.feedback.ToastMessage
import dev.skillbill.desktop.core.ui.navigation.AppRoute
import kotlin.time.Instant

/**
 * One open detail. [header] is the route's row until the first read replaces it, so it paints in the first frame. [stale] means the
 * last refresh failed and [sections] are the previous results; [renderedAt] is when the shown read arrived, for relative ages.
 */
data class DetailUiState(
  val header: WorkItem,
  val sections: SectionsUi,
  val stale: Boolean,
  val renderedAt: Instant,
  val listLoads: Map<DetailList, ListLoad> = emptyMap(),
  val boardPosition: BoardPosition? = null,
)

sealed interface SectionsUi {
  data object Pending : SectionsUi

  data class Loaded(
    val detail: Detail,
    val runOptions: RunOptions?,
    val listAnchors: Map<DetailList, String>,
    val moreLists: Set<DetailList>,
  ) : SectionsUi

  data object Gone : SectionsUi

  data class Failed(val problem: ReadProblem) : SectionsUi
}

/** One list's page request for [anchor]; a new anchor means a new page. */
sealed interface ListLoad {
  val anchor: String?

  data class Loading(override val anchor: String?) : ListLoad

  data class Done(override val anchor: String?) : ListLoad

  data class Failed(override val anchor: String?, val problem: ReadProblem) : ListLoad
}

/** Where the open item sits in the board's order, for J, K and the pager. */
data class BoardPosition(val index: Int, val total: Int, val previous: WorkItem?, val next: WorkItem?)

sealed interface DetailAction {
  data class LoadMore(val list: DetailList) : DetailAction
  data object Next : DetailAction
  data object Previous : DetailAction
  data object Retry : DetailAction
  data class Copy(val text: String) : DetailAction
  data object RevealFolder : DetailAction
  data object OpenBranch : DetailAction
  data class OpenSpecFile(val path: String) : DetailAction
  data class Navigate(val route: AppRoute) : DetailAction
}

sealed interface DetailEffect {
  data class OpenItem(val item: WorkItem) : DetailEffect
  data class OpenRoute(val route: AppRoute) : DetailEffect
  data class CopyToClipboard(val text: String) : DetailEffect
  data class ShowToast(val message: ToastMessage) : DetailEffect
}
