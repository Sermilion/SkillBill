package dev.skillbill.tray.feature.specs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skillbill.tray.core.designsystem.DesignButton
import dev.skillbill.tray.core.designsystem.amber
import dev.skillbill.tray.core.designsystem.body
import dev.skillbill.tray.core.designsystem.border
import dev.skillbill.tray.core.designsystem.dim
import dev.skillbill.tray.core.designsystem.ink
import dev.skillbill.tray.core.designsystem.mono
import dev.skillbill.tray.core.designsystem.muted
import dev.skillbill.tray.core.designsystem.raised
import dev.skillbill.tray.core.designsystem.sidebar
import dev.skillbill.tray.core.designsystem.strongBorder

/** The pane is this wide wherever it is hosted. */
val SPECS_PANE_WIDTH: Dp = 320.dp

/**
 * The pane chrome: header, the toolbar slot, filters, the tree or the state that replaces it, and the footer. [toolbar] renders
 * pinned under the header.
 */
@Composable
fun SpecsPane(
  state: SpecsUiState,
  onAction: (SpecsAction) -> Unit,
  modifier: Modifier = Modifier,
  toolbar: @Composable () -> Unit = {},
) {
  Column(modifier.width(SPECS_PANE_WIDTH).fillMaxHeight().background(sidebar)) {
    Header(state, onAction)
    toolbar()
    FilterField(state.filterText) { onAction(SpecsAction.FilterChanged(it)) }
    StatusFilter(state, onAction)
    Box(Modifier.weight(1f).fillMaxWidth()) { Body(state, onAction) }
    state.project?.let { Footer(it.specsRoot) { onAction(SpecsAction.Reveal) } }
  }
}

@Composable
private fun Header(state: SpecsUiState, onAction: (SpecsAction) -> Unit) {
  Column {
    Row(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      Text("Specs", color = ink, fontFamily = body, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
      ProjectPicker(state, onAction, Modifier.weight(1f))
      HeaderButton("⊟", "Collapse all") { onAction(SpecsAction.CollapseAll) }
      HeaderButton("×", "Close specs pane") { onAction(SpecsAction.ClosePane) }
    }
    HorizontalDivider(color = border)
  }
}

@Composable
private fun ProjectPicker(state: SpecsUiState, onAction: (SpecsAction) -> Unit, modifier: Modifier) {
  var open by remember { mutableStateOf(false) }
  Box(modifier, contentAlignment = Alignment.CenterEnd) {
    Row(
      Modifier.clickable(role = Role.Button, onClickLabel = "Choose project") { open = true }.padding(horizontal = 6.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Text(state.project?.displayName ?: "No project", color = muted, fontFamily = body, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 120.dp))
      Text("▾", color = dim, fontSize = 9.sp)
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
      state.projects.forEach { project ->
        DropdownMenuItem(text = { Text(project.displayName) }, onClick = {
          open = false
          onAction(SpecsAction.PickProject(project.projectId))
        })
      }
    }
  }
}

@Composable
private fun HeaderButton(glyph: String, description: String, onClick: () -> Unit) {
  Box(
    Modifier.size(24.dp).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = description },
    contentAlignment = Alignment.Center,
  ) {
    Text(glyph, color = dim, fontFamily = mono, fontSize = 13.sp)
  }
}

@Composable
private fun FilterField(text: String, onChange: (String) -> Unit) {
  Box(
    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).height(30.dp)
      .background(raised, RoundedCornerShape(6.dp)).border(1.dp, strongBorder, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp),
    contentAlignment = Alignment.CenterStart,
  ) {
    BasicTextField(
      value = text,
      onValueChange = onChange,
      modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Filter specs" },
      singleLine = true,
      textStyle = TextStyle(color = ink, fontFamily = body, fontSize = 12.sp),
      cursorBrush = SolidColor(amber),
    )
    if (text.isEmpty()) Text("Filter by key, title or file", color = dim, fontFamily = body, fontSize = 12.sp)
  }
}

