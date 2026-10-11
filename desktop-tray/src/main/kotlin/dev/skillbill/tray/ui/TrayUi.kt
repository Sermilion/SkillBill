package dev.skillbill.tray.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.unit.dp
import dev.skillbill.tray.application.*
import dev.skillbill.tray.domain.*
import dev.skillbill.tray.domain.Retention
import dev.skillbill.tray.presentation.presentPopover
import dev.skillbill.tray.presentation.effectiveFreshness
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant

private val canvas = Color(0xFF182229)
private val panel = Color(0xFF202D35)
private val raised = Color(0xFF293941)
private val ink = Color(0xFFE5EFED)
private val muted = Color(0xFF9EAFB4)
private val mint = Color(0xFF72D5B0)
private val amber = Color(0xFFE8B85D)
private val red = Color(0xFFE47F7D)

@Composable
fun TrayPopover(model: DashboardModel, openMain: () -> Unit, openHistory: () -> Unit, openSettings: () -> Unit, onQuit: () -> Unit) {
  val state by model.state.collectAsState()
  val board = state.board
  val now by tickingNow()
  val view = presentPopover(state, now)
  Column(Modifier.width(390.dp).heightIn(min = 420.dp, max = 620.dp).background(canvas).padding(18.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text("Skill Bill", color = ink, style = MaterialTheme.typography.titleLarge)
        Text(view.pollAgeSeconds?.let { "Updated ${age(Duration.ofSeconds(it))} ago" } ?: "Waiting for the first board", color = muted, style = MaterialTheme.typography.labelMedium)
      }
      TextButton(onClick = model::refresh) { Text("Refresh", color = mint) }
    }
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      Stat("Running", view.running, mint, Modifier.weight(1f))
      Stat("Needs you", view.needsYou, amber, Modifier.weight(1f))
      Stat("Done 24h", view.done24h, ink, Modifier.weight(1f))
    }
    Spacer(Modifier.height(14.dp))
    TabRow(selectedTabIndex = if (state.settings.lastPopoverTab == "Goals") 0 else 1, containerColor = canvas, contentColor = mint) {
      Tab(state.settings.lastPopoverTab == "Goals", { model.chooseTab("Goals") }, text = { Text("${if (view.goals.any(::needsAttention)) "● " else ""}Goals ${view.goals.size}") })
      Tab(state.settings.lastPopoverTab == "Phases", { model.chooseTab("Phases") }, text = { Text("${if (view.livePhases.any(::needsAttention)) "● " else ""}Phases ${view.livePhases.size}") })
    }
    Spacer(Modifier.height(8.dp))
    if (state.problem != null) ProblemBanner(state.problem!!, state.stale)
    val rows = if (state.settings.lastPopoverTab == "Goals") view.goals else view.livePhases
    if (rows.isEmpty() && state.problem == null) EmptyBoard(board == null, state.settings.pollSeconds)
    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
      items(rows, key = { it.identity }) { item -> WorkRow(item, model, now, state.stale) }
      if (state.settings.lastPopoverTab == "Phases" && view.finishedPhases.isNotEmpty()) {
        item { Text("Finished in the last hour", color = muted, style = MaterialTheme.typography.labelMedium) }
        items(view.finishedPhases, key = { it.identity }) { item -> WorkRow(item, model, now, state.stale) }
      }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      TextButton(onClick = openMain) { Text("Open Skill Bill", color = mint) }
      Row {
        TextButton(onClick = openHistory) { Text("History", color = muted) }
        TextButton(onClick = openSettings) { Text("Settings", color = muted) }
        TextButton(onClick = onQuit) { Text("Quit", color = red) }
      }
    }
  }
}

@Composable
private fun Stat(label: String, value: Int, color: Color, modifier: Modifier = Modifier) {
  Column(modifier.background(panel, RoundedCornerShape(10.dp)).padding(vertical = 10.dp, horizontal = 12.dp)) {
    Text(value.toString(), color = color, style = MaterialTheme.typography.titleLarge)
    Text(label, color = muted, style = MaterialTheme.typography.labelSmall)
  }
}

