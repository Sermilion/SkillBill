package dev.skillbill.tray.feature.work

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skillbill.tray.core.designsystem.DesignLabel
import dev.skillbill.tray.core.designsystem.amber
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

/**
 * The sidebar's live work as rows of the window's one sidebar list: the needs-you, standalone-phase and goal-run sections.
 * The window owns the list, its [scroll] state and the filter text, so work and history share one scroll region.
 */
fun LazyListScope.workSections(
  state: WorkUiState,
  filtering: Boolean,
  selected: WorkIdentity?,
  scroll: LazyListState,
  onAction: (WorkAction) -> Unit,
) {
  val shown = mutableSetOf<WorkIdentity>()
  workSection("Needs you", WorkSection.NEEDS_YOU, state.needsYou, shown, filtering, selected, scroll, onAction, first = true)
  workSection("Standalone phases", WorkSection.PHASES, state.phases, shown, filtering, selected, scroll, onAction)
  workSection("Goal runs", WorkSection.GOALS, state.goals, shown, filtering, selected, scroll, onAction)
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
) {
  val rows = (list as? PagedListState.Content)?.items.orEmpty().filter { shown.add(it.identity) }
  item(key = "$section:header") {
    DesignLabel("$title · ${rows.size}", Modifier.padding(start = 14.dp, top = if (first) 4.dp else 14.dp, bottom = 6.dp))
  }
  when (list) {
    PagedListState.InitialLoading -> item(key = "$section:loading") { SectionNote("Loading...") }
    is PagedListState.InitialError -> item(key = "$section:problem") { SectionProblem(list.problem.summary) { onAction(WorkAction.Retry(section)) } }
    is PagedListState.LoadedEmpty -> {
      if (list.refreshing) item(key = "$section:refreshing") { SectionNote("Updating...") }
      item(key = "$section:empty") { SectionNote(if (filtering) "No matching work" else "No ${title.lowercase()}") }
    }
    is PagedListState.Content -> {
      if (list.refreshing) item(key = "$section:refreshing") { SectionNote("Updating...") }
      list.readError?.let { problem -> item(key = "$section:problem") { SectionProblem(problem.summary) { onAction(WorkAction.Retry(section)) } } }
      items(rows, key = { "$section:${it.identity.repositoryIdentity}:${it.identity.id}" }) { item ->
        SidebarWorkRow(item, selected == item.identity) { onAction(WorkAction.Select(item.identity)) }
      }
      if (list.append != AppendState.Exhausted) item(key = "$section:footer") {
        LoadNextPageOnScroll(scroll, list.append !is AppendState.Failed, list.append == AppendState.Loading, "$section:footer") {
          onAction(WorkAction.LoadMore(section))
        }
        PageFooter(list.append) { onAction(WorkAction.LoadMore(section)) }
      }
    }
  }
}

@Composable
private fun SectionNote(text: String) {
  Text(text, Modifier.padding(start = 16.dp, bottom = 8.dp), color = muted, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun SectionProblem(summary: String, retry: () -> Unit) {
  Column(Modifier.padding(horizontal = 16.dp)) {
    Text(summary, color = amber, style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = retry) { Text("Retry", color = amber) }
  }
}
