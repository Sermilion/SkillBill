package dev.skillbill.tray.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import dev.skillbill.tray.domain.*
import dev.skillbill.tray.presentation.effectiveFreshness
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DetailPane(detail: Detail?, problem: Problem?, model: DashboardModel, modifier: Modifier, now: Instant, boardStale: Boolean) {
  if (detail == null) return
  val state by model.state.collectAsState()
  val scroll = rememberScrollState()
  LoadNextPageOnScroll(scroll, detail.nextCursor != null, state.detailLoading, model::loadMoreDetail)
  val item = detail.item
  val clipboard = LocalClipboardManager.current
  val fresh = effectiveFreshness(item, boardStale, state.detailExtrasStale) == Freshness.FRESH
  val current = state.board?.items?.singleOrNull { it.identity == item.identity }
  val total = item.progressTotal ?: detail.subtasks.size.takeIf { it > 0 }
  val timing = state.displayDurations[item.identity]
  val frozen = boardStale || item.freshness == Freshness.STALE
  val standalone = item.scope == ExecutionScope.STANDALONE_PHASE
  val blocked = item.lifecycle == Lifecycle.BLOCKED || item.lifecycle == Lifecycle.PAUSED
  val blockReason = item.pauseReason ?: detail.executionHistory.firstOrNull { it.invocationId == item.invocationId }?.result ?: item.summary
  val subtaskTime = durationText(item.subtaskActiveDurationMs, item.subtaskActiveDurationAsOf, item, now, frozen, timing?.subtaskMs)
  Column(modifier.background(canvas)) {
    Row(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(canvas, mint.copy(alpha = .04f)))).padding(start = 26.dp, end = 26.dp, top = 20.dp, bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          StatusChip(item.lifecycle.name.lowercase(), lifecycleColor(item.lifecycle), item.lifecycle)
          (item.issueKey ?: item.phaseId)?.let { StatusChip(it, muted) }
          StatusChip(if (item.family == WorkFamily.FEATURE_GOAL) "goal" else "phase", Color(0xFFF49463))
          StatusChip(if (fresh) "fresh" else "stale", if (fresh) green else muted)
        }
        Text(if (blocked || item.lifecycle == Lifecycle.FAILED) detail.featureTitle ?: "${if (standalone) "Phase ${item.phaseId.orEmpty()}" else "Workflow"} ${item.lifecycle.name.lowercase()}" else displayFeatureTitle(detail.featureTitle ?: item.summary), color = ink, fontFamily = display, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 28.8.sp, letterSpacing = (-.48).sp)
        Text("${FileName(item.repositoryRoot)} · ${displayPath(item.repositoryRoot)} · ${item.branch ?: "no branch"}", color = muted, fontFamily = mono, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        listOfNotNull(problem, state.actionProblem).forEach { Text(it.summary, color = orange, style = MaterialTheme.typography.bodySmall) }
        if (state.detailLoading) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          CircularProgressIndicator(Modifier.size(12.dp), color = mint, strokeWidth = 2.dp)
          Text("Loading more details...", color = muted, style = MaterialTheme.typography.bodySmall)
        }
        if (item.lifecycle == Lifecycle.BLOCKED || item.lifecycle == Lifecycle.PAUSED) {
          Column(Modifier.fillMaxWidth().background(orange.copy(alpha = .12f), RoundedCornerShape(10.dp)).border(1.dp, orange.copy(alpha = .4f), RoundedCornerShape(10.dp)).padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
              ReasonTooltip(blockReason, Modifier.weight(1f), color = amber, fontSize = 13.sp, lineHeight = 19.sp)
              DesignButton("Copy reason") { clipboard.setText(AnnotatedString(blockReason)) }
            }
            if (item.lifecycle == Lifecycle.BLOCKED) item.issueKey?.let { TerminalCommand("skill-bill $it resume") { command -> clipboard.setText(AnnotatedString(command)) } }
          }
        }
      }
      if (current != null && !boardStale && current.freshness == Freshness.FRESH && eligibleForControl(current)) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DesignButton(if (current.pauseRequested) "Pause requested" else "Pause after current subtask", enabled = !current.pauseRequested) { model.perform("pause", item.identity) }
        DesignButton("Stop goal", danger = true) { model.perform("stop", item.identity) }
      }
    }
    HorizontalDivider(color = border)
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
      DetailMetric(if (item.family == WorkFamily.FEATURE_GOAL) "Subtask" else "Phase", item.currentSubtaskId?.let { "$it/${total ?: "?"}" } ?: item.phaseId ?: "Goal finish", "${detail.subtasks.count { it.status in setOf("complete", "completed") }} complete · ${detail.subtasks.count { it.status == "pending" }} pending", Modifier.weight(1f), item.currentSubtaskId != null)
      MetricDivider()
      DetailMetric(if (standalone) "Step" else "Phase", if (item.family == WorkFamily.FEATURE_GOAL && item.currentSubtaskId == null) item.currentStepLabel else item.currentPhaseId ?: detail.currentPhase ?: item.currentStepLabel, item.phaseExecution?.label() ?: "", Modifier.weight(1f))
      MetricDivider()
      DetailMetric(if (standalone) "Phase elapsed" else "Goal active", if (standalone) phaseElapsedText(item, now, boardStale) else durationText(item.activeDurationMs, item.activeDurationAsOf, item, now, frozen, timing?.goalMs), item.startedAt?.let { "since ${formatInstant(it, "d MMM HH:mm")}" } ?: "", Modifier.weight(1f))
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
            detail.subtasks.forEach { task -> Box(Modifier.weight(1f).height(5.dp).background(subtaskColor(task.status, task.id.toString() == item.currentSubtaskId), RoundedCornerShape(2.dp))) }
          }
          Column {
            detail.subtasks.forEach { task ->
              val active = task.id.toString() == item.currentSubtaskId && item.lifecycle == Lifecycle.ACTIVE
              val pending = task.status == "pending"
              val taskInk = ink.copy(alpha = if (pending && !active) .55f else 1f)
              Row(Modifier.fillMaxWidth().background(if (active) green.copy(alpha = .06f) else Color.Transparent, RoundedCornerShape(8.dp)).padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(task.id.toString().padStart(2, '0'), Modifier.width(18.dp), color = if (active) green else dim, fontFamily = mono, fontSize = 11.sp, lineHeight = 16.5.sp)
                WorkDot(if (active) Lifecycle.ACTIVE else if (task.status in setOf("complete", "completed")) Lifecycle.TERMINAL else if (task.status == "blocked") Lifecycle.BLOCKED else if (task.status == "failed") Lifecycle.FAILED else Lifecycle.IDLE, Modifier.padding(top = 5.dp))
                Column(Modifier.weight(1f)) {
                  Text(task.name, color = taskInk, fontFamily = body, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp, lineHeight = 18.75.sp)
                  val validation = detail.validation.firstOrNull { it.subtaskId == task.id }
                  Text(listOfNotNull(task.status, task.commitSha?.take(7), if (active) item.phaseExecution?.let { "${it.phaseId} ${it.label()}" } else null, if (active) item.currentModel else null, if (active) item.currentEffort else null, validation?.passed?.let { if (it) "validation passed" else "validation failed" }, task.dependencies.takeIf { pending && it.isNotEmpty() }?.joinToString { "after #${it.subtaskId}" }).joinToString(" · "), color = if (active) green else dim, fontFamily = mono, fontSize = 11.sp, lineHeight = 16.5.sp)
                  task.blockedReason?.let { SelectionContainer { Text(it, color = orange, fontSize = 12.sp, lineHeight = 18.sp) } }
                  if (active && detail.phases.isNotEmpty()) FlowRow(Modifier.padding(top = 9.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    detail.phases.forEach { phase -> PhaseChip(phase.id, phase.finished, phase.id == item.currentPhaseId, phase.attemptCount) }
                  }
                }
                if (active) Text(subtaskTime, color = green, fontFamily = mono, fontSize = 11.sp, lineHeight = 16.5.sp)
              }
              HorizontalDivider(color = border)
            }
            if (detail.goalFinishPipeline.isNotEmpty()) Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
              Text("—", Modifier.width(18.dp), color = dim, fontFamily = mono, fontSize = 11.sp)
              WorkDot(Lifecycle.IDLE, Modifier.padding(top = 5.dp))
              Column(Modifier.weight(1f)) {
                Text("Goal finish", color = ink.copy(alpha = .55f), fontFamily = body, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
                FlowRow(Modifier.padding(top = 9.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) { detail.goalFinishPipeline.forEach { PhaseChip(it, false, it == item.currentStepId, 0) } }
              }
            }
          }
        } else if (detail.stepPipeline.isNotEmpty()) {
          DesignLabel("Phase pipeline")
          FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) { detail.stepPipeline.forEach { PhaseChip(it, false, it == item.currentStepId, 0) } }
        }
        val event = detail.latestActivity.event
        val edit = detail.latestActivity.edit
        val activity = buildList {
          item.lastAgentActivityLabel?.let { add("Agent activity" to "$it${item.lastAgentActivityAt?.let { at -> " · ${age(Duration.between(at, now).coerceNonNegative())} ago" }.orEmpty()}") }
          edit?.let { add("Last edit" to "${it.paths.joinToString { path -> "…/" + path.substringAfterLast('/') }} +${it.insertions ?: 0} −${it.deletions ?: 0}${it.recordedAt?.let { at -> " · ${age(Duration.between(at, now).coerceNonNegative())} ago" }.orEmpty()}") }
          event?.let { add("Event" to listOfNotNull(it.workflowPhase, it.workerRole, it.summary).joinToString(" · ")) }
          item.activity?.let { add("Phase event" to it) }
        }
        if (activity.isNotEmpty() || detail.executionHistory.isNotEmpty()) Column(Modifier.fillMaxWidth().padding(top = 4.dp).background(panel, RoundedCornerShape(10.dp)).border(1.dp, border, RoundedCornerShape(10.dp)).padding(horizontal = 15.dp, vertical = 13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          DesignLabel(if (standalone) "Phase activity" else "Goal activity")
          activity.forEach { (label, value) -> DesignKv(label, value) }
          detail.executionHistory.forEachIndexed { index, instance ->
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
        PageFooter(detail.nextCursor != null, state.detailLoading, model::loadMoreDetail)
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
          Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            DesignLabel("Validation")
            detail.validation.forEach { record ->
              Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                WorkDot(if (record.passed == true) Lifecycle.TERMINAL else if (record.passed == false) Lifecycle.FAILED else Lifecycle.IDLE)
                Text("Subtask ${record.subtaskId}", Modifier.width(72.dp), color = ink, fontFamily = body, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp)
                Text(record.integrityProblem ?: "${when (record.passed) { true -> "passed"; false -> "failed"; null -> "unknown" }}${record.result?.let { " · ${it.checks.size} checks" }.orEmpty()}", modifier = Modifier.weight(1f), color = dim, fontFamily = mono, fontSize = 11.sp, lineHeight = 16.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End)
              }
            }
          }
          HorizontalDivider(color = border)
        }
        item.issueKey?.let {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DesignLabel("From the terminal")
            TerminalCommand("skill-bill goal status $it --repo-root ${shellQuote(item.repositoryRoot)}") { command -> clipboard.setText(AnnotatedString(command)) }
          }
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