@Composable
private fun WorkRow(item: WorkItem, model: DashboardModel, now: Instant = Instant.now(), boardStale: Boolean = false) {
  val selected by model.state.collectAsState()
  val selectedRow = selected.selected == item.identity
  Row(Modifier.fillMaxWidth().background(if (selectedRow) raised else panel, RoundedCornerShape(9.dp)).clickable { model.select(item.identity) }.padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        LifecycleMark(item.lifecycle, item.planningState?.let { it != "prepared" } == true)
        Text(item.issueKey ?: item.phaseId ?: item.currentStepId, color = ink, style = MaterialTheme.typography.titleSmall)
        if (item.pauseRequested) Text("Pause requested", color = amber, style = MaterialTheme.typography.labelSmall)
        if (effectiveFreshness(item, boardStale) == Freshness.STALE) Text("Stale · updated ${age(Duration.between(item.updatedAt, now).coerceNonNegative())} ago", color = amber, style = MaterialTheme.typography.labelSmall)
      }
      Text(item.summary, color = muted, style = MaterialTheme.typography.bodySmall, maxLines = 1)
      Text("${FileName(item.repositoryRoot)}  /  ${item.branch ?: "no branch"}", color = muted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
      item.activity?.let { Text(it, color = muted, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
      val phaseExecution = item.phaseExecution
      val phase = item.currentPhaseId ?: phaseExecution?.phaseId ?: item.phaseId
      Text("${item.currentStepLabel}${phase?.let { " · $it" }.orEmpty()}${phaseExecution?.let { " · ${it.label()}" }.orEmpty()}", color = ink, style = MaterialTheme.typography.labelSmall)
      val planning = item.planningState?.let { it != "prepared" } == true
      val completed = if (planning) item.plannedSubtasks else item.progressCompleted
      val total = if (planning) item.totalSubtasks else item.progressTotal
      if (completed != null && total != null) Text("${if (planning) "$completed/$total plans saved" else "Progress $completed/$total"}${item.planningWaveSubtaskIds.takeIf { planning && it.isNotEmpty() }?.let { " · planning ${it.joinToString()}" }.orEmpty()}", color = if (planning) mint else muted, style = MaterialTheme.typography.labelSmall)
      item.pauseReason?.let { Text(it, color = amber, style = MaterialTheme.typography.labelSmall, maxLines = 2) }
    }
      Column(horizontalAlignment = Alignment.End) {
        val state by model.state.collectAsState()
        val frozen = boardStale || item.freshness == Freshness.STALE
        state.displayDurations[item.identity]?.goalMs?.let { value ->
          val duration = if (frozen) value else displayedDuration(item.activeDurationMs, item.activeDurationAsOf, now, item.freshness, item.lifecycle == Lifecycle.ACTIVE) ?: value
          Text("G ${age(Duration.ofMillis(duration))}", color = mint, style = MaterialTheme.typography.labelSmall)
        }
        state.displayDurations[item.identity]?.let { timing -> timing.subtaskMs?.let { value ->
          val duration = if (frozen || timing.subtaskId != item.currentSubtaskId) value else displayedDuration(item.subtaskActiveDurationMs, item.subtaskActiveDurationAsOf, now, item.freshness, item.lifecycle == Lifecycle.ACTIVE) ?: value
          Text("S ${age(Duration.ofMillis(duration))}", color = muted, style = MaterialTheme.typography.labelSmall)
        } }
      }
  }
}

@Composable
private fun LifecycleMark(lifecycle: Lifecycle, planning: Boolean = false) {
  val color = when { lifecycle == Lifecycle.BLOCKED -> amber; lifecycle == Lifecycle.FAILED -> red; lifecycle == Lifecycle.PAUSED -> amber; planning -> Color(0xFF8BC7E8); lifecycle == Lifecycle.ACTIVE -> mint; else -> muted }
  Box(Modifier.size(8.dp).background(color, RoundedCornerShape(50)))
}

@Composable
private fun ProblemBanner(problem: Problem, stale: Boolean) {
  Text(if (stale) "${problem.summary} Showing the last successful board as stale." else problem.summary,
    Modifier.fillMaxWidth().background(Color(0xFF3D3527), RoundedCornerShape(8.dp)).padding(10.dp), color = amber, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun EmptyBoard(loading: Boolean, pollSeconds: Int) {
  Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
    Text(if (loading) "Connecting to Skill Bill" else "Nothing to show", color = ink, style = MaterialTheme.typography.titleMedium)
    Text(if (loading) "The tray checks the CLI every $pollSeconds seconds." else "Goals and standalone phases will appear here.", color = muted, style = MaterialTheme.typography.bodySmall)
  }
}

@Composable
fun MainWindow(model: DashboardModel, onHistory: () -> Unit, onSettings: () -> Unit, onClose: () -> Unit, onQuit: () -> Unit) {
  val state by model.state.collectAsState()
  val now by tickingNow()
  val board = state.board
  val all = board?.items.orEmpty()
  val projected = board?.let { project(it, now) }
  var category by remember { mutableStateOf("Needs you") }
  var filter by remember { mutableStateOf("") }
  val categoryItems = when (category) {
    "Needs you" -> all.filter { item -> item.lifecycle == Lifecycle.BLOCKED || item.lifecycle == Lifecycle.FAILED || item.pauseReasonCode == "awaiting_operator_decision" }
    "Goals running" -> all.filter { it.family == WorkFamily.FEATURE_GOAL && it.lifecycle == Lifecycle.ACTIVE }
    else -> projected?.phases.orEmpty()
  }
  val visible = categoryItems.filter { item -> listOfNotNull(item.repositoryRoot, item.issueKey, item.branch).any { it.contains(filter, true) } }
  Row(Modifier.fillMaxSize().background(canvas)) {
    Column(Modifier.width(225.dp).fillMaxHeight().background(panel).padding(16.dp)) {
      Text("Skill Bill", color = ink, style = MaterialTheme.typography.titleLarge)
      Spacer(Modifier.height(24.dp))
      listOf("Needs you", "Goals running", "Standalone phases").forEach { choice ->
        Text(choice, Modifier.fillMaxWidth().background(if (category == choice) raised else Color.Transparent, RoundedCornerShape(7.dp)).clickable { category = choice }.padding(10.dp), color = if (category == choice) mint else ink)
      }
      Spacer(Modifier.weight(1f))
      TextButton(onClick = onHistory) { Text("History", color = muted) }
      TextButton(onClick = onSettings) { Text("Settings", color = muted) }
      TextButton(onClick = model::refresh) { Text("Refresh now", color = mint) }
      TextButton(onClick = onQuit) { Text("Quit", color = red) }
    }
    Column(Modifier.weight(1f).fillMaxHeight().padding(18.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(category, Modifier.weight(1f), color = ink, style = MaterialTheme.typography.headlineSmall)
        TextButton(onClick = onClose) { Text("Hide to tray", color = muted) }
      }
      OutlinedTextField(filter, { filter = it }, Modifier.fillMaxWidth(), label = { Text("Filter by repo, issue, or branch") }, singleLine = true)
      Spacer(Modifier.height(12.dp))
      Row(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.width(340.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
          items(visible, key = { it.identity }) { WorkRow(it, model, now, state.stale) }
        }
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f).fillMaxHeight()) {
          (state.trayProblem ?: state.problem)?.let { ProblemBanner(it, state.stale) }
          DetailPane(state.detail, state.detailProblem, model, Modifier.weight(1f).fillMaxHeight(), now, state.stale)
        }
      }
    }
  }
}

