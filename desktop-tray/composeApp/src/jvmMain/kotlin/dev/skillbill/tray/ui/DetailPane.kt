package dev.skillbill.tray.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skillbill.tray.application.*
import dev.skillbill.tray.core.designsystem.*
import dev.skillbill.tray.core.domain.model.*
import dev.skillbill.tray.presentation.effectiveFreshness
import dev.skillbill.tray.presentation.phasePipeline
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlin.time.toJavaInstant

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DetailPane(detail: Detail?, problem: Problem?, model: DashboardModel, modifier: Modifier, now: Instant, boardStale: Boolean) {
  if (detail == null) return
  val state by model.state.collectAsState()
  val scroll = rememberScrollState()
  val item = detail.item
  val pipeline = phasePipeline(detail)
  val planning = item.planningState?.let { it != "prepared" } == true
  val subtaskKeys = detail.subtasks.map { "${item.identity.repositoryIdentity}:${item.identity.id}:subtask:${it.id}" } +
    listOfNotNull("${item.identity.repositoryIdentity}:${item.identity.id}:goal-finish".takeIf { detail.goalFinishPipeline.isNotEmpty() })
  val dependencyKeys = detail.dependencies.map { "${item.identity.repositoryIdentity}:${item.identity.id}:dependency:${it.subtaskId}:${it.dependency.subtaskId}" } +
    listOf("${item.identity.repositoryIdentity}:${item.identity.id}:dependency-header", "${item.identity.repositoryIdentity}:${item.identity.id}:dependency-footer")
  val executionKeys = detail.executionHistory.map { "${item.identity.repositoryIdentity}:${item.identity.id}:execution:${it.invocationId}:${it.executionId}" }
  val validationKeys = detail.validation.map { record ->
    "${item.identity.repositoryIdentity}:${item.identity.id}:validation:${record.subtaskId}:" +
      record.evidence.joinToString("|") { "${it.command}:${it.exitCode}" }
  } +
    "${item.identity.repositoryIdentity}:${item.identity.id}:validation-header"
  val subtaskScroll = rememberAnchoredListState(item.identity, item.databaseGeneration, "subtasks", subtaskKeys)
  val dependencyScroll = rememberAnchoredListState(item.identity, item.databaseGeneration, "dependencies", dependencyKeys)
  val executionScroll = rememberAnchoredListState(item.identity, item.databaseGeneration, "executions", executionKeys)
  val validationScroll = rememberAnchoredListState(item.identity, item.databaseGeneration, "validation", validationKeys)
  val clipboard = LocalClipboardManager.current
  val fresh = effectiveFreshness(item, boardStale, state.detailExtrasStale) == Freshness.FRESH
  val current = state.board?.items?.singleOrNull { it.identity == item.identity }
  val total = item.progressTotal ?: detail.subtasks.size.takeIf { it > 0 }
  val timing = state.displayDurations[item.identity]
  val frozen = !fresh
  val standalone = item.scope == ExecutionScope.STANDALONE_PHASE
  val blockReason = item.exactPauseReason
    ?: detail.executionHistory.firstOrNull { it.invocationId == item.invocationId }?.result
    ?: item.pauseReason
    ?: item.summary
  val copyReason = if (item.family == WorkFamily.FEATURE_GOAL) item.exactPauseReason else blockReason
  val goalBlocked = item.family == WorkFamily.FEATURE_GOAL && item.lifecycle == Lifecycle.BLOCKED
  val goalBlockReason = copyReason.takeIf { goalBlocked }
  val subtaskTime = durationText(item.subtaskActiveDurationMs, item.subtaskActiveDurationAsOf, item, now, frozen, timing?.subtaskMs)
  Column(modifier.background(canvas)) {
    Row(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(canvas, mint.copy(alpha = .04f)))).padding(start = 26.dp, end = 26.dp, top = 20.dp, bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          StatusChip(if (item.lifecycle == Lifecycle.CANCELED) "Canceled" else item.lifecycle.name.lowercase(), lifecycleColor(item.lifecycle), item.lifecycle)
          (item.issueKey ?: item.phaseId)?.let { StatusChip(it, muted) }
          StatusChip(if (item.family == WorkFamily.FEATURE_GOAL) "goal" else "phase", Color(0xFFF49463))
          StatusChip(if (fresh) "fresh" else "stale", if (fresh) green else muted)
        }
        Text(detailTitle(detail), color = ink, fontFamily = display, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 28.8.sp, letterSpacing = (-.48).sp)
        Text("${item.repositoryTitle()} · ${item.branch ?: "no branch"} · ${displayPath(item.repositoryRoot)}", color = muted, fontFamily = mono, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        listOfNotNull(problem, state.actionProblem).forEach { Text(it.summary, color = orange, style = MaterialTheme.typography.bodySmall) }
        if (item.lifecycle == Lifecycle.BLOCKED || item.lifecycle == Lifecycle.PAUSED) {
          Column(Modifier.fillMaxWidth().background(orange.copy(alpha = .12f), RoundedCornerShape(10.dp)).border(1.dp, orange.copy(alpha = .4f), RoundedCornerShape(10.dp)).padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
              ReasonTooltip(if (item.family == WorkFamily.FEATURE_GOAL) item.summary else blockReason, Modifier.weight(1f), color = amber, fontSize = 13.sp, lineHeight = 19.sp)
              DesignButton("Copy reason", enabled = copyReason != null) { copyReason?.let { clipboard.setText(AnnotatedString(it)) } }
            }
          }
        }
      }
      if (current != null && !boardStale) {
        RunActionButtons(detail, model, item.identity in state.actionsInProgress)
      }
    }
    HorizontalDivider(color = border)
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
      DetailMetric(if (item.family == WorkFamily.FEATURE_GOAL) "Subtask" else "Phase", item.currentSubtaskId?.let { "$it/${total ?: "?"}" } ?: item.phaseId ?: "Goal finish", "${detail.completeCount} complete · ${detail.pendingCount} pending", Modifier.weight(1f), item.currentSubtaskId != null)
      MetricDivider()
      DetailMetric(if (standalone) "Step" else "Phase", if (planning) item.currentStepLabel else if (item.family == WorkFamily.FEATURE_GOAL && item.currentSubtaskId == null) item.currentStepLabel else item.currentPhaseId ?: detail.currentPhase ?: item.currentStepLabel, if (planning) "${item.plannedSubtasks ?: 0}/${item.totalSubtasks ?: "?"} plans saved" else item.phaseExecution?.label() ?: "", Modifier.weight(1f))
      MetricDivider()
      val duration = detailDuration(item, now, frozen, timing?.goalMs)
      DetailMetric(duration.label, duration.value, duration.note, Modifier.weight(1f))
      if (!standalone) {
        MetricDivider()
        DetailMetric("Subtask active", subtaskTime, if (frozen) "last known duration" else if (item.lifecycle == Lifecycle.ACTIVE) "ticking" else "", Modifier.weight(1f))
      }
    }
    HorizontalDivider(color = border)
    Row(Modifier.weight(1f).fillMaxWidth()) {
      Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(scroll).padding(horizontal = 26.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (detail.subtasks.isNotEmpty()) {
          Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Subtasks", color = ink, fontFamily = display, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text("from the goal manifest", color = dim, fontFamily = mono, fontSize = 11.sp)
          }
          Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            detail.subtasks.forEach { task -> Box(Modifier.weight(1f).height(5.dp).background(subtaskColor(task.status), RoundedCornerShape(2.dp))) }
          }
          LazyColumn(state = subtaskScroll, modifier = Modifier.heightIn(max = 420.dp)) {
            items(detail.subtasks, key = { "${item.identity.repositoryIdentity}:${item.identity.id}:subtask:${it.id}" }) { task ->
              val active = !planning && task.id.toString() == item.currentSubtaskId && item.lifecycle == Lifecycle.ACTIVE && item.currentStepId !in setOf("pending_launch", "initializing")
              val pending = task.status == "pending"
              val taskInk = ink.copy(alpha = if (pending && !active) .55f else 1f)
              Row(Modifier.fillMaxWidth().background(if (active) blue.copy(alpha = .06f) else Color.Transparent, RoundedCornerShape(8.dp)).padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(task.id.toString().padStart(2, '0'), Modifier.width(18.dp), color = if (active) blue else dim, fontFamily = mono, fontSize = 11.sp, lineHeight = 16.5.sp)
                WorkDot(if (active) Lifecycle.ACTIVE else if (task.status in setOf("complete", "completed")) Lifecycle.TERMINAL else if (task.status == "blocked") Lifecycle.BLOCKED else if (task.status == "failed") Lifecycle.FAILED else Lifecycle.IDLE, Modifier.padding(top = 5.dp))
                Column(Modifier.weight(1f)) {
                  Text(task.name, color = taskInk, fontFamily = body, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp, lineHeight = 18.75.sp)
                  val validation = detail.validation.firstOrNull { it.subtaskId == task.id }
                  Text(listOfNotNull(task.status, task.commitSha?.take(7), if (active) item.phaseExecution?.let { "${it.phaseId} ${it.label()}" } else null, if (active) item.currentModel else null, if (active) item.currentEffort else null, validation?.passed?.let { if (it) "validation passed" else "validation failed" }, task.dependencies.takeIf { pending && it.isNotEmpty() }?.joinToString { "after #${it.subtaskId}" }).joinToString(" · "), color = if (active) blue else dim, fontFamily = mono, fontSize = 11.sp, lineHeight = 16.5.sp)
                  task.blockedReason?.let { SelectionContainer { Text(it, color = orange, fontSize = 12.sp, lineHeight = 18.sp) } }
                  if (active && pipeline.isNotEmpty()) FlowRow(Modifier.padding(top = 9.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    pipeline.forEach { phase -> PhaseChip(phase.id, phase.finished, phase.id == (item.currentPhaseId ?: detail.currentPhase), phase.attemptCount) }
                  }
                }
                if (active) Text(subtaskTime, color = blue, fontFamily = mono, fontSize = 11.sp, lineHeight = 16.5.sp)
              }
              HorizontalDivider(color = border)
            }
            this.item(key = "${item.identity.repositoryIdentity}:${item.identity.id}:goal-finish") { if (detail.goalFinishPipeline.isNotEmpty()) Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
              Text("—", Modifier.width(18.dp), color = dim, fontFamily = mono, fontSize = 11.sp)
              WorkDot(Lifecycle.IDLE, Modifier.padding(top = 5.dp))
              Column(Modifier.weight(1f)) {
                Text("Goal finish", color = ink.copy(alpha = .55f), fontFamily = body, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
                FlowRow(Modifier.padding(top = 9.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) { detail.goalFinishPipeline.forEach { PhaseChip(it, false, it == item.currentStepId, 0) } }
              }
            } }
          }
          PageFooter(detail.cursors[DetailList.SUBTASKS] != null, state.detailLoadStates[DetailList.SUBTASKS] == ListLoadState.APPENDING,
            state.detailLoadStates[DetailList.SUBTASKS] == ListLoadState.APPEND_FAILED) { model.loadMoreDetail(DetailList.SUBTASKS) }
        } else if (detail.stepPipeline.isNotEmpty()) {
          DesignLabel("Phase pipeline")
          FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) { detail.stepPipeline.forEach { PhaseChip(it, false, it == item.currentStepId, 0) } }
        }
        if (detail.dependencies.isNotEmpty() || detail.cursors[DetailList.DEPENDENCIES] != null) {
          LazyColumn(state = dependencyScroll, modifier = Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            this.item(key = "${item.identity.repositoryIdentity}:${item.identity.id}:dependency-header") { DesignLabel("Dependencies") }
            items(detail.dependencies, key = { "${item.identity.repositoryIdentity}:${item.identity.id}:dependency:${it.subtaskId}:${it.dependency.subtaskId}" }) { entry ->
              val flags = listOfNotNull(if (entry.dependency.optional) "optional" else null,
                if (entry.dependency.skipped) "skipped" else null).joinToString(" · ")
              val suffix = if (flags.isEmpty()) "" else " · $flags"
              Text("#${entry.subtaskId} depends on #${entry.dependency.subtaskId}$suffix", color = dim, fontFamily = mono, fontSize = 11.sp)
            }
            this.item(key = "${item.identity.repositoryIdentity}:${item.identity.id}:dependency-footer") { PageFooter(detail.cursors[DetailList.DEPENDENCIES] != null,
              state.detailLoadStates[DetailList.DEPENDENCIES] == ListLoadState.APPENDING,
              state.detailLoadStates[DetailList.DEPENDENCIES] == ListLoadState.APPEND_FAILED) { model.loadMoreDetail(DetailList.DEPENDENCIES) } }
          }
        }
        PageFooter(detail.cursors[DetailList.PHASES] != null, state.detailLoadStates[DetailList.PHASES] == ListLoadState.APPENDING,
          state.detailLoadStates[DetailList.PHASES] == ListLoadState.APPEND_FAILED) { model.loadMoreDetail(DetailList.PHASES) }
        val event = detail.latestActivity.event
        val edit = detail.latestActivity.edit
        val activity = buildList {
          item.lastAgentActivityLabel?.let { add("Agent activity" to "$it${item.lastAgentActivityAt?.let { at -> " · ${age((now - at).coerceNonNegative())} ago" }.orEmpty()}") }
          edit?.let { add("Last edit" to "${it.paths.joinToString { path -> "…/" + path.substringAfterLast('/') }} +${it.insertions ?: 0} −${it.deletions ?: 0}${it.recordedAt?.let { at -> " · ${age((now - at).coerceNonNegative())} ago" }.orEmpty()}") }
          event?.let { add("Event" to listOfNotNull(it.workflowPhase, it.workerRole, it.summary).joinToString(" · ")) }
          item.activity?.let { add("Phase event" to it) }
        }
        if (activity.isNotEmpty() || detail.executionHistory.isNotEmpty() || goalBlocked) Column(Modifier.fillMaxWidth().padding(top = 4.dp).background(panel, RoundedCornerShape(10.dp)).border(1.dp, border, RoundedCornerShape(10.dp)).padding(horizontal = 15.dp, vertical = 13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          DesignLabel(if (standalone) "Phase activity" else "Goal activity")
          goalBlockReason?.let { reason ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
              Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Blocked · ${formatInstant(item.updatedAt, "d MMM HH:mm:ss")}", color = orange, fontSize = 12.sp)
                ReasonTooltip(reason, color = orange)
              }
              DesignButton("Copy reason") { clipboard.setText(AnnotatedString(reason)) }
            }
          }
          if (goalBlocked && goalBlockReason == null) Text("The full block reason was not saved for this attempt.", color = muted, fontSize = 12.sp)
          activity.forEach { (label, value) -> DesignKv(label, value) }
          LazyColumn(state = executionScroll, modifier = Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(detail.executionHistory, key = { "${item.identity.repositoryIdentity}:${item.identity.id}:execution:${it.invocationId}:${it.executionId}" }) { instance ->
              val index = detail.executionHistory.indexOf(instance)
              HorizontalDivider(color = border)
              Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
              Text("Run ${detail.executionHistory.size - index} · ${if (standalone) instance.lifecycleState else "Invocation"} · ${formatInstant(instance.startedAt, "d MMM HH:mm:ss")}${if (index == 0) " · latest run" else ""}", color = ink, fontFamily = body, fontSize = 12.sp)
              SelectionContainer { Text(instance.invocationId, color = muted, fontFamily = mono, fontSize = 11.sp) }
              SelectionContainer { Text("Execution ${instance.executionId}", color = dim, fontFamily = mono, fontSize = 11.sp) }
              Text("${if (instance.finishedAt != null) "Finished" else "Last update"} ${formatInstant(instance.finishedAt ?: instance.updatedAt, "d MMM HH:mm:ss")}", color = muted, fontSize = 11.sp)
              instance.result?.let { reason -> ReasonTooltip(reason) }
              }
            }
          }
        }
        PageFooter(detail.cursors[DetailList.EXECUTIONS] != null, state.detailLoadStates[DetailList.EXECUTIONS] == ListLoadState.APPENDING,
          state.detailLoadStates[DetailList.EXECUTIONS] == ListLoadState.APPEND_FAILED) { model.loadMoreDetail(DetailList.EXECUTIONS) }
      }
      Box(Modifier.width(1.dp).fillMaxHeight().background(border))
      Column(Modifier.width(290.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        MetadataPanel("Execution", listOfNotNull(
          item.workflowId?.let { "Workflow" to it }, item.invocationId?.let { "Invocation" to it },
          detail.activeAgent?.let { "Agent" to it }, item.currentModel?.let { "Model" to "$it${item.currentEffort?.let { effort -> " · $effort" }.orEmpty()}" },
          detail.executionLiveness?.let { "Liveness" to it }, item.startedAt?.let { "Started" to formatInstant(it, "d MMM HH:mm") },
          "Last update" to formatInstant(item.updatedAt, "HH:mm:ss"),
        ))
        val attempts = buildList {
          detail.attempts.phaseAttempts.filterValues { it > 0 }.takeIf { it.isNotEmpty() }?.let { add("Attempts" to it.entries.joinToString(" · ") { entry -> "${entry.key} ${entry.value}" }) }
          detail.attempts.fixIterations.filterValues { it > 0 }.takeIf { it.isNotEmpty() }?.let { add("Fix iterations" to it.entries.joinToString(" · ") { entry -> "${entry.key} ${entry.value}" }) }
          detail.attempts.findingsInScope?.let { add("Findings in scope" to it.toString()) }
          detail.attempts.reAttemptCauses.filterValues { it > 0 }.takeIf { it.isNotEmpty() }?.let { add("Re-attempts" to it.entries.joinToString(" · ") { entry -> "${entry.key} ${entry.value}" }) }
          if (detail.attempts.supervisorKills > 0) add("Supervisor kills" to detail.attempts.supervisorKills.toString())
        }
        MetadataPanel("Attempts and fixes", attempts)
        if (detail.validation.isNotEmpty()) {
          LazyColumn(state = validationScroll, modifier = Modifier.heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            this.item(key = "${item.identity.repositoryIdentity}:${item.identity.id}:validation-header") { DesignLabel("Validation") }
            items(detail.validation, key = { record ->
              "${item.identity.repositoryIdentity}:${item.identity.id}:validation:${record.subtaskId}:" +
                record.evidence.joinToString("|") { "${it.command}:${it.exitCode}" }
            }) { record ->
              Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                WorkDot(if (record.passed == true) Lifecycle.TERMINAL else if (record.passed == false) Lifecycle.FAILED else Lifecycle.IDLE)
                Text("Subtask ${record.subtaskId}", Modifier.width(72.dp), color = ink, fontFamily = body, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp)
                Text(record.integrityProblem ?: "${when (record.passed) { true -> "passed"; false -> "failed"; null -> "unknown" }}${record.result?.let { " · ${it.checks.size} checks" }.orEmpty()}", modifier = Modifier.weight(1f), color = dim, fontFamily = mono, fontSize = 11.sp, lineHeight = 16.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End)
              }
            }
          }
          PageFooter(detail.cursors[DetailList.VALIDATION] != null, state.detailLoadStates[DetailList.VALIDATION] == ListLoadState.APPENDING,
            state.detailLoadStates[DetailList.VALIDATION] == ListLoadState.APPEND_FAILED) { model.loadMoreDetail(DetailList.VALIDATION) }
          HorizontalDivider(color = border)
        }
      }
    }
  }
}

