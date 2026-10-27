package skillbill.cli.work

import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.validate
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import me.tatarka.inject.annotations.Inject
import skillbill.application.work.WorkListService
import skillbill.application.work.model.WorkListItem
import skillbill.application.work.model.WorkListResult
import skillbill.cli.kernel.cli.CliRunState
import skillbill.cli.kernel.cli.DocumentedCliCommand
import skillbill.cli.kernel.cli.DocumentedNoOpCliCommand
import skillbill.cli.kernel.work.workBoardDiagnosticWireMap
import skillbill.cli.kernel.work.workDetailItemWireMap
import skillbill.cli.model.CliFormat
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.identity.status.WORK_BOARD_CONTRACT_VERSION
import skillbill.contracts.workflow.identity.status.WORK_DETAIL_CONTRACT_VERSION
import skillbill.contracts.workflow.identity.status.WorkBoardPayloadKeys
import skillbill.contracts.workflow.identity.status.WorkDetailPayloadKeys
import skillbill.contracts.workflow.identity.status.WorkPaginationLimits
import skillbill.engine.work.IdeStatusService
import skillbill.engine.work.model.IdeStatusRequest
import skillbill.ports.idestatus.WorkPayloadValidator
import skillbill.ports.idestatus.model.IdeStatusProblemCode
import skillbill.ports.idestatus.model.WorkBoardRequest
import skillbill.ports.idestatus.model.WorkBoardWindow
import skillbill.ports.idestatus.model.WorkDetailRequest
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Inject
class WorkTopLevelCommands(
  list: WorkListCommand,
  status: WorkStatusCommand,
  board: WorkBoardCommand,
  detail: WorkDetailCommand,
  recover: WorkRecoverCommand,
) {
  val command: DocumentedNoOpCliCommand =
    object : DocumentedNoOpCliCommand("work", "Inspect all persisted feature work.") {}
      .subcommands(list, status, board, detail, recover)
}

@Inject
class WorkBoardCommand(
  private val service: IdeStatusService,
  private val validator: WorkPayloadValidator,
  private val state: CliRunState,
) : DocumentedCliCommand("board", "Emit the machine-wide work board as JSON.") {
  private val format by option("--format", help = "Output format.")
    .choice("json")
    .default("json")
  private val finishedWithin by option("--finished-within", help = "Terminal retention window.")
    .choice(WorkBoardWindow.entries.associateBy { it.wireValue })
    .default(WorkBoardWindow.TWENTY_FOUR_HOURS)
  private val pageSize by option("--page-size", help = "Maximum candidates projected per page, from 1 to 100.")
    .int().default(WorkPaginationLimits.DEFAULT_PAGE_SIZE).validate {
      require(it in 1..WorkPaginationLimits.MAX_PAGE_SIZE) { "Page size must be from 1 to 100." }
    }
  private val cursor by option("--cursor", help = "Continue from the previous page's next_cursor.")

  override fun run() {
    val result = service.board(WorkBoardRequest(finishedWithin, pageSize = pageSize, cursor = cursor))
    val payload =
      linkedMapOf<String, Any?>(
        SharedPayloadKeys.CONTRACT_VERSION to WORK_BOARD_CONTRACT_VERSION,
        WorkBoardPayloadKeys.ITEMS to
          result.items.map { item ->
            linkedMapOf(
              WorkBoardPayloadKeys.SNAPSHOT to service.toWireMap(item.snapshot),
              WorkBoardPayloadKeys.REPOSITORY_ROOT to item.repositoryRoot,
              WorkBoardPayloadKeys.FINISHED_AT to item.finishedAt?.toString(),
              WorkBoardPayloadKeys.FINISHED_AT_SOURCE to item.finishedAtSource?.wireValue,
            )
          },
        WorkBoardPayloadKeys.OBSERVED_AT to result.observedAt.toString(),
        WorkBoardPayloadKeys.FINISHED_WITHIN to result.finishedWithin.wireValue,
        WorkBoardPayloadKeys.DIAGNOSTICS to result.diagnostics.map(::workBoardDiagnosticWireMap),
        WorkBoardPayloadKeys.PAGE_SIZE to result.pageSize,
        WorkBoardPayloadKeys.NEXT_CURSOR to result.nextCursor,
      )
    if (result.absentDatabase) {
      payload[WorkBoardPayloadKeys.PROBLEM] =
        mapOf(
          WorkBoardPayloadKeys.CODE to IdeStatusProblemCode.ABSENT_DATABASE.wireValue,
          WorkBoardPayloadKeys.MESSAGE to "Skill Bill database is not present.",
        )
    }
    validator.validateBoard(JsonCodec.mapToJsonString(payload), "work-board-cli")
    state.complete(payload, CliFormat.JSON)
  }
}