@Composable
private fun DetailPane(detail: Detail?, problem: Problem?, model: DashboardModel, modifier: Modifier = Modifier, now: Instant = Instant.now(), boardStale: Boolean = false) {
  val clipboard = LocalClipboardManager.current
  Column(modifier.verticalScroll(rememberScrollState()).background(panel, RoundedCornerShape(12.dp)).padding(18.dp)) {
    if (problem != null) Text(problem.summary, color = amber)
    model.state.collectAsState().value.actionProblem?.let { Text(it.summary, color = amber) }
    if (detail == null) {
      Text("Select a goal or phase", color = muted)
      return@Column
    }
    val item = detail.item
    Text(detail.featureTitle ?: item.summary, color = ink, style = MaterialTheme.typography.titleLarge)
    if (item.lifecycle == Lifecycle.BLOCKED) {
      Spacer(Modifier.height(10.dp))
      Text(item.pauseReason ?: "Waiting for an operator decision", color = amber)
      item.issueKey?.let { key -> CopyLine("skill-bill $key resume", "Blocked resume command", clipboard::setText) }
    }
    val dashboardState = model.state.collectAsState().value
    val effectiveStale = effectiveFreshness(item, boardStale, dashboardState.detailExtrasStale) == Freshness.STALE
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      AssistChip(onClick = {}, label = { Text(item.lifecycle.name.lowercase()) })
      AssistChip(onClick = {}, label = { Text(item.issueKey ?: item.phaseId ?: "standalone phase") })
      item.family?.let { family -> AssistChip(onClick = {}, label = { Text(family.name.lowercase().replace('_', ' ')) }) }
      AssistChip(onClick = {}, label = { Text(if (effectiveStale) "stale · ${age(Duration.between(item.updatedAt, now).coerceNonNegative())} old" else item.freshness.name.lowercase()) })
    }
    Spacer(Modifier.height(12.dp))
    Text("${item.repositoryRoot}\n${item.branch ?: "Branch not available"}", color = muted)
    val currentItem = dashboardState.board?.items?.singleOrNull { it.identity == item.identity }
    if (currentItem != null && !boardStale && eligibleForControl(currentItem)) {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { model.perform("pause", item.identity) }, enabled = !currentItem.pauseRequested) { Text("Pause after current subtask") }
        OutlinedButton(onClick = { model.perform("stop", item.identity) }) { Text("Stop goal") }
      }
    }
    item.issueKey?.let { key -> CopyLine("skill-bill goal status $key --repo-root ${shellQuote(item.repositoryRoot)}", "Goal status command", clipboard::setText) }
    HorizontalDivider(Modifier.padding(vertical = 10.dp), color = raised)
    val goalSubtaskTotal = item.progressTotal ?: detail.subtasks.size.takeIf { it > 0 }
    if (item.family == WorkFamily.FEATURE_GOAL) {
      item.currentSubtaskId?.let { id -> Text("Current subtask   $id of ${goalSubtaskTotal ?: "not available"}${item.currentSubtaskStartedAt?.let { " · started ${age(Duration.between(it, now).coerceNonNegative())} ago" }.orEmpty()}", color = ink) }
      if (item.planningState?.let { it != "prepared" } == true) {
        Text("Planning progress   ${item.plannedSubtasks ?: "not available"}/${item.totalSubtasks ?: "not available"}", color = ink)
      } else {
        Text("Completed progress   ${item.progressCompleted ?: "not available"}/${item.progressTotal ?: "not available"}", color = ink)
      }
    }
    val execution = item.phaseExecution
    Text(execution?.let { "Phase   ${it.phaseId} · ${it.label()}" } ?: "Phase   ${item.currentPhaseId ?: detail.currentPhase ?: item.currentStepLabel}", color = muted)
    item.currentModel?.let { Text("Model   $it${item.currentEffort?.let { effort -> " · $effort" }.orEmpty()}", color = muted) }
    item.startedAt?.let { Text("Started   ${age(Duration.between(it, now).coerceNonNegative())} ago", color = muted) }
    item.workflowId?.let { Text("Workflow   $it", color = muted) }
    item.invocationId?.let { Text("Invocation   $it", color = muted) }
    Text("Last update   ${age(Duration.between(item.updatedAt, now).coerceNonNegative())} ago", color = muted)
    item.lastAgentActivityLabel?.let { label -> Text("Last agent activity   $label${item.lastAgentActivityAt?.let { " · ${age(Duration.between(it, now).coerceNonNegative())} ago" }.orEmpty()}", color = muted) }
    val timing = dashboardState.displayDurations[item.identity]
    val freezeClocks = boardStale || item.freshness == Freshness.STALE
    val goalDuration = if (freezeClocks) timing?.goalMs else null
    val subtaskDuration = if (freezeClocks) timing?.subtaskMs else null
    Text("Goal time   ${durationText(item.activeDurationMs, item.activeDurationAsOf, item, now, freezeClocks, goalDuration)}", color = muted)
    Text("Subtask time   ${durationText(item.subtaskActiveDurationMs, item.subtaskActiveDurationAsOf, item, now, freezeClocks, subtaskDuration)}", color = muted)
    if (detail.stepPipeline.isNotEmpty()) Pipeline("Step pipeline", detail.stepPipeline)
    if (detail.goalFinishPipeline.isNotEmpty()) Pipeline("Goal finish", detail.goalFinishPipeline)
    if (detail.phases.isNotEmpty()) {
      Text("Current subtask phases", color = muted, style = MaterialTheme.typography.labelMedium)
      Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        detail.phases.forEach { phase ->
          val executionLabel = execution?.takeIf { it.phaseId == phase.id }?.label()?.let { " · $it" }.orEmpty()
          AssistChip(onClick = {}, label = { Text("${phase.id} · ${if (phase.finished) "done" else phase.status}$executionLabel") })
        }
      }
    }
    if (detail.subtasks.isNotEmpty()) {
      Text("Subtasks", color = muted, style = MaterialTheme.typography.labelMedium)
      detail.subtasks.forEach { subtask ->
        Text("${subtask.id}. ${subtask.name} · ${subtask.status}${subtask.commitSha?.let { " · ${it.take(8)}" }.orEmpty()}", color = ink, style = MaterialTheme.typography.bodySmall)
        subtask.blockedReason?.let { Text(it, color = amber, style = MaterialTheme.typography.labelSmall) }
        if (subtask.dependencies.isNotEmpty()) Text("Depends on ${subtask.dependencies.joinToString { dependency -> "#${dependency.subtaskId}${if (dependency.optional) " optional" else ""}${if (dependency.skipped) " skipped" else ""}" }}", color = muted, style = MaterialTheme.typography.labelSmall)
      }
    }
    val executionRows = listOfNotNull(
      item.workflowId?.let { "Workflow" to it }, item.invocationId?.let { "Invocation" to it },
      detail.activeAgent?.let { "Agent" to it }, item.currentModel?.let { "Model" to "$it${item.currentEffort?.let { effort -> " · $effort" }.orEmpty()}" },
      detail.executionLiveness?.let { "Liveness" to it }, item.startedAt?.let { "Started" to it.toString() },
      item.updatedAt.let { "Last update" to it.toString() },
    )
    if (executionRows.isNotEmpty()) LabeledPanel("Execution", executionRows)
    val event = detail.latestActivity.event
    val edit = detail.latestActivity.edit
    val activityRows = buildList {
      item.lastAgentActivityLabel?.let { add("Agent" to "$it${item.lastAgentActivityAt?.let { time -> " · ${age(Duration.between(time, now).coerceNonNegative())} ago" }.orEmpty()}") }
      event?.workflowPhase?.let { add("Phase" to it) }
      event?.workerRole?.let { add("Worker" to it) }
      event?.summary?.let { add("Activity" to it) }
      event?.at?.let { add("Event age" to "${age(Duration.between(it, now).coerceNonNegative())} ago") }
      edit?.paths?.takeIf { it.isNotEmpty() }?.let { add("Edited paths" to it.joinToString(", ")) }
      edit?.let { add("Changes" to "+${it.insertions ?: 0} / −${it.deletions ?: 0}") }
      edit?.recordedAt?.let { add("Edit age" to "${age(Duration.between(it, now).coerceNonNegative())} ago") }
    }
    if (activityRows.isNotEmpty()) LabeledPanel("Latest activity", activityRows)
    if (item.isPhase() && item.activity != null) LabeledPanel("Now", listOfNotNull("Activity" to item.activity, item.lastAgentActivityAt?.let { "Activity age" to "${age(Duration.between(it, now).coerceNonNegative())} ago" }))
    val hasAttemptEvidence = detail.attempts.phaseAttempts.values.any { it > 0 } || detail.attempts.fixIterations.values.any { it > 0 } ||
      detail.attempts.findingsInScope?.let { it > 0 } == true || detail.attempts.reAttemptCauses.values.any { it > 0 } || detail.attempts.supervisorKills > 0
    val attempts = if (!hasAttemptEvidence) emptyList() else buildList {
      detail.attempts.phaseAttempts.forEach { (key, value) -> add("$key attempts" to value.toString()) }
      detail.attempts.fixIterations.forEach { (key, value) -> add("$key fix iterations" to value.toString()) }
      detail.attempts.findingsInScope?.let { add("Findings in scope" to it.toString()) }
      detail.attempts.reAttemptCauses.forEach { (key, value) -> add("Re-attempt · $key" to value.toString()) }
      add("Supervisor kills" to detail.attempts.supervisorKills.toString())
    }
    if (attempts.isNotEmpty()) LabeledPanel("Attempts and fixes", attempts)
    val validationRows = detail.validation.flatMap { record -> buildList {
      add("Subtask ${record.subtaskId}" to when { record.integrityProblem != null -> "integrity problem"; record.passed == true -> "passed"; record.passed == false -> "failed"; else -> "unknown" })
      record.result?.let { result ->
        add("Checks" to "${result.checks.size} · ${result.status}")
        result.gates.forEachIndexed { index, gate -> add("Gate ${index + 1}" to "${gate.outcome} · ${gate.durationMs} ms · ${gate.cacheMode} · ${gate.executedWorkUnits} work units${gate.executedChecks.takeIf { it.isNotEmpty() }?.let { " · ${it.joinToString()}" }.orEmpty()}${gate.exitCode?.let { " · exit $it" }.orEmpty()}${gate.command?.let { " · $it" }.orEmpty()}") }
      }
      record.integrityProblem?.let { add("Integrity" to it) }
      record.evidence.forEach { add("Command" to "${it.command} · exit ${it.exitCode}") }
    } }
    if (validationRows.isNotEmpty()) LabeledPanel("Validation", validationRows)
  }
}

