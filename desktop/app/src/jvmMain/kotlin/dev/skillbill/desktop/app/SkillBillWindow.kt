package dev.skillbill.desktop.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.skillbill.desktop.core.common.Outcome
import dev.skillbill.desktop.core.domain.model.BoardQuery
import dev.skillbill.desktop.core.domain.model.BoardState
import dev.skillbill.desktop.core.domain.model.ControlOutcome
import dev.skillbill.desktop.core.domain.model.DetailSections
import dev.skillbill.desktop.core.domain.model.SpecControlState
import dev.skillbill.desktop.core.domain.model.SpecNode
import dev.skillbill.desktop.core.domain.model.SpecProject
import dev.skillbill.desktop.core.domain.model.SpecRunState
import dev.skillbill.desktop.core.domain.model.SpecTreeState
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.model.WorkKind
import dev.skillbill.desktop.core.domain.repository.SpecRepository
import dev.skillbill.desktop.core.domain.repository.WorkBoardRepository
import dev.skillbill.desktop.core.domain.repository.WorkControlRepository
import dev.skillbill.desktop.core.domain.repository.WorkDetailRepository
import dev.skillbill.desktop.core.domain.settings.HistoryWindow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import skillbill.contracts.workflow.identity.status.WorkPaginationLimits

private val windowBoardQuery = BoardQuery(HistoryWindow.DEFAULT, filter = "", pageSize = WorkPaginationLimits.DEFAULT_PAGE_SIZE)

fun showSkillBillWindow(
  board: WorkBoardRepository,
  details: WorkDetailRepository,
  controls: WorkControlRepository,
  specs: SpecRepository,
) = application {
  Window(onCloseRequest = ::exitApplication, title = "Skill Bill") {
    MainWindow(board, details, controls, specs)
  }
}