@Inject
class WorkDetailCommand(
  private val service: IdeStatusService,
  private val validator: WorkPayloadValidator,
  private val state: CliRunState,
) : DocumentedCliCommand("detail", "Emit one persisted work item and its detail as JSON.") {
  private val id by argument("id", help = "Workflow id or standalone phase invocation id.")
  private val format by option("--format", help = "Output format.")
    .choice("json")
    .default("json")
  private val pageSize by option(
    "--page-size",
    help = "Maximum records from each detail list per page, from 1 to 100. Goal subtasks fill one page of up to 100.",
  )
    .int().default(WorkPaginationLimits.DEFAULT_PAGE_SIZE).validate {
      require(it in 1..WorkPaginationLimits.MAX_PAGE_SIZE) { "Page size must be from 1 to 100." }
    }
  private val cursor by option("--cursor", help = "Continue from the previous detail page's next_cursor.")

  override fun run() {
    val result = service.detail(WorkDetailRequest(id, pageSize = pageSize, cursor = cursor))
    val payload =
      linkedMapOf<String, Any?>(
        SharedPayloadKeys.CONTRACT_VERSION to WORK_DETAIL_CONTRACT_VERSION,
        WorkDetailPayloadKeys.OBSERVED_AT to result.observedAt.toString(),
        WorkDetailPayloadKeys.ITEM to result.item?.let { workDetailItemWireMap(it, service.toWireMap(it.snapshot)) },
        WorkDetailPayloadKeys.PAGE_SIZE to result.pageSize,
        WorkDetailPayloadKeys.NEXT_CURSOR to result.nextCursor,
      )
    result.problem?.let {
      payload[WorkDetailPayloadKeys.PROBLEM] =
        mapOf(
          WorkDetailPayloadKeys.CODE to it.code.wireValue,
          WorkDetailPayloadKeys.MESSAGE to it.message,
        )
    }
    validator.validateDetail(JsonCodec.mapToJsonString(payload), "work-detail-cli")
    state.complete(payload, CliFormat.JSON)
  }
}

@Inject
class WorkListCommand(
  private val service: WorkListService,
  private val state: CliRunState,
) : DocumentedCliCommand("list", "List persisted feature-task, feature-verify, and feature-goal work.") {
  private val format by option("--format", help = "Output format.")
    .choice("table", "json")
    .default("table")
  private val limit by option("--limit", help = "Maximum number of rows.").int().validate {
    require(it > 0) { "--limit must be a positive integer." }
  }

  override fun run() {
    val result = service.list(limit = limit)
    val payload = result.toPayload()
    if (format == "json") {
      state.complete(payload, CliFormat.JSON)
    } else {
      state.completeText(result.toTable(), payload)
    }
  }
}

@Inject
class WorkStatusCommand(
  private val service: IdeStatusService,
  private val state: CliRunState,
) : DocumentedCliCommand(
    "status",
    "Emit one schema-valid IDE status snapshot for an explicit repository root.",
  ) {
  private val repoRoot by option(
    "--repo-root",
    help = "Repository root to resolve canonical repo-root-realpath-v1 identity.",
  ).required()
  private val format by option("--format", help = "Output format.")
    .choice("json")
    .default("json")

  override fun run() {
    val result =
      service.status(
        IdeStatusRequest(
          repoRoot = repoRoot,
        ),
      )
    state.complete(
      service.toWireMap(result.snapshot),
      CliFormat.JSON,
      exitCode = result.exitCode,
    )
  }
}

private fun WorkListResult.toPayload(): Map<String, Any?> =
  mapOf(
    "work" to work.map(WorkListItem::toPayload),
  )

private fun WorkListItem.toPayload(): Map<String, Any?> =
  linkedMapOf(
    SharedPayloadKeys.ISSUE_KEY to issueKey,
    "workflow_kind" to workflowKind.wireValue,
    SharedPayloadKeys.WORKFLOW_ID to workflowId,
    "started_at" to startedAt.toString(),
    "current_state" to currentState,
    "state_entered_at" to stateEnteredAt.toString(),
    "state_entered_at_estimated" to stateEnteredAtEstimated,
  )

