package dev.skillbill.desktop.feature.detail

import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.WorkItem
import kotlin.time.Instant

internal val PLAN_STAGES = listOf(
  "preplan" to "Reading the repo",
  "plan" to "Splitting the work",
  "plan_subtasks" to "Writing subtask specs",
)

internal enum class PlanStageState { NotStarted, Running, Paused, Done, Failed, Blocked }

internal data class PlanStageView(
  val phaseId: String,
  val label: String,
  val state: PlanStageState,
  val startedAt: Instant?,
  val finishedAt: Instant?,
)

internal fun planStageViews(item: WorkItem, detail: Detail): List<PlanStageView> {
  val plan = detail.standalonePlan
  val stages = plan?.stages.orEmpty().associateBy { it.phaseId }
  val units = plan?.units.orEmpty()
  val waves = plan?.waves.orEmpty()
  return PLAN_STAGES.map { (phaseId, label) ->
    val stage = stages[phaseId]
    val status = stage?.status
    val fanOut = phaseId == "plan_subtasks"
    val state = when {
      status == "blocked" || (fanOut && hasBlockedSubtasks(plan) && status != "completed") -> PlanStageState.Blocked
      status == "running" -> if (item.running) PlanStageState.Running else PlanStageState.Paused
      status == "paused" -> PlanStageState.Paused
      status == "completed" -> PlanStageState.Done
      status == "failed" -> PlanStageState.Failed
      fanOut && (status == null || status == "pending") -> when {
        item.running && (units.any { it.state in setOf("writing", "saved", "blocked") } || waves.isNotEmpty() ||
          detail.planActivity.any { it.phaseId == "plan_subtasks" }) -> PlanStageState.Running
        !item.running && units.isNotEmpty() && units.all { it.state == "saved" } -> PlanStageState.Done
        else -> PlanStageState.NotStarted
      }
      else -> PlanStageState.NotStarted
    }
    val startedAt = stage?.firstStartedAt
      ?: if (fanOut) (units.mapNotNull { it.startedAt } + waves.mapNotNull { it.startedAt }).minOrNull() else null
    PlanStageView(phaseId, label, state, startedAt, stage?.finishedAt)
  }
}

internal fun planStageStatus(state: PlanStageState): String = when (state) {
  PlanStageState.Blocked -> "blocked"
  PlanStageState.Running -> "active"
  PlanStageState.Paused -> "paused"
  PlanStageState.Done -> "done"
  PlanStageState.Failed -> "failed"
  PlanStageState.NotStarted -> "queued"
}

internal fun planRoots(item: WorkItem): List<String> =
  listOf(item.checkoutRoot, item.repositoryRoot).filter(String::isNotBlank).distinct()

internal data class PlanOutputText(val subtaskId: Int?, val kind: String, val verb: String, val subject: String) {
  val display: String get() = listOf(verb, subject).filter(String::isNotBlank).joinToString(" ")
}

private val SUBTASK_PREFIX = Regex("""^\[subtask (\d+)] """)
private val TOOL_LINE = Regex("""^Tool (\S+)(?: (.*))?$""", RegexOption.DOT_MATCHES_ALL)
// No closing quote required: the producer cuts the JSON at a character limit, so the last value may be truncated.
private val TOOL_KEY = Regex(""""(command|file_path|path|pattern|query|description)"\s*:\s*"((?:[^"\\]|\\.)*)""")
private val ABSOLUTE_PATH = Regex("""(?<![A-Za-z0-9_])(?:[A-Za-z]:[/\\]|/)[^\s"'<>]+""")

internal fun planOutputText(raw: String, roots: List<String>): PlanOutputText {
  val prefix = SUBTASK_PREFIX.find(raw)
  val subtaskId = prefix?.groupValues?.get(1)?.toIntOrNull()
  val line = prefix?.let { raw.substring(it.range.last + 1) } ?: raw
  val tool = TOOL_LINE.find(line) ?: return PlanOutputText(subtaskId, "note", "", shortenPathsIn(line, roots))
  val name = tool.groupValues[1]
  val values = linkedMapOf<String, String>()
  TOOL_KEY.findAll(tool.groupValues[2]).forEach { match -> values.putIfAbsent(match.groupValues[1], unescapeJson(match.groupValues[2])) }
  val path = (values["file_path"] ?: values["path"])?.let { shortenPlanPath(it, roots) }.orEmpty()
  return when {
    name == "Bash" -> PlanOutputText(subtaskId, "command", "Ran", shortenPathsIn(values["command"].orEmpty(), roots))
    name == "Read" -> PlanOutputText(subtaskId, "read", "Read", path)
    name in setOf("Write", "Edit", "MultiEdit") -> PlanOutputText(subtaskId, "write", "Wrote", path)
    name in setOf("Grep", "Glob") -> PlanOutputText(subtaskId, "search", "Searched", values["pattern"] ?: values["query"] ?: path)
    name.startsWith("mcp__") -> PlanOutputText(subtaskId, "mcp", "Called", name.substringAfterLast("__"))
    else -> PlanOutputText(subtaskId, "tool", name, values.values.firstOrNull()?.let { shortenPathsIn(it, roots) }.orEmpty())
  }
}

private fun unescapeJson(value: String): String = Regex("""\\(.)""").replace(value) { match ->
  when (val c = match.groupValues[1]) { "n" -> "\n"; "t" -> "\t"; else -> c }
}

private fun shortenPathsIn(text: String, roots: List<String>): String {
  return ABSOLUTE_PATH.replace(text) { match ->
    val path = match.value.trimEnd(',', ';', ':', '.', ')', ']', '}')
    val suffix = match.value.drop(path.length)
    shortenPlanPath(path, roots) + suffix
  }
}

internal fun shortenPlanPath(path: String, roots: List<String>): String {
  val normalizedPath = path.replace('\\', '/')
  val relative = roots.map { it.replace('\\', '/').trimEnd('/') }.filter(String::isNotEmpty).firstNotNullOfOrNull { root ->
    when {
      normalizedPath == root -> ""
      normalizedPath.startsWith("$root/") -> normalizedPath.removePrefix("$root/")
      else -> null
    }
  } ?: normalizedPath
  if (relative.startsWith("/") || relative.getOrNull(1) == ':') return relative
  val segments = relative.split('/')
  return if (segments.size > 3) "${segments.first()}/…/${segments[segments.size - 2]}/${segments.last()}" else relative
}

internal fun planActivitySummary(events: List<PlanActivityEvent>): String {
  fun count(kinds: Set<String>) = events.filter { it.kind in kinds }.sumOf { 1 + it.children.size }
  return listOf(
    count(setOf("read")) to "read",
    count(setOf("search")) to "searched",
    count(setOf("write")) to "written",
    count(setOf("command", "mcp", "tool")) to "commands",
  ).filter { it.first > 0 }.joinToString(" · ") { (n, label) -> "$n $label" }
}