@Composable
private fun MainWindow(
  board: WorkBoardRepository,
  details: WorkDetailRepository,
  controls: WorkControlRepository,
  specs: SpecRepository,
) {
  val boardState by produceState<BoardState>(BoardState.Loading, board) {
    board.observe(windowBoardQuery).collect { value = it }
  }
  val reservations by controls.reservations.collectAsState()
  val items = (boardState as? BoardState.Loaded)?.items.orEmpty()
  var selectedKey by remember { mutableStateOf<String?>(null) }
  val selectedItem = items.firstOrNull { it.id == selectedKey } ?: items.firstOrNull()

  Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
    Column(Modifier.width(250.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
      Text("Work", style = MaterialTheme.typography.titleLarge)
      when (val state = boardState) {
        BoardState.Loading -> Text("Loading work…")
        is BoardState.Failed -> Text(state.problem.toString())
        is BoardState.Loaded -> state.sections.forEach { section ->
          if (section.items.isNotEmpty()) {
            Text(section.kind.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
            section.items.forEach { item ->
              TextButton(onClick = { selectedKey = item.id }) {
                Text("${item.issueKey ?: item.title}  ${item.lifecycle}")
              }
            }
          }
        }
      }
    }
    VerticalDivider(Modifier.fillMaxHeight())
    DetailLaunchSurface(selectedItem, details, controls, reservations, Modifier.weight(1f))
    VerticalDivider(Modifier.fillMaxHeight())
    SpecsLaunchSurface(selectedItem, items, specs, controls, reservations, Modifier.width(400.dp))
  }
}

@Composable
private fun DetailLaunchSurface(
  item: WorkItem?,
  details: WorkDetailRepository,
  controls: WorkControlRepository,
  reservations: Set<String>,
  modifier: Modifier = Modifier,
) {
  if (item == null) {
    Column(modifier.padding(8.dp)) { Text("Select work to see its detail.") }
    return
  }
  val detailSections by produceState<DetailSections?>(null, item, details) {
    details.observe(item).collect { value = it.sections }
  }
  val loaded = detailSections as? DetailSections.Loaded
  val options = loaded?.runOptions
  val key = item.issueKey ?: item.id
  val reserved = key in reservations
  var agent by remember(key, item.control.sessionId) { mutableStateOf(options?.agentId) }
  val scope = rememberCoroutineScope()
  var launchResult by remember(key, item.control.sessionId) { mutableStateOf<ControlOutcome?>(null) }

  Column(modifier.padding(8.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(item.title, style = MaterialTheme.typography.titleLarge)
    Text(item.summary)
    Text("${item.repositoryName} · ${item.lifecycle}")
    if (loaded?.stale == true) Text("Detail is out of date. Refresh the work list before launching.")
    if (options != null) {
      val allowed = loaded != null && item.controlEligible && !loaded.stale && options.canLaunch
      TextButton(onClick = {
        val choices = options.agentChoices
        if (choices.isNotEmpty()) agent = choices[(choices.indexOf(agent).coerceAtLeast(-1) + 1) % choices.size]
      }, enabled = allowed && !reserved) {
        Text(agent?.let { "Agent: $it" } ?: "Choose agent")
      }
      Button(
        onClick = {
          if (loaded != null && item.controlEligible && !loaded.stale && options.canLaunch && key !in controls.reservations.value) {
            scope.launch { launchResult = controls.launch(item, agent ?: options.agentId) }
          }
        },
        enabled = allowed && !reserved,
      ) { Text(options.launchLabel) }
      if (reserved) Text("Launch request in progress")
      launchResult?.let { Text(it.launchMessage()) }
    } else if (detailSections == null || detailSections == DetailSections.Pending) {
      Text("Loading run options…")
    } else {
      Text("Run options are unavailable for this item.")
    }
  }
}

@Composable
private fun SpecsLaunchSurface(
  selectedItem: WorkItem?,
  boardItems: List<WorkItem>,
  specs: SpecRepository,
  controls: WorkControlRepository,
  reservations: Set<String>,
  modifier: Modifier = Modifier,
) {
  var projects by remember { mutableStateOf<List<SpecProject>>(emptyList()) }
  var project by remember { mutableStateOf<SpecProject?>(null) }
  var selectedBundle by remember { mutableStateOf<SpecNode.Bundle?>(null) }
  var state by remember { mutableStateOf<SpecTreeState>(SpecTreeState.Loading) }
  var controlState by remember { mutableStateOf<SpecControlState>(SpecControlState.Loading) }
  var loadedControlForPath by remember { mutableStateOf<String?>(null) }
  val scope = rememberCoroutineScope()
  var launchResult by remember(project?.projectId, selectedBundle?.path, selectedItem?.control?.sessionId) {
    mutableStateOf<ControlOutcome?>(null)
  }

  LaunchedEffect(specs, selectedItem?.control?.sessionId) {
    projects = (specs.projects() as? Outcome.Success)?.value.orEmpty()
  }
  LaunchedEffect(selectedItem?.repositoryRoot, projects) {
    val root = selectedItem?.repositoryRoot
    project = if (root == null) projects.firstOrNull()
    else (specs.projectFor(root) as? Outcome.Success)?.value ?: projects.firstOrNull { it.repoRoot == root }
  }
  LaunchedEffect(project?.projectId) {
    val selected = project ?: return@LaunchedEffect
    specs.observeTree(selected.projectId).collect { state = it }
  }
  LaunchedEffect(project?.projectId, selectedItem?.control?.sessionId) {
    selectedBundle = null
    controlState = SpecControlState.Loading
    loadedControlForPath = null
  }
  val tree = (state as? SpecTreeState.Loaded)?.tree
  val bundles = remember(tree) { tree?.roots.orEmpty().flatMap(::bundlesOf) }
  LaunchedEffect(project?.projectId, selectedBundle?.path) {
    val currentProject = project ?: return@LaunchedEffect
    val bundle = selectedBundle ?: return@LaunchedEffect
    loadedControlForPath = bundle.path
    specs.control(dev.skillbill.desktop.core.domain.model.SpecBundleRef(currentProject.projectId, bundle.path, currentProject.repoRoot))
      .collect { controlState = it }
  }
  val loaded = controlState as? SpecControlState.Loaded
  val control = loaded?.control
  val liveGoal = control?.workflowId?.let { workflowId ->
    boardItems.singleOrNull {
      it.kind == WorkKind.Goal && it.id == workflowId && it.control.workflowId == workflowId &&
        it.control.repositoryIdentity == control.repositoryIdentity
    }
  }
  val key = control?.issueKey ?: selectedBundle?.issueKey
  val reserved = key != null && key in reservations
  var agent by remember(key, selectedBundle?.path, selectedItem?.control?.sessionId) { mutableStateOf(control?.agentId) }

  Column(modifier.fillMaxHeight().padding(8.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text("Specs", style = MaterialTheme.typography.titleLarge)
    Text(project?.displayName ?: "Choose a project")
    bundles.forEach { bundle ->
      TextButton(onClick = { selectedBundle = bundle; controlState = SpecControlState.Loading; loadedControlForPath = null }) {
        Text("${bundle.issueKey ?: bundle.name}  ${bundle.title}")
      }
    }
    if (selectedBundle != null && control != null && key != null) {
      Text("${selectedBundle?.issueKey ?: selectedBundle?.name} · ${control.runState}")
      val canLaunch = control.canStart && !reserved && (control.runState == SpecRunState.NotStarted || liveGoal != null)
      TextButton(onClick = {
        val choices = control.agentChoices
        if (choices.isNotEmpty()) agent = choices[(choices.indexOf(agent).coerceAtLeast(-1) + 1) % choices.size]
      }, enabled = canLaunch) { Text(agent?.let { "Agent: $it" } ?: "Choose agent") }
      Button(
        onClick = {
          val current = controlState as? SpecControlState.Loaded ?: return@Button
          val view = current.control
          val bundle = selectedBundle ?: return@Button
          if (loadedControlForPath != bundle.path) return@Button
          val selectedKey = view.issueKey ?: selectedBundle?.issueKey ?: return@Button
          if (!view.canStart || selectedKey in controls.reservations.value) return@Button
          val selectedProject = project ?: return@Button
          scope.launch {
            if (view.runState == SpecRunState.NotStarted) {
              launchResult = selectedProject.repoRoot?.let { controls.startGoal(it, selectedKey, agent ?: view.agentId) }
                ?: ControlOutcome.Failed(dev.skillbill.desktop.core.domain.model.ReadProblem.Runtime("Project directory is unavailable."))
            } else {
              launchResult = liveGoal?.let { controls.launch(it, agent ?: view.agentId) }
            }
          }
        },
        enabled = canLaunch,
      ) { Text(control.startLabel) }
      launchResult?.let { Text(it.launchMessage()) }
    }
  }
}

private fun bundlesOf(node: SpecNode): List<SpecNode.Bundle> = when (node) {
  is SpecNode.Bundle -> listOf(node) + node.children.flatMap(::bundlesOf)
  is SpecNode.Folder -> node.children.flatMap(::bundlesOf)
  is SpecNode.File -> emptyList()
}

private fun ControlOutcome.launchMessage(): String = when (this) {
  ControlOutcome.RequestAccepted -> "Launch request accepted."
  ControlOutcome.AlreadyLaunching -> "Launch request already in progress."
  is ControlOutcome.Refused -> "Launch refused: ${detail ?: rejection.name}."
  is ControlOutcome.Failed -> "Launch failed: $problem"
  else -> "Launch result: $this"
}
