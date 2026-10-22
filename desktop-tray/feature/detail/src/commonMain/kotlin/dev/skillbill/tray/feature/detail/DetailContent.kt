package dev.skillbill.tray.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skillbill.tray.core.designsystem.DesignButton
import dev.skillbill.tray.core.designsystem.DesignKv
import dev.skillbill.tray.core.designsystem.DesignLabel
import dev.skillbill.tray.core.designsystem.blue
import dev.skillbill.tray.core.designsystem.body
import dev.skillbill.tray.core.designsystem.canvas
import dev.skillbill.tray.core.designsystem.dim
import dev.skillbill.tray.core.designsystem.display
import dev.skillbill.tray.core.designsystem.green
import dev.skillbill.tray.core.designsystem.ink
import dev.skillbill.tray.core.designsystem.mint
import dev.skillbill.tray.core.designsystem.mono
import dev.skillbill.tray.core.designsystem.muted
import dev.skillbill.tray.core.designsystem.orange
import dev.skillbill.tray.core.designsystem.panel
import dev.skillbill.tray.core.designsystem.red
import dev.skillbill.tray.core.designsystem.strongBorder
import dev.skillbill.tray.core.designsystem.surface3
import dev.skillbill.tray.core.domain.model.Detail
import dev.skillbill.tray.core.domain.model.DetailList
import dev.skillbill.tray.core.domain.model.ExecutionScope
import dev.skillbill.tray.core.domain.model.Freshness
import dev.skillbill.tray.core.domain.model.Lifecycle
import dev.skillbill.tray.core.domain.model.PhaseExecution
import dev.skillbill.tray.core.domain.model.WorkFamily
import dev.skillbill.tray.core.domain.model.stalled
import dev.skillbill.tray.core.ui.FeedbackTone
import dev.skillbill.tray.core.ui.PageFooter
import dev.skillbill.tray.core.ui.ReasonTooltip
import dev.skillbill.tray.core.ui.WorkDot
import dev.skillbill.tray.core.ui.age
import dev.skillbill.tray.core.ui.coerceNonNegative
import dev.skillbill.tray.core.ui.durationText
import dev.skillbill.tray.core.ui.effectiveFreshness
import dev.skillbill.tray.core.ui.formatInstant
import dev.skillbill.tray.core.ui.label
import dev.skillbill.tray.core.ui.livenessText
import dev.skillbill.tray.core.ui.outcomeLabel
import dev.skillbill.tray.core.ui.phaseLabel
import dev.skillbill.tray.core.ui.phasePipeline
import dev.skillbill.tray.core.ui.rememberCopyToClipboard
import dev.skillbill.tray.core.ui.statusColor
import dev.skillbill.tray.core.ui.tickingNow
import kotlin.time.Instant

/**
 * One work item's detail. It reads everything it shows from its own state: whether the detail read is behind the store comes from
 * the load, and whether run controls are offered from [DetailUiState.controllable]. A refresh also calls [onRefresh], the shell's,
 * which checks the installed runtime.
 */
@Composable
fun DetailRoute(viewModel: DetailViewModel, modifier: Modifier = Modifier, onBackToList: (() -> Unit)? = null, onRefresh: () -> Unit = {}) {
  val state by viewModel.state.collectAsState()
  val now by tickingNow()
  val onAction: (DetailAction) -> Unit = { action ->
    if (action == DetailAction.Refresh) onRefresh()
    viewModel.onAction(action)
  }
  DetailContent(state, now, onAction, modifier, onBackToList)
}

/** [onBackToList], when given, is offered next to Refresh where the item cannot be read, so a missing item never strands the pane. */
@Composable
fun DetailContent(
  state: DetailUiState,
  now: Instant,
  onAction: (DetailAction) -> Unit,
  modifier: Modifier = Modifier,
  onBackToList: (() -> Unit)? = null,
) {
  when (val load = state.load) {
    DetailLoad.Loading -> DetailMessage("Loading work detail", "Fetching phases, subtasks, and history.", null, null, modifier)
    is DetailLoad.Failed -> DetailMessage("Work detail unavailable", load.problem.summary, { onAction(DetailAction.Refresh) }, onBackToList, modifier)
    is DetailLoad.Loaded -> LoadedDetail(state, load, now, onAction, modifier)
  }
}

