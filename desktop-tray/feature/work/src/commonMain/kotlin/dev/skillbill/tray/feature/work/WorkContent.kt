package dev.skillbill.tray.feature.work

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skillbill.tray.core.designsystem.DesignLabel
import dev.skillbill.tray.core.designsystem.body
import dev.skillbill.tray.core.designsystem.dim
import dev.skillbill.tray.core.designsystem.ink
import dev.skillbill.tray.core.designsystem.mint
import dev.skillbill.tray.core.designsystem.muted
import dev.skillbill.tray.core.designsystem.surface3
import dev.skillbill.tray.core.domain.model.WorkIdentity
import dev.skillbill.tray.core.domain.model.WorkItem
import dev.skillbill.tray.core.domain.model.WorkSection
import dev.skillbill.tray.core.ui.AppendState
import dev.skillbill.tray.core.ui.LoadNextPageOnScroll
import dev.skillbill.tray.core.ui.PageFooter
import dev.skillbill.tray.core.ui.PagedListState
import dev.skillbill.tray.core.ui.SidebarWorkRow
import kotlin.time.Instant

/**
 * The sidebar's work as rows of the window's one sidebar list: the needs-you, standalone-phase, operation and goal-run sections, then
 * what finished since [today], the start of the local day. The window owns the list, its [scroll] state and the filter text,
 * so work and history share one scroll region.
 */
fun LazyListScope.workSections(
  state: WorkUiState,
  today: Instant,
  filtering: Boolean,
  selected: WorkIdentity?,
  scroll: LazyListState,
  onAction: (WorkAction) -> Unit,
) {
  val shown = mutableSetOf<WorkIdentity>()
  workSection("Needs you", WorkSection.NEEDS_YOU, state.needsYou, shown, filtering, selected, scroll, onAction, first = true)
  workSection("Standalone phases", WorkSection.PHASES, state.phases, shown, filtering, selected, scroll, onAction)
  workSection("Operations", WorkSection.OPERATIONS, state.operations, shown, filtering, selected, scroll, onAction)
  workSection("Goal runs", WorkSection.GOALS, state.goals, shown, filtering, selected, scroll, onAction)
  // The recent section holds the last 24 hours, latest first; rows from before today's start drop out, and so do older pages.
  workSection("Finished today", WorkSection.RECENT, state.finished, shown, filtering, selected, scroll, onAction, empty = "Nothing finished today") {
    it.finishedAt?.let { finished -> finished >= today } == true
  }
}

/** The sidebar's filter field. The window owns the text and hands it to both the work and the history ViewModel. */
@Composable
fun SidebarFilterField(filter: String, onChange: (String) -> Unit) {
  BasicTextField(
    filter,
    onChange,
    Modifier.padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 10.dp).fillMaxWidth().height(34.dp)
      .background(surface3, RoundedCornerShape(8.dp)).border(1.dp, dev.skillbill.tray.core.designsystem.border, RoundedCornerShape(8.dp))
      .padding(horizontal = 10.dp),
    singleLine = true,
    textStyle = TextStyle(color = ink, fontFamily = body, fontSize = 12.sp),
    cursorBrush = SolidColor(mint),
    decorationBox = { field ->
      Box(contentAlignment = Alignment.CenterStart) {
        if (filter.isEmpty()) Text("⌕  Filter by repo, issue, branch", color = dim, fontFamily = body, fontSize = 12.sp)
        field()
      }
    },
  )
}

private fun LazyListScope.workSection(
  title: String,
  section: WorkSection,
  list: PagedListState<WorkItem>,
  shown: MutableSet<WorkIdentity>,
  filtering: Boolean,
  selected: WorkIdentity?,
  scroll: LazyListState,
  onAction: (WorkAction) -> Unit,
  first: Boolean = false,
  empty: String = "No ${title.lowercase()}",
  shows: (WorkItem) -> Boolean = { true },
) {
  val loaded = (list as? PagedListState.Content)?.items.orEmpty()
  val rows = loaded.filter { shows(it) && shown.add(it.identity) }
  item(key = "$section:header") {
    DesignLabel("$title · ${rows.size}", Modifier.padding(start = 14.dp, top = if (first) 4.dp else 14.dp, bottom = 6.dp))
  }
  when (list) {
    PagedListState.InitialLoading -> item(key = "$section:loading") { SectionNote("Loading...") }
    // Read failures are reported once, in the window's status line, so the section only says it has nothing to show.
    is PagedListState.InitialError -> item(key = "$section:problem") { SectionNote("Couldn't load") }
    is PagedListState.LoadedEmpty -> emptyNote(section, if (filtering) "No matching work" else empty)
    is PagedListState.Content -> {
      if (rows.isEmpty()) emptyNote(section, if (filtering) "No matching work" else empty)
      items(rows, key = { "$section:${it.identity.repositoryIdentity}:${it.identity.id}" }) { item ->
        SidebarWorkRow(item, selected == item.identity) { onAction(WorkAction.Select(item.identity)) }
      }
      // Pages run in section order, so once a loaded row is not shown, no later page holds one that is.
      if (list.append != AppendState.Exhausted && loaded.all(shows)) item(key = "$section:footer") {
        LoadNextPageOnScroll(scroll, list.append !is AppendState.Failed, list.append == AppendState.Loading, "$section:footer") {
          onAction(WorkAction.LoadMore(section))
        }
        PageFooter(list.append) { onAction(WorkAction.LoadMore(section)) }
      }
    }
  }
}

private fun LazyListScope.emptyNote(section: WorkSection, text: String) {
  item(key = "$section:empty") { SectionNote(text) }
}

@Composable
private fun SectionNote(text: String) {
  Text(text, Modifier.padding(start = 16.dp, bottom = 8.dp), color = muted, style = MaterialTheme.typography.bodySmall)
}