@Composable
private fun DetailMetric(label: String, value: String, hint: String, modifier: Modifier, accent: Boolean = false) {
  Column(modifier.padding(horizontal = 18.dp, vertical = 15.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
    DesignLabel(label)
    Text(value, color = if (accent) green else ink, fontFamily = display, fontWeight = FontWeight.Bold, fontSize = 19.sp, lineHeight = 19.sp, letterSpacing = (-.38).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Text(hint, color = muted, fontFamily = mono, fontSize = 11.sp, lineHeight = 11.sp, maxLines = 1)
  }
}

@Composable
private fun MetricDivider() = Box(Modifier.width(1.dp).fillMaxHeight().background(border))

@Composable
private fun StatusChip(label: String, color: Color, lifecycle: Lifecycle? = null) {
  Row(Modifier.background(if (lifecycle == Lifecycle.ACTIVE) green.copy(alpha = .12f) else Color.Transparent, RoundedCornerShape(50)).border(1.dp, if (color == muted) strongBorder else color.copy(alpha = .45f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
    lifecycle?.let { WorkDot(it) }
    Text(label, color = color, fontFamily = mono, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 11.sp)
  }
}

@Composable
private fun PhaseChip(id: String, complete: Boolean, active: Boolean, attempts: Int) {
  val color = if (active) Color(0xFF14100A) else if (complete) green else dim
  Text(id + if (active && attempts > 1) " · $attempts" else "", Modifier.background(if (active) green else Color.Transparent, RoundedCornerShape(5.dp)).border(1.dp, if (complete) green.copy(alpha = .35f) else border, RoundedCornerShape(5.dp)).padding(horizontal = 7.dp, vertical = 4.dp), color = color, fontFamily = mono, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium, fontSize = 10.5.sp, lineHeight = 10.5.sp)
}

@Composable
private fun MetadataPanel(title: String, rows: List<Pair<String, String>>) {
  if (rows.isEmpty()) return
  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    DesignLabel(title)
    rows.forEach { (label, value) -> DesignKv(label, value, if (label == "Liveness" && value == "live") green else ink) }
  }
  HorizontalDivider(color = border)
}

private fun subtaskColor(status: String): Color = when {
  status == "in_progress" -> blue
  status == "complete" || status == "completed" -> green
  status == "blocked" -> orange
  status == "failed" -> red
  else -> surface3
}

@Composable
private fun rememberAnchoredListState(
  identity: WorkIdentity,
  generation: Long,
  listKind: String,
  keys: List<String>,
): LazyListState {
  val state = remember(identity, generation, listKind) { LazyListState() }
  var anchor by remember(identity, generation, listKind) { mutableStateOf<Pair<String, Int>?>(null) }
  var previousKeys by remember(identity, generation, listKind) { mutableStateOf(keys) }
  LaunchedEffect(state) {
    snapshotFlow {
      state.layoutInfo.visibleItemsInfo.firstOrNull()?.let { info ->
        (info.key as? String)?.let { it to state.firstVisibleItemScrollOffset }
      }
    }.collect { visible -> anchor = visible }
  }
  LaunchedEffect(keys) {
    val saved = anchor
    if (saved != null) {
      val restoredKey = if (saved.first in keys) saved.first else {
        val oldIndex = previousKeys.indexOf(saved.first).coerceAtLeast(0)
        previousKeys.drop(oldIndex + 1).firstOrNull { it in keys }
          ?: previousKeys.take(oldIndex).asReversed().firstOrNull { it in keys }
      }
      val index = keys.indexOf(restoredKey)
      if (index >= 0) state.scrollToItem(index, saved.second)
    }
    previousKeys = keys
  }
  return state
}

private fun formatInstant(value: Instant, pattern: String) = DateTimeFormatter.ofPattern(pattern).withZone(ZoneId.systemDefault()).format(value.toJavaInstant())
private fun displayPath(path: String) = path.replace(System.getProperty("user.home"), "~")

internal data class DetailDuration(val label: String, val value: String, val note: String)

internal fun detailDuration(item: WorkItem, now: Instant, stale: Boolean, frozen: Long? = null): DetailDuration {
  val finished = item.finishedAt?.takeIf { item.lifecycle != Lifecycle.ACTIVE && item.liveSection == null }
  if (finished != null) {
    val value = item.activeDurationMs?.let { age(it.milliseconds) } ?: "not available"
    return DetailDuration("Total run time", value, "finished ${formatInstant(finished, "d MMM HH:mm")}")
  }
  val standalone = item.scope == ExecutionScope.STANDALONE_PHASE
  val value = if (standalone) phaseElapsedText(item, now, stale) else
    durationText(item.activeDurationMs, item.activeDurationAsOf, item, now, stale, frozen)
  return DetailDuration(if (standalone) "Phase elapsed" else "Goal active", value,
    item.startedAt?.let { "since ${formatInstant(it, "d MMM HH:mm")}" } ?: "")
}

internal fun detailTitle(detail: Detail): String {
  detail.featureTitle?.takeIf { it.isNotBlank() }?.let { return displayFeatureTitle(it) }
  val item = detail.item
  val title = if (item.scope == ExecutionScope.STANDALONE_PHASE) {
    item.phaseId?.takeIf { it.isNotBlank() }?.replace('_', ' ')?.replace('-', ' ')?.replaceFirstChar(Char::uppercase) ?: "Standalone phase"
  } else when (item.family) {
    WorkFamily.FEATURE_GOAL -> "Goal"
    WorkFamily.FEATURE_VERIFY -> "Verification"
    else -> "Feature work"
  }
  return item.issueKey?.let { "$title · $it" } ?: title
}

private fun displayFeatureTitle(value: String): String =
  if (Regex("[a-z0-9]+(?:-[a-z0-9]+)+").matches(value)) value.replace('-', ' ').replaceFirstChar(Char::uppercase) else value
