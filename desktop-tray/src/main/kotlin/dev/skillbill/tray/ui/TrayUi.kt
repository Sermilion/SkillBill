package dev.skillbill.tray.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.unit.dp
import dev.skillbill.tray.application.*
import dev.skillbill.tray.domain.*
import dev.skillbill.tray.domain.Retention
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant

@Composable
internal fun Pipeline(label: String, steps: List<String>) {
  Column(Modifier.padding(top = 10.dp)) {
    Text(label, color = muted, style = MaterialTheme.typography.labelMedium)
    Text(steps.joinToString("  →  "), color = ink)
  }
}

@Composable
internal fun CopyLine(value: String, label: String, copy: (AnnotatedString) -> Unit) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    SelectionContainer(Modifier.weight(1f)) { Text("$label: $value", color = muted, style = MaterialTheme.typography.bodySmall) }
    TextButton(onClick = { copy(AnnotatedString(value)) }) { Text("Copy", color = mint) }
  }
}

@Composable
internal fun LabeledPanel(title: String, rows: List<Pair<String, String>>) {
  if (rows.isEmpty()) return
  Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).background(raised, RoundedCornerShape(8.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
    Text(title, color = mint, style = MaterialTheme.typography.labelMedium)
    rows.forEach { (label, value) -> Row(Modifier.fillMaxWidth()) { Text(label, Modifier.width(130.dp), color = muted, style = MaterialTheme.typography.labelSmall); Text(value, Modifier.weight(1f), color = ink, style = MaterialTheme.typography.bodySmall) } }
  }
}

@Composable
fun SettingsWindow(settings: AppSettings, onChange: (AppSettings) -> Unit, problem: Problem?, effectiveDatabasePath: String? = null, onClose: () -> Unit) {
  var database by remember(settings.databasePath) { mutableStateOf(settings.databasePath.orEmpty()) }
  Column(Modifier.width(460.dp).heightIn(max = 680.dp).background(canvas).padding(20.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) { Text("Settings", Modifier.weight(1f), color = ink, style = MaterialTheme.typography.titleLarge); TextButton(onClick = onClose) { Text("Close") } }
    problem?.let { Text(it.summary, color = amber, style = MaterialTheme.typography.bodySmall) }
    OutlinedTextField(database, { database = it }, Modifier.fillMaxWidth(), label = { Text("Database path, blank uses runtime default") }, singleLine = true)
    effectiveDatabasePath?.let { Text("Current database: $it", color = muted, style = MaterialTheme.typography.bodySmall) }
    Text("History retention", color = ink)
    Row { Retention.entries.forEach { value -> FilterChip(settings.retention == value, { onChange(settings.copy(retention = value)) }, label = { Text(value.cliValue) }) } }
    Text("Notifications", color = ink)
    allowedTransitions.forEach { key -> Row(verticalAlignment = Alignment.CenterVertically) {
      Text(key.replace('_', ' '), Modifier.weight(1f), color = muted)
      Switch(settings.notifications[key] ?: defaultNotifications.getValue(key), { enabled -> onChange(settings.copy(notifications = settings.notifications + (key to enabled))) })
    } }
    Row(verticalAlignment = Alignment.CenterVertically) { Text("Start at login", Modifier.weight(1f), color = ink); Switch(settings.startAtLogin, { onChange(settings.copy(startAtLogin = it)) }) }
    Button(onClick = { onChange(settings.copy(databasePath = database.ifBlank { null })) }) { Text("Save settings") }
  }
}

internal fun age(duration: Duration): String = when {
  duration.toHours() > 0 -> "${duration.toHours()}h ${duration.toMinutesPart()}m"
  duration.toMinutes() > 0 -> "${duration.toMinutes()}m ${duration.toSecondsPart()}s"
  else -> "${duration.seconds.coerceAtLeast(0)}s"
}
internal fun Duration.coerceNonNegative(): Duration = if (isNegative) Duration.ZERO else this
internal fun PhaseExecution.label(): String = when (kind) {
  PhaseExecutionKind.PASS -> "pass $count"
  PhaseExecutionKind.SEMANTIC_LOOP -> "loop $count"
  PhaseExecutionKind.GATE_RUN -> "gate run $count"
  PhaseExecutionKind.BOUNDED_EDGE -> total?.let { "$count/$it" } ?: "edge $count"
  PhaseExecutionKind.ATTEMPT -> "attempt $count"
}
internal fun durationText(value: Long?, asOf: Instant?, item: WorkItem, now: Instant, boardStale: Boolean, frozen: Long? = null): String = (if (boardStale) frozen ?: value else value?.let { displayedDuration(it, asOf, now, item.freshness, item.lifecycle == Lifecycle.ACTIVE) })?.let { age(Duration.ofMillis(it)) } ?: "not available"
internal fun phaseElapsedText(item: WorkItem, now: Instant, stale: Boolean): String {
  val start = item.startedAt ?: return "not available"
  val end = item.finishedAt ?: if (!stale && item.freshness == Freshness.FRESH && item.lifecycle == Lifecycle.ACTIVE) now else item.updatedAt
  return age(Duration.between(start, end).coerceNonNegative())
}
internal fun FileName(path: String) = path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')
internal fun WorkItem.repositoryTitle() = repositoryName ?: FileName(repositoryRoot)
@Composable
internal fun tickingNow(): State<Instant> {
  val now = remember { mutableStateOf(Instant.now()) }
  LaunchedEffect(Unit) { while (true) { delay(1000); now.value = Instant.now() } }
  return now
}