@Composable
private fun DetailMessage(title: String, message: String, onRefresh: (() -> Unit)?, onBackToList: (() -> Unit)?, modifier: Modifier) {
  Box(modifier.fillMaxWidth().background(canvas), contentAlignment = Alignment.Center) {
    Column(Modifier.widthIn(max = 380.dp).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text(title, color = ink, style = MaterialTheme.typography.titleLarge)
      Text(message, color = muted, style = MaterialTheme.typography.bodyMedium)
      if (onRefresh != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onRefresh) { Text("Refresh") }
        if (onBackToList != null) OutlinedButton(onClick = onBackToList) { Text("Back to list") }
      }
    }
  }
}

private fun Detail.key(kind: String, suffix: String) = "${item.identity.repositoryIdentity}:${item.identity.id}:$kind:$suffix"

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LoadedDetail(
  state: DetailUiState,
  load: DetailLoad.Loaded,
  now: Instant,
  onAction: (DetailAction) -> Unit,
  modifier: Modifier,
) {
  val detail = load.detail
  val scroll = rememberScrollState()
  val item = detail.item
  val pipeline = phasePipeline(detail)
  val planning = item.currentStepId == "planning"
  val subtaskKeys = detail.subtasks.map { detail.key("subtask", it.id.toString()) } +
    listOfNotNull(detail.key("goal-finish", "").takeIf { detail.goalFinishPipeline.isNotEmpty() })
  val dependencyKeys = detail.dependencies.map { detail.key("dependency", "${it.subtaskId}:${it.dependency.subtaskId}") } +
    listOf(detail.key("dependency-header", ""), detail.key("dependency-footer", ""))
  val executionKeys = detail.executionHistory.map { detail.key("execution", "${it.invocationId}:${it.executionId}") }
  val validationKeys = detail.validation.map { detail.key("validation", validationKey(it)) } + detail.key("validation-header", "")
  val subtaskScroll = rememberAnchoredListState(item.identity, item.databaseGeneration, "subtasks", subtaskKeys)
  val dependencyScroll = rememberAnchoredListState(item.identity, item.databaseGeneration, "dependencies", dependencyKeys)
  val executionScroll = rememberAnchoredListState(item.identity, item.databaseGeneration, "executions", executionKeys)
  val validationScroll = rememberAnchoredListState(item.identity, item.databaseGeneration, "validation", validationKeys)
  val copy = rememberCopyToClipboard()
  val readError = state.readHealth.reportedProblem
  val fresh = effectiveFreshness(item, false, readError != null) == Freshness.FRESH
  val total = item.progressTotal ?: detail.subtasks.size.takeIf { it > 0 }
  val frozen = !fresh
  val operation = item.family == WorkFamily.FEATURE_VERIFY
  val standalone = item.scope == ExecutionScope.STANDALONE_PHASE
  val blockReason = item.exactPauseReason
    ?: detail.executionHistory.firstOrNull { it.invocationId == item.invocationId }?.result
    ?: item.pauseReason
    ?: item.summary
  val copyReason = if (item.family == WorkFamily.FEATURE_GOAL) item.exactPauseReason else blockReason
  val goalBlocked = item.family == WorkFamily.FEATURE_GOAL && item.lifecycle == Lifecycle.BLOCKED
  val goalBlockReason = copyReason.takeIf { goalBlocked }
  var expandedReason by remember(item.identity, item.invocationId, blockReason) { mutableStateOf(false) }
  val subtaskTime = durationText(item.subtaskActiveDurationMs, item.subtaskActiveDurationAsOf, item, now, frozen)
  Column(modifier.background(canvas)) {
    Column(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(canvas, mint.copy(alpha = .04f)))).padding(start = 26.dp, end = 26.dp, top = 20.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
      Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          StatusChip(item.outcomeLabel(), item.statusColor(), item.lifecycle, stalled = item.stalled())
          (item.issueKey ?: item.phaseId)?.let { StatusChip(it, muted) }
          StatusChip(if (operation) "operation" else if (item.family == WorkFamily.FEATURE_GOAL || item.goalLaunched) "goal" else "phase", Color(0xFFF49463))
          StatusChip(if (fresh) "fresh" else "stale", if (fresh) green else muted)
        }
        Text(detailTitle(detail), color = ink, fontFamily = display, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 28.8.sp, letterSpacing = (-.48).sp)
        RepositoryLocationLinks(item)
        readError?.let { Text(it.summary, color = orange, style = MaterialTheme.typography.bodySmall) }
        state.pendingFeedback.forEach { feedback ->
          Text(
            feedback.message,
            Modifier.clickable { onAction(DetailAction.AcknowledgeFeedback(feedback.id)) },
            color = if (feedback.tone == FeedbackTone.PROBLEM) orange else green,
            style = MaterialTheme.typography.bodySmall,
          )
        }
        if (item.lifecycle in setOf(Lifecycle.BLOCKED, Lifecycle.PAUSED, Lifecycle.FAILED)) {
          val reasonColor = if (item.lifecycle == Lifecycle.FAILED) red else orange
          Column(Modifier.fillMaxWidth().background(reasonColor.copy(alpha = .12f), RoundedCornerShape(10.dp)).border(1.dp, reasonColor.copy(alpha = .4f), RoundedCornerShape(10.dp)).padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (item.lifecycle == Lifecycle.FAILED) "Run failed" else if (item.lifecycle == Lifecycle.BLOCKED) "Run blocked" else "Run paused", color = reasonColor, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
              if (expandedReason) {
                SelectionContainer(Modifier.weight(1f).heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                  Text(blockReason, color = reasonColor, fontSize = 13.sp, lineHeight = 19.sp)
                }
              } else {
                ReasonTooltip(blockReason, Modifier.weight(1f), color = reasonColor, fontSize = 13.sp, lineHeight = 19.sp)
              }
              DesignButton("Copy reason", enabled = copyReason != null) { copyReason?.let(copy) }
            }
            DesignButton(if (expandedReason) "Collapse reason" else "Show full reason") { expandedReason = !expandedReason }
            val restoration = detail.executionHistory.firstOrNull { it.invocationId == item.invocationId }?.restoration
            restorationNote(restoration)?.let { Text(it, color = muted, style = MaterialTheme.typography.bodySmall) }
          }
        }
      }
      RunActionButtons(detail, state.control is ControlState.InProgress, state.controllable && !state.readHealth.blocksControls, onAction)
    }
    HorizontalDivider(color = dev.skillbill.tray.core.designsystem.border)
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
      if (!operation) {
        DetailMetric(if (item.family == WorkFamily.FEATURE_GOAL) "Subtask" else "Phase", item.currentSubtaskId?.let { "$it/${total ?: "?"}" } ?: item.phaseId ?: "Goal finish", "${detail.completeCount} complete · ${detail.pendingCount} pending", Modifier.weight(1f), item.currentSubtaskId != null)
        MetricDivider()
      }
      DetailMetric(
        if (standalone || operation) "Step" else "Phase",
        if (operation || planning) item.currentStepLabel else if (item.family == WorkFamily.FEATURE_GOAL && item.currentSubtaskId == null) item.currentStepLabel else item.phaseExecution?.phaseLabel() ?: item.currentPhaseId ?: detail.currentPhase ?: item.currentStepLabel,
        if (planning) "${item.plannedSubtasks ?: 0}/${item.totalSubtasks ?: "?"} plans saved" else item.phaseExecution?.label() ?: "",
        Modifier.weight(1f),
      )
      MetricDivider()
      val duration = detailDuration(item, now, frozen)
      DetailMetric(duration.label, duration.value, duration.note, Modifier.weight(1f))
      if (!standalone && !operation) {
        MetricDivider()
        DetailMetric("Subtask active", subtaskTime, if (frozen) "last known duration" else if (item.lifecycle == Lifecycle.ACTIVE) "ticking" else "", Modifier.weight(1f))
      }
    }
    HorizontalDivider(color = dev.skillbill.tray.core.designsystem.border)
    Row(Modifier.weight(1f).fillMaxWidth()) {
      Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(scroll).padding(horizontal = 26.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (!operation && detail.subtasks.isNotEmpty()) {
          Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Subtasks", color = ink, fontFamily = display, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text("from the goal manifest", color = dim, fontFamily = mono, fontSize = 11.sp)
          }
          Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            detail.subtasks.forEach { task -> Box(Modifier.weight(1f).height(5.dp).background(subtaskColor(task.status), RoundedCornerShape(2.dp))) }
          }
          LazyColumn(state = subtaskScroll, modifier = Modifier.heightIn(max = 420.dp)) {
            items(detail.subtasks, key = { detail.key("subtask", it.id.toString()) }) { task ->
              val active = !planning && task.id.toString() == item.currentSubtaskId && item.lifecycle == Lifecycle.ACTIVE && item.currentStepId !in setOf("pending_launch", "initializing")
              val pending = task.status == "pending"
              val taskInk = ink.copy(alpha = if (pending && !active) .55f else 1f)
              Row(Modifier.fillMaxWidth().background(if (active) blue.copy(alpha = .06f) else Color.Transparent, RoundedCornerShape(8.dp)).padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(task.id.toString().padStart(2, '0'), Modifier.width(18.dp), color = if (active) blue else dim, fontFamily = mono, fontSize = 11.sp, lineHeight = 16.5.sp)
                WorkDot(if (active) Lifecycle.ACTIVE else if (task.status in setOf("complete", "completed")) Lifecycle.TERMINAL else if (task.status == "blocked") Lifecycle.BLOCKED else if (task.status == "failed") Lifecycle.FAILED else Lifecycle.IDLE, Modifier.padding(top = 5.dp))
                Column(Modifier.weight(1f)) {
                  Text(task.name, color = taskInk, fontFamily = body, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp, lineHeight = 18.75.sp)
                  val validation = detail.validation.firstOrNull { it.subtaskId == task.id }
                  Text(
                    listOfNotNull(
                      task.status, task.commitSha?.take(7),
                      if (active) item.phaseExecution?.phaseLabel() else null,
                      if (active) item.currentModel else null,
                      if (active) item.currentEffort else null,
                      validation?.passed?.let { if (it) "validation passed" else "validation failed" },
                      task.dependencies.takeIf { pending && it.isNotEmpty() }?.joinToString { "after #${it.subtaskId}" },
                    ).joinToString(" · "),
                    color = if (active) blue else dim, fontFamily = mono, fontSize = 11.sp, lineHeight = 16.5.sp,
                  )
                  task.blockedReason?.let { SelectionContainer { Text(it, color = orange, fontSize = 12.sp, lineHeight = 18.sp) } }
                  if (active && pipeline.isNotEmpty()) FlowRow(Modifier.padding(top = 9.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    pipeline.forEach { phase -> PhaseChip(phase.id, phase.finished, phase.id == (item.currentPhaseId ?: detail.currentPhase), item.phaseExecution?.takeIf { it.phaseId == phase.id }) }
                  }
                }
                if (active) Text(subtaskTime, color = blue, fontFamily = mono, fontSize = 11.sp, lineHeight = 16.5.sp)
              }
              HorizontalDivider(color = dev.skillbill.tray.core.designsystem.border)
            }
            this.item(key = detail.key("goal-finish", "")) {
              val finishSteps = goalFinishSteps(item, detail.goalFinishPipeline)
              val finishActive = finishSteps.any { it.state == GoalFinishStepState.ACTIVE }
              val finishDone = finishSteps.isNotEmpty() && finishSteps.all { it.state == GoalFinishStepState.COMPLETE }
              if (finishSteps.isNotEmpty()) Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("—", Modifier.width(18.dp), color = if (finishActive) blue else dim, fontFamily = mono, fontSize = 11.sp)
                WorkDot(if (finishActive) Lifecycle.ACTIVE else if (finishDone) Lifecycle.TERMINAL else Lifecycle.IDLE, Modifier.padding(top = 5.dp))
                Column(Modifier.weight(1f)) {
                  Text("Goal finish", color = ink.copy(alpha = if (finishActive || finishDone) 1f else .55f), fontFamily = body, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
                  FlowRow(Modifier.padding(top = 9.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    finishSteps.forEach { PhaseChip(it.id, it.state == GoalFinishStepState.COMPLETE, it.state == GoalFinishStepState.ACTIVE) }
                  }
                }
              }
            }
          }
          PageFooter(state.append(DetailList.SUBTASKS)) { onAction(DetailAction.LoadMore(DetailList.SUBTASKS)) }
        } else if (!operation && detail.stepPipeline.isNotEmpty()) {
          DesignLabel("Phase pipeline")
          FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            detail.stepPipeline.forEach { PhaseChip(it, false, it == item.currentStepId, item.phaseExecution?.takeIf { execution -> execution.phaseId == it }) }
          }
        }
        if (detail.dependencies.isNotEmpty() || detail.cursors[DetailList.DEPENDENCIES] != null) {
          LazyColumn(state = dependencyScroll, modifier = Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            this.item(key = detail.key("dependency-header", "")) { DesignLabel("Dependencies") }
            items(detail.dependencies, key = { detail.key("dependency", "${it.subtaskId}:${it.dependency.subtaskId}") }) { entry ->
              val flags = listOfNotNull(
                if (entry.dependency.optional) "optional" else null,
                if (entry.dependency.skipped) "skipped" else null,
              ).joinToString(" · ")
              val suffix = if (flags.isEmpty()) "" else " · $flags"
              Text("#${entry.subtaskId} depends on #${entry.dependency.subtaskId}$suffix", color = dim, fontFamily = mono, fontSize = 11.sp)
            }
            this.item(key = detail.key("dependency-footer", "")) {
              PageFooter(state.append(DetailList.DEPENDENCIES)) { onAction(DetailAction.LoadMore(DetailList.DEPENDENCIES)) }
            }
          }
        }
        if (!operation) PageFooter(state.append(DetailList.PHASES)) { onAction(DetailAction.LoadMore(DetailList.PHASES)) }
        val event = detail.latestActivity.event
        val edit = detail.latestActivity.edit
        val activity = buildList {
          item.lastAgentActivityLabel?.let { add("Agent activity" to "$it${item.lastAgentActivityAt?.let { at -> " · ${age((now - at).coerceNonNegative())} ago" }.orEmpty()}") }
          edit?.let { add("Last edit" to "${it.paths.joinToString { path -> "…/" + path.substringAfterLast('/') }} +${it.insertions ?: 0} −${it.deletions ?: 0}${it.recordedAt?.let { at -> " · ${age((now - at).coerceNonNegative())} ago" }.orEmpty()}") }
          event?.let { add("Event" to listOfNotNull(it.workflowPhase, it.workerRole, it.summary).joinToString(" · ")) }
          item.activity?.let { add((if (operation) "Operation event" else "Phase event") to it) }
        }
        if (activity.isNotEmpty() || detail.executionHistory.isNotEmpty() || goalBlocked || detail.phaseResults.isNotEmpty() || detail.goalQa != null) Column(Modifier.fillMaxWidth().padding(top = 4.dp).background(panel, RoundedCornerShape(10.dp)).border(1.dp, dev.skillbill.tray.core.designsystem.border, RoundedCornerShape(10.dp)).padding(horizontal = 15.dp, vertical = 13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          DesignLabel(if (operation) "Operation activity" else if (standalone) "Phase activity" else "Goal activity")
          goalBlockReason?.let { reason ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
              Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Blocked · ${formatInstant(item.updatedAt, "d MMM HH:mm:ss")}", color = orange, fontSize = 12.sp)
                ReasonTooltip(reason, color = orange)
              }
              DesignButton("Copy reason") { copy(reason) }
            }
          }
          if (goalBlocked && goalBlockReason == null) Text("The full block reason was not saved for this attempt.", color = muted, fontSize = 12.sp)
          activity.forEach { (label, value) -> DesignKv(label, value) }
          PhaseResultsSection(detail.phaseResults, detail.goalQa) { id -> detail.subtasks.firstOrNull { it.id == id }?.name }
          QaFindingsSection(detail.goalQa)
          LazyColumn(state = executionScroll, modifier = Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(detail.executionHistory, key = { detail.key("execution", "${it.invocationId}:${it.executionId}") }) { instance ->
              val index = detail.executionHistory.indexOf(instance)
              HorizontalDivider(color = dev.skillbill.tray.core.designsystem.border)
              Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("Run ${detail.executionHistory.size - index} · ${if (standalone) executionOutcomeLabel(item, instance) else "Invocation"} · ${formatInstant(instance.startedAt, "d MMM HH:mm:ss")}${if (index == 0) " · latest run" else ""}", color = ink, fontFamily = body, fontSize = 12.sp)
                SelectionContainer { Text(instance.invocationId, color = muted, fontFamily = mono, fontSize = 11.sp) }
                SelectionContainer { Text("Execution ${instance.executionId}", color = dim, fontFamily = mono, fontSize = 11.sp) }
                Text("${if (instance.finishedAt != null) "Finished" else "Last update"} ${formatInstant(instance.finishedAt ?: instance.updatedAt, "d MMM HH:mm:ss")}", color = muted, fontSize = 11.sp)
                instance.result?.let { reason -> ReasonTooltip(reason) }
                instance.qaReport?.let { QaCriteriaSection(it) }
                instance.planReport?.let { PlanReportSection(it) }
              }
            }
          }
        }
        PageFooter(state.append(DetailList.EXECUTIONS)) { onAction(DetailAction.LoadMore(DetailList.EXECUTIONS)) }
      }
      Box(Modifier.width(1.dp).fillMaxHeight().background(dev.skillbill.tray.core.designsystem.border))
      Column(Modifier.width(290.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        MetadataPanel(
          "Execution",
          listOfNotNull(
            item.workflowId?.let { "Workflow" to it }, item.invocationId?.let { "Invocation" to it },
            detail.activeAgent?.let { "Agent" to it }, "Model" to (item.currentModel?.let { "$it${item.currentEffort?.let { effort -> " · $effort" }.orEmpty()}" } ?: "Not reported"),
            detail.executionLiveness?.let { "Liveness" to livenessText(it, item, now) }, item.startedAt?.let { "Started" to formatInstant(it, "d MMM HH:mm") },
            "Last update" to formatInstant(item.updatedAt, "HH:mm:ss"),
          ),
        )
        val attempts = buildList {
          detail.attempts.phaseAttempts.filterValues { it > 0 }.takeIf { it.isNotEmpty() }?.let { add("Phase runs" to it.entries.joinToString(" · ") { entry -> "${entry.key} ${entry.value}" }) }
          detail.attempts.fixIterations.filterValues { it > 0 }.takeIf { it.isNotEmpty() }?.let { add("Fix iterations" to it.entries.joinToString(" · ") { entry -> "${entry.key} ${entry.value}" }) }
          detail.attempts.findingsInScope?.let { add("Findings in scope" to it.toString()) }
          detail.attempts.reAttemptCauses.filterValues { it > 0 }.takeIf { it.isNotEmpty() }?.let { add("Re-attempts" to it.entries.joinToString(" · ") { entry -> "${entry.key} ${entry.value}" }) }
          if (detail.attempts.supervisorKills > 0) add("Supervisor kills" to detail.attempts.supervisorKills.toString())
        }
        MetadataPanel("Runs and fixes", attempts)
        if (detail.validation.isNotEmpty()) {
          LazyColumn(state = validationScroll, modifier = Modifier.heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            this.item(key = detail.key("validation-header", "")) { DesignLabel("Validation") }
            items(detail.validation, key = { detail.key("validation", validationKey(it)) }) { record ->
              Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                WorkDot(if (record.passed == true) Lifecycle.TERMINAL else if (record.passed == false) Lifecycle.FAILED else Lifecycle.IDLE)
                Text("Subtask ${record.subtaskId}", Modifier.width(72.dp), color = ink, fontFamily = body, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp)
                Text(
                  record.integrityProblem ?: "${when (record.passed) { true -> "passed"; false -> "failed"; null -> "unknown" }}${record.result?.let { " · ${it.checks.size} checks" }.orEmpty()}",
                  modifier = Modifier.weight(1f), color = dim, fontFamily = mono, fontSize = 11.sp, lineHeight = 16.sp, textAlign = TextAlign.End,
                )
              }
            }
          }
          PageFooter(state.append(DetailList.VALIDATION)) { onAction(DetailAction.LoadMore(DetailList.VALIDATION)) }
          HorizontalDivider(color = dev.skillbill.tray.core.designsystem.border)
        }
      }
    }
  }
}