@Composable
private fun Pipeline(label: String, steps: List<String>) {
  Column(Modifier.padding(top = 10.dp)) {
    Text(label, color = muted, style = MaterialTheme.typography.labelMedium)
    Text(steps.joinToString("  →  "), color = ink)
  }
}

@Composable
private fun CopyLine(value: String, label: String, copy: (AnnotatedString) -> Unit) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    SelectionContainer(Modifier.weight(1f)) { Text("$label: $value", color = muted, style = MaterialTheme.typography.bodySmall) }
    TextButton(onClick = { copy(AnnotatedString(value)) }) { Text("Copy", color = mint) }
  }
}

@Composable
private fun LabeledPanel(title: String, rows: List<Pair<String, String>>) {
  if (rows.isEmpty()) return
  Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).background(raised, RoundedCornerShape(8.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
    Text(title, color = mint, style = MaterialTheme.typography.labelMedium)
    rows.forEach { (label, value) -> Row(Modifier.fillMaxWidth()) { Text(label, Modifier.width(130.dp), color = muted, style = MaterialTheme.typography.labelSmall); Text(value, Modifier.weight(1f), color = ink, style = MaterialTheme.typography.bodySmall) } }
  }
}

@Composable
fun HistoryWindow(state: DashboardState, model: DashboardModel, onClose: () -> Unit) {
  val now by tickingNow()
  var filter by remember { mutableStateOf("All") }
  val cutoff = now.minus(state.settings.retention.duration)
  val items = state.board?.items.orEmpty().filter { it.finishedAt?.let { time -> !time.isBefore(cutoff) } == true }.sortedByDescending { it.finishedAt }.filter {
    filter == "All" || (filter == "Goals" && it.family == WorkFamily.FEATURE_GOAL) || (filter == "Phases" && it.isPhase())
  }
  Column(Modifier.width(520.dp).height(600.dp).background(canvas).padding(18.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) { Text("History", Modifier.weight(1f), color = ink, style = MaterialTheme.typography.titleLarge); TextButton(onClick = onClose) { Text("Close") } }
    Row { listOf("All", "Goals", "Phases").forEach { TextButton(onClick = { filter = it }) { Text(it, color = if (filter == it) mint else muted) } } }
    Text("Finished within ${state.settings.retention.cliValue}", color = muted)
    LazyColumn { items(items, key = { it.identity }) { WorkRow(it, model, now, state.stale) } }
  }
}