private fun WorkListResult.toTable(): String {
  val headers = listOf("ISSUE", "KIND", "WORKFLOW", "STARTED", "STATE", "STATE SINCE")
  val formatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss z").withZone(ZoneId.systemDefault())
  val rows =
    work.map { item ->
      listOf(
        item.issueKey?.toTerminalSafeIssueKey() ?: "-",
        item.workflowKind.wireValue,
        item.workflowId.toTerminalSafeText(),
        formatter.format(item.startedAt),
        item.currentState,
        formatter.format(item.stateEnteredAt) + if (item.stateEnteredAtEstimated) "~" else "",
      )
    }
  val widths =
    headers.indices.map { index ->
      maxOf(terminalDisplayWidth(headers[index]), rows.maxOfOrNull { terminalDisplayWidth(it[index]) } ?: 0)
    }

  fun render(values: List<String>): String =
    values.indices.joinToString("  ") { index ->
      values[index].padTerminalEnd(widths[index])
    }
  return buildString {
    appendLine(render(headers))
    rows.forEach { appendLine(render(it)) }
    if (work.any { it.stateEnteredAtEstimated }) appendLine("~ estimated")
  }
}

internal fun String.toTerminalSafeIssueKey(): String {
  return toTerminalSafeText().truncateTerminalDisplayWidth(MAX_TABLE_ISSUE_KEY_DISPLAY_WIDTH)
}

internal fun String.toTerminalSafeText(): String {
  val sanitized =
    buildString(length) {
      this@toTerminalSafeText.codePoints().forEach { codePoint ->
        appendCodePoint(if (Character.isISOControl(codePoint)) REPLACEMENT_CODE_POINT else codePoint)
      }
    }
  return sanitized
}

internal fun terminalDisplayWidth(value: String): Int = value.codePoints().toArray().sumOf(::terminalDisplayWidth)

internal fun String.truncateTerminalDisplayWidth(maxWidth: Int): String {
  require(maxWidth > 0) { "maxWidth must be positive." }
  if (terminalDisplayWidth(this) <= maxWidth) {
    return this
  }
  val markerWidth = terminalDisplayWidth(TRUNCATION_MARKER)
  val contentWidth = (maxWidth - markerWidth).coerceAtLeast(0)
  val truncated = StringBuilder()
  var usedWidth = 0
  for (codePoint in codePoints().toArray()) {
    val codePointWidth = terminalDisplayWidth(codePoint)
    if (codePointWidth > contentWidth - usedWidth) {
      break
    }
    truncated.appendCodePoint(codePoint)
    usedWidth += codePointWidth
  }
  return truncated.append(TRUNCATION_MARKER).toString()
}

internal fun String.padTerminalEnd(width: Int): String =
  this + " ".repeat((width - terminalDisplayWidth(this)).coerceAtLeast(0))

private fun terminalDisplayWidth(codePoint: Int): Int =
  when {
    Character.isISOControl(codePoint) -> 1
    Character.getType(codePoint) in ZERO_WIDTH_CHARACTER_TYPES -> 0
    WIDE_CODE_POINT_RANGES.any { codePoint in it } -> 2
    else -> 1
  }

private val ZERO_WIDTH_CHARACTER_TYPES: Set<Int> =
  setOf(
    Character.NON_SPACING_MARK.toInt(),
    Character.COMBINING_SPACING_MARK.toInt(),
    Character.ENCLOSING_MARK.toInt(),
    Character.FORMAT.toInt(),
  )

private val WIDE_CODE_POINT_RANGES: List<IntRange> =
  listOf(
    "1100..115F",
    "2329..232A",
    "2E80..A4CF",
    "AC00..D7A3",
    "F900..FAFF",
    "FE10..FE19",
    "FE30..FE6F",
    "FF00..FF60",
    "FFE0..FFE6",
    "1B000..1B12F",
    "1B170..1B2FF",
    "1F200..1F251",
    "1F300..1FAFF",
    "1FC00..1FFFD",
    "20000..3FFFD",
  ).map(::parseHexadecimalRange)

private fun parseHexadecimalRange(value: String): IntRange {
  val (start, end) = value.split("..")
  return start.toInt(HEXADECIMAL_RADIX)..end.toInt(HEXADECIMAL_RADIX)
}

private const val HEXADECIMAL_RADIX = 16
private const val REPLACEMENT_CODE_POINT = 0xFFFD
private const val MAX_TABLE_ISSUE_KEY_DISPLAY_WIDTH = 128
private const val TRUNCATION_MARKER = "…"