private fun validationKey(record: dev.skillbill.tray.core.domain.model.SubtaskValidation): String =
  "${record.subtaskId}:" + record.evidence.joinToString("|") { "${it.command}:${it.exitCode}" }

@Composable
private fun DetailMetric(label: String, value: String, hint: String, modifier: Modifier, accent: Boolean = false) {
  Column(modifier.padding(horizontal = 18.dp, vertical = 15.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
    DesignLabel(label)
    Text(value, color = if (accent) green else ink, fontFamily = display, fontWeight = FontWeight.Bold, fontSize = 19.sp, lineHeight = 19.sp, letterSpacing = (-.38).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Text(hint, color = muted, fontFamily = mono, fontSize = 11.sp, lineHeight = 11.sp, maxLines = 1)
  }
}

@Composable
private fun MetricDivider() = Box(Modifier.width(1.dp).fillMaxHeight().background(dev.skillbill.tray.core.designsystem.border))

@Composable
private fun StatusChip(label: String, color: Color, lifecycle: Lifecycle? = null, stalled: Boolean = false) {
  Row(
    Modifier.background(if (lifecycle == Lifecycle.ACTIVE) green.copy(alpha = .12f) else Color.Transparent, RoundedCornerShape(50))
      .border(1.dp, if (color == muted) strongBorder else color.copy(alpha = .45f), RoundedCornerShape(50))
      .padding(horizontal = 8.dp, vertical = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    lifecycle?.let { WorkDot(it, stalled = stalled) }
    Text(label, color = color, fontFamily = mono, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 11.sp)
  }
}

@Composable
private fun PhaseChip(id: String, complete: Boolean, active: Boolean, execution: PhaseExecution? = null) {
  val color = if (active) Color(0xFF14100A) else if (complete) green else dim
  Text(
    if (active) execution?.phaseLabel() ?: id else id,
    Modifier.background(if (active) green else Color.Transparent, RoundedCornerShape(5.dp))
      .border(1.dp, if (complete) green.copy(alpha = .35f) else dev.skillbill.tray.core.designsystem.border, RoundedCornerShape(5.dp))
      .padding(horizontal = 7.dp, vertical = 4.dp),
    color = color, fontFamily = mono, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium, fontSize = 10.5.sp, lineHeight = 10.5.sp,
  )
}

@Composable
private fun MetadataPanel(title: String, rows: List<Pair<String, String>>) {
  if (rows.isEmpty()) return
  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    DesignLabel(title)
    rows.forEach { (label, value) -> DesignKv(label, value, if (label == "Liveness" && value == "live") green else ink) }
  }
  HorizontalDivider(color = dev.skillbill.tray.core.designsystem.border)
}

private fun subtaskColor(status: String): Color = when {
  status == "in_progress" -> blue
  status == "complete" || status == "completed" -> green
  status == "blocked" -> orange
  status == "failed" -> red
  else -> surface3
}