@Composable
fun SettingsWindow(settings: AppSettings, onChange: (AppSettings) -> Unit, problem: Problem?, onClose: () -> Unit) {
  var cli by remember(settings.cliOverride) { mutableStateOf(settings.cliOverride.orEmpty()) }
  Column(Modifier.width(460.dp).heightIn(max = 680.dp).background(canvas).padding(20.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) { Text("Settings", Modifier.weight(1f), color = ink, style = MaterialTheme.typography.titleLarge); TextButton(onClick = onClose) { Text("Close") } }
    problem?.let { Text(it.summary, color = amber, style = MaterialTheme.typography.bodySmall) }
    OutlinedTextField(cli, { cli = it }, Modifier.fillMaxWidth(), label = { Text("Skill Bill CLI path override") }, singleLine = true)
    Text("Check every", color = ink)
    Row { listOf(5, 15, 30, 60).forEach { value -> FilterChip(settings.pollSeconds == value, { onChange(settings.copy(pollSeconds = value)) }, label = { Text("${value}s") }) } }
    Text("History retention", color = ink)
    Row { Retention.entries.forEach { value -> FilterChip(settings.retention == value, { onChange(settings.copy(retention = value)) }, label = { Text(value.cliValue) }) } }
    Text("Notifications", color = ink)
    allowedTransitions.forEach { key -> Row(verticalAlignment = Alignment.CenterVertically) {
      Text(key.replace('_', ' '), Modifier.weight(1f), color = muted)
      Switch(settings.notifications[key] ?: defaultNotifications.getValue(key), { enabled -> onChange(settings.copy(notifications = settings.notifications + (key to enabled))) })
    } }
    Row(verticalAlignment = Alignment.CenterVertically) { Text("Start at login", Modifier.weight(1f), color = ink); Switch(settings.startAtLogin, { onChange(settings.copy(startAtLogin = it)) }) }
    Button(onClick = { onChange(settings.copy(cliOverride = cli.ifBlank { null })) }) { Text("Save settings") }
  }
}

