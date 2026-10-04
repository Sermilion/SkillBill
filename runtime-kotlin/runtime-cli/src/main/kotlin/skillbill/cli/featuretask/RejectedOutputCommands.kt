package skillbill.cli.featuretask

import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import me.tatarka.inject.annotations.Inject
import skillbill.application.diagnostics.RejectedOutputDiagnosticInspection
import skillbill.application.diagnostics.model.RejectedOutputDiagnosticDeletion
import skillbill.application.diagnostics.model.RejectedOutputDiagnosticInspectionResult
import skillbill.application.diagnostics.model.RejectedOutputDiagnosticMetadata
import skillbill.cli.kernel.cli.CliRunState
import skillbill.cli.kernel.cli.DocumentedCliCommand
import skillbill.error.core.RejectedOutputDiagnosticFailureCode
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rejectedOutputDiagnosticAbsentMessage
import skillbill.error.core.rejectedOutputDiagnosticExpiredMessage
import skillbill.error.core.rejectedOutputDiagnosticInvalidRequestMessage
import skillbill.error.core.rejectedOutputDiagnosticOversizedMessage
import skillbill.ports.diagnostics.model.RejectedOutputDiagnosticSelector

@Inject
class RejectedOutputInspectCliCommand(
  private val inspection: RejectedOutputDiagnosticInspection,
  private val state: CliRunState,
) : DocumentedCliCommand(
    "rejected-output",
    "Inspect rejected phase output metadata, or emit one exact stored body with --raw-output.",
  ) {
  private val workflowId by option("--workflow", help = "Workflow identifier.").required()
  private val phaseId by option("--phase", help = "Optional phase selector.")
  private val attempt by option("--attempt", help = "Optional attempt selector.").int()
  private val repairTurn by option(
    "--repair-turn",
    help = "Optional validation-gate repair-turn selector within one attempt; 0 is an ordinary attempt.",
  ).int()
  private val rawOutput by option(
    "--raw-output",
    help = "Write the exact stored response bytes; the selector must resolve to one record.",
  ).flag(default = false)

  override fun run() {
    val selector = RejectedOutputDiagnosticSelector(workflowId, phaseId, attempt, repairTurn)
    when (val result = inspection.inspect(selector, rawOutput)) {
      is RejectedOutputDiagnosticInspectionResult.RawBytes ->
        state.completeRaw(result.bytes)
      is RejectedOutputDiagnosticInspectionResult.Metadata -> {
        val lines = result.records.map { it.safeLine() }
        val text = lines.joinToString("\n", postfix = if (lines.isEmpty()) "" else "\n")
        state.completeText(text, emptyMap())
      }
      is RejectedOutputDiagnosticInspectionResult.Absent ->
        throw retrievalFailure(rejectedOutputDiagnosticAbsentMessage(result.identity))
      is RejectedOutputDiagnosticInspectionResult.Expired ->
        throw retrievalFailure(rejectedOutputDiagnosticExpiredMessage(result.identity))
      is RejectedOutputDiagnosticInspectionResult.Oversized ->
        throw retrievalFailure(rejectedOutputDiagnosticOversizedMessage(result.identity))
      is RejectedOutputDiagnosticInspectionResult.AmbiguousSelector ->
        throw retrievalFailure("Rejected output diagnostic retrieval failed: $AMBIGUOUS_RAW_SELECTOR_REASON")
      is RejectedOutputDiagnosticInspectionResult.InvalidRequest ->
        throw invalidRequestFailure(result.reason)
    }
  }
}

@Inject
class RejectedOutputCleanupCliCommand(
  private val inspection: RejectedOutputDiagnosticInspection,
  private val state: CliRunState,
) : DocumentedCliCommand(
    "rejected-output-cleanup",
    "Delete rejected-output diagnostics selected within one workflow.",
  ) {
  private val workflowId by option("--workflow", help = "Workflow identifier.").required()
  private val phaseId by option("--phase", help = "Optional phase selector.")
  private val attempt by option("--attempt", help = "Optional attempt selector.").int()
  private val repairTurn by option(
    "--repair-turn",
    help = "Optional validation-gate repair-turn selector within one attempt; 0 is an ordinary attempt.",
  ).int()

  override fun run() {
    val deleted =
      when (
        val deletion =
          inspection.cleanup(
            RejectedOutputDiagnosticSelector(workflowId, phaseId, attempt, repairTurn),
          )
      ) {
        is RejectedOutputDiagnosticDeletion.Deleted -> deletion.count
        is RejectedOutputDiagnosticDeletion.InvalidRequest -> throw invalidRequestFailure(deletion.reason)
      }
    state.completeText("deleted=$deleted\n", mapOf("deleted" to deleted))
  }
}

private fun retrievalFailure(message: String): SkillBillRuntimeException =
  SkillBillRuntimeException(RejectedOutputDiagnosticFailureCode.RETRIEVAL, message)

private fun invalidRequestFailure(reason: String): SkillBillRuntimeException =
  SkillBillRuntimeException(
    RejectedOutputDiagnosticFailureCode.INVALID_REQUEST,
    rejectedOutputDiagnosticInvalidRequestMessage(reason),
  )

private const val AMBIGUOUS_RAW_SELECTOR_REASON: String =
  "raw output requires a selector resolving to exactly one diagnostic; " +
    "an attempt that ran a validation-gate repair cycle holds one per repair turn, " +
    "so add --repair-turn (the metadata listing prints each turn)"

private fun RejectedOutputDiagnosticMetadata.safeLine(): String =
  listOf(
    "identity=${identity.safeField()}",
    "workflow=${workflowId.safeField()}",
    "phase=${phaseId.safeField()}",
    "attempt=$attempt",
    "repair_turn=$repairTurn",
    "rule=${rule.safeField()}",
    "path=${path.safeField()}",
    "reason=${reason.safeField()}",
    "agent=${agentId.safeField()}",
    "model=${model.safeField()}",
    "recorded_at=$recordedAt",
    "byte_size=$byteSize",
    "sha256=$sha256",
    "lifecycle=${lifecycle.name.lowercase()}",
  ).joinToString(" ")

private const val CONTROL_CHARACTER_LIMIT: Int = 0x20
private const val DELETE_CHARACTER_CODE: Int = 0x7f
private const val HEX_RADIX: Int = 16
private const val UNICODE_ESCAPE_WIDTH: Int = 4

private fun String.safeField(): String =
  buildString {
    append('"')
    this@safeField.forEach { character ->
      when (character) {
        '\\' -> append("\\\\")
        '"' -> append("\\\"")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else ->
          if (character.code < CONTROL_CHARACTER_LIMIT || character.code == DELETE_CHARACTER_CODE) {
            append("\\u")
            append(character.code.toString(HEX_RADIX).padStart(UNICODE_ESCAPE_WIDTH, '0'))
          } else {
            append(character)
          }
      }
    }
    append('"')
  }