@Composable
private fun TerminalCommand(command: String, copy: (String) -> Unit) {
  Row(Modifier.fillMaxWidth().height(34.dp).background(titlebar, RoundedCornerShape(8.dp)).border(1.dp, strongBorder, RoundedCornerShape(8.dp)).padding(start = 12.dp, end = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(command, Modifier.weight(1f), color = ink, fontFamily = mono, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Box(Modifier.height(24.dp).background(raised, RoundedCornerShape(8.dp)).border(1.dp, strongBorder, RoundedCornerShape(8.dp)).clickable { copy(command) }.padding(horizontal = 9.dp), contentAlignment = Alignment.Center) {
      Text("Copy", color = ink, fontFamily = body, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
    }
  }
}

private fun subtaskColor(status: String, active: Boolean): Color = when {
  active -> green
  status == "complete" || status == "completed" -> green
  status == "blocked" -> orange
  status == "failed" -> red
  else -> surface3
}

private fun formatInstant(value: Instant, pattern: String) = DateTimeFormatter.ofPattern(pattern).withZone(ZoneId.systemDefault()).format(value)
private fun displayPath(path: String) = path.replace(System.getProperty("user.home"), "~")

private fun displayFeatureTitle(value: String): String =
  if (Regex("[a-z0-9]+(?:-[a-z0-9]+)+").matches(value)) value.replace('-', ' ').replaceFirstChar(Char::uppercase) else value