private fun age(duration: Duration): String = when {
  duration.toHours() > 0 -> "${duration.toHours()}h ${duration.toMinutesPart()}m"
  duration.toMinutes() > 0 -> "${duration.toMinutes()}m ${duration.toSecondsPart()}s"
  else -> "${duration.seconds.coerceAtLeast(0)}s"
}
private fun Duration.coerceNonNegative(): Duration = if (isNegative) Duration.ZERO else this
private fun PhaseExecution.label(): String = when (kind) {
  PhaseExecutionKind.PASS -> "pass $count"
  PhaseExecutionKind.SEMANTIC_LOOP -> "loop $count"
  PhaseExecutionKind.GATE_RUN -> "gate run $count"
  PhaseExecutionKind.BOUNDED_EDGE -> total?.let { "$count/$it" } ?: "edge $count"
  PhaseExecutionKind.ATTEMPT -> "attempt $count"
}
private fun durationText(value: Long?, asOf: Instant?, item: WorkItem, now: Instant, boardStale: Boolean, frozen: Long? = null): String = (if (boardStale) frozen else value?.let { displayedDuration(it, asOf, now, item.freshness, item.lifecycle == Lifecycle.ACTIVE) })?.let { age(Duration.ofMillis(it)) } ?: "not available"
private fun needsAttention(item: WorkItem) = item.lifecycle == Lifecycle.BLOCKED || item.lifecycle == Lifecycle.FAILED || item.pauseReasonCode == "awaiting_operator_decision"
private fun FileName(path: String) = path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')
private fun shellQuote(path: String): String = if (System.getProperty("os.name").startsWith("Windows", true)) "\"${path.replace("\"", "\\\"")}\"" else "'${path.replace("'", "'\\''")}'"

@Composable
private fun tickingNow(): State<Instant> {
  val now = remember { mutableStateOf(Instant.now()) }
  LaunchedEffect(Unit) { while (true) { delay(1000); now.value = Instant.now() } }
  return now
}