@Composable
private fun StatusFilter(state: SpecsUiState, onAction: (SpecsAction) -> Unit) {
  val counts = (state.snapshot as? SpecSnapshotState.Loaded)?.snapshot?.counts
  Row(
    Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp).selectableGroup(),
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    SpecStatusFilter.entries.forEach { filter ->
      val label = when (filter) {
        SpecStatusFilter.ALL -> "All"
        SpecStatusFilter.ACTIVE -> "Active"
        SpecStatusFilter.PENDING -> "Pending"
        SpecStatusFilter.DONE -> "Done"
      }
      val count = counts?.let {
        when (filter) {
          SpecStatusFilter.ALL -> it.all
          SpecStatusFilter.ACTIVE -> it.active
          SpecStatusFilter.PENDING -> it.pending
          SpecStatusFilter.DONE -> it.done
        }
      }
      val selected = state.statusFilter == filter
      Row(
        Modifier.weight(1f).height(26.dp)
          .background(if (selected) raised else sidebar, RoundedCornerShape(6.dp))
          .border(1.dp, if (selected) amber.copy(alpha = .5f) else border, RoundedCornerShape(6.dp))
          .selectable(selected = selected, role = Role.RadioButton) { onAction(SpecsAction.ChooseStatus(filter)) },
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(label, color = if (selected) ink else muted, fontFamily = body, fontWeight = FontWeight.Medium, fontSize = 11.sp, maxLines = 1)
        count?.let { Text(it.toString(), color = dim, fontFamily = mono, fontSize = 10.sp) }
      }
    }
  }
}

@Composable
private fun Body(state: SpecsUiState, onAction: (SpecsAction) -> Unit) {
  val snapshot = state.snapshot
  val loaded = (snapshot as? SpecSnapshotState.Loaded)?.snapshot
  when {
    snapshot is SpecSnapshotState.Failed -> UnreadableFolder(snapshot.message, onAction)
    loaded?.failure != null -> UnreadableFolder(loaded.failure.orEmpty(), onAction)
    state.project == null && state.projectsLoaded && state.projects.isEmpty() -> StateMessage("No specs yet") {
      DesignButton("Retry") { onAction(SpecsAction.Retry) }
    }
    loaded == null -> StateMessage("Loading")
    loaded.roots.isEmpty() -> StateMessage("No specs yet")
    state.rows.isEmpty() && state.filtersActive -> StateMessage("No specs match", "Nothing in this project matches the filter and status.") {
      DesignButton("Clear filters") { onAction(SpecsAction.ClearFilters) }
    }
    else -> SpecsTree(state, onAction, Modifier.fillMaxSize())
  }
}

@Composable
private fun UnreadableFolder(message: String, onAction: (SpecsAction) -> Unit) {
  StateMessage("Can't read the spec folder", message) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      DesignButton("Retry") { onAction(SpecsAction.Retry) }
      DesignButton("Reveal folder") { onAction(SpecsAction.Reveal) }
    }
  }
}

@Composable
private fun StateMessage(title: String, detail: String? = null, actions: @Composable () -> Unit = {}) {
  Column(
    Modifier.fillMaxSize().padding(24.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(title, color = ink, fontFamily = body, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, textAlign = TextAlign.Center)
    detail?.let { Text(it, color = muted, fontFamily = body, fontSize = 12.sp, textAlign = TextAlign.Center) }
    actions()
  }
}

@Composable
private fun Footer(specsRoot: String, onReveal: () -> Unit) {
  Column {
    HorizontalDivider(color = border)
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(specsRoot, Modifier.weight(1f), color = dim, fontFamily = mono, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.StartEllipsis)
      Text(
        "Reveal",
        Modifier.clickable(role = Role.Button, onClick = onReveal),
        color = amber,
        fontFamily = body,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
      )
    }
  }
}
