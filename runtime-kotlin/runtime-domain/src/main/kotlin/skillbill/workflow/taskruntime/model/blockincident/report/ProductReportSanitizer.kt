package skillbill.workflow.taskruntime.model.blockincident.report

import skillbill.text.sha256HexUtf8
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentCertainty
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentDiagnosisRecord
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentOccurrence
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryAttempt
import skillbill.contracts.workflow.identity.blockincident.BlockIncidentReportKeys as Keys

object ProductReportSanitizer {
  const val MAX_BODY_BYTES = 16 * 1024
  private const val MAX_EVIDENCE = 8
  private const val MAX_FIELD_CHARS = 2048
  private const val MAX_REPRODUCTION_CHARS = 4000
  private const val MAX_TITLE_BYTES = 200
  private const val MAX_EVIDENCE_SUMMARY_BYTES = 512
  private const val MAX_EVIDENCE_ITEM_CHARS = 540
  private const val FINGERPRINT_MARKER = Keys.REPORT_FINGERPRINT_MARKER
  private const val INCIDENT_MARKER = "<!-- skill-bill-incident:"
  private const val TITLE_PREFIX = "Skill Bill: "

  fun createPayload(
    draft: ProductReportDraft,
    reportId: String,
  ): ProductReportCreatePayload? {
    val originMarker = "${Keys.REPORT_ORIGIN_MARKER}$reportId -->"
    val fingerprintMarker = "$FINGERPRINT_MARKER${draft.fingerprint} -->"
    if (ProductReportRedaction.hasResidualSecret(originMarker)) return null
    val content = draft.body.substringBeforeLast("\n$FINGERPRINT_MARKER", draft.body)
    val suffix = "\n$originMarker\n$fingerprintMarker"
    val budget = MAX_BODY_BYTES - suffix.toByteArray(Charsets.UTF_8).size
    if (budget < 0) return null
    val bounded = truncateUtf8(content, budget)
    val rendered = bounded + suffix
    val safeBody =
      rendered.takeIf {
        it.toByteArray(Charsets.UTF_8).size <= MAX_BODY_BYTES && !ProductReportRedaction.hasResidualToken(it)
      } ?: return null
    val outcomes = draft.fieldOutcomes.toMutableMap()
    if (bounded != content) outcomes[Keys.REPORT_BODY] = ProductReportFieldOutcome.TRUNCATED
    return ProductReportCreatePayload(safeBody, outcomes.toMap())
  }

  fun sanitize(
    occurrence: BlockIncidentOccurrence,
    diagnosis: BlockIncidentDiagnosisRecord,
    recoveryAttempts: List<BlockIncidentRecoveryAttempt>,
    runtimeFacts: ProductReportRuntimeFacts,
    incidentCommentToken: String? = null,
  ): ProductReportSanitization {
    val cause = diagnosis.cause ?: diagnosis.uncertaintyNote ?: "Evidence gap: cause not established"
    val fingerprint =
      sha256HexUtf8(
        listOf(
          occurrence.failureCode.orEmpty(),
          occurrence.origin.wireValue,
          occurrence.phaseId.orEmpty(),
          ProductReportRedaction.normalizeCause(cause, fingerprintIdentities(occurrence, recoveryAttempts)),
        ).joinToString(":"),
      )
    val outcomes = linkedMapOf<String, ProductReportFieldOutcome>()
    val values = linkedMapOf<String, String?>()
    val scanValues = mutableListOf<String>()

    fun add(
      key: String,
      raw: String?,
      cap: Int = MAX_FIELD_CHARS,
      sanitizedFragments: List<String>? = null,
      sanitizedRedacted: Boolean = false,
    ) {
      if (raw.isNullOrBlank()) {
        outcomes[key] = ProductReportFieldOutcome.DROPPED
        values[key] = null
        return
      }
      val (safe, redacted) =
        if (sanitizedFragments == null) ProductReportRedaction.redact(raw) else raw to sanitizedRedacted
      val truncated = safe.length > cap
      outcomes[key] =
        when {
          truncated -> ProductReportFieldOutcome.TRUNCATED
          redacted -> ProductReportFieldOutcome.REDACTED
          else -> ProductReportFieldOutcome.KEPT
        }
      values[key] = safe.take(cap)
      scanValues += sanitizedFragments ?: listOf(safe.take(cap))
    }
    add(Keys.SUMMARY, diagnosis.summary, BlockIncidentDiagnosisRecord.SUMMARY_MAX)
    add(Keys.WHAT_HAPPENED, diagnosis.whatHappened, BlockIncidentDiagnosisRecord.WHAT_HAPPENED_MAX)
    val (safeCause, causeRedacted) = ProductReportRedaction.redact(cause)
    val causeLabel = if (diagnosis.certainty == BlockIncidentCertainty.DIAGNOSED) "" else "Suspected: "
    add(
      Keys.CAUSE,
      causeLabel + safeCause,
      BlockIncidentDiagnosisRecord.CAUSE_MAX,
      listOf(safeCause),
      causeRedacted,
    )
    add(Keys.REPRODUCTION_STEPS, diagnosis.reproductionSteps, MAX_REPRODUCTION_CHARS)
    add(Keys.RUNTIME_VERSION, runtimeFacts.runtimeVersion)
    add(Keys.RUNTIME_BUILD, runtimeFacts.runtimeBuild)
    add(Keys.OS_FAMILY, runtimeFacts.osFamily)
    add(Keys.PROVIDER_ID, runtimeFacts.providerId)
    add(Keys.PHASE_STRATEGY_ID, runtimeFacts.phaseStrategyId)
    add(Keys.MODEL_ID, runtimeFacts.modelId)
    add(Keys.FAILURE_CODE, occurrence.failureCode)
    add(Keys.FAILURE_DISPOSITION, occurrence.failureDisposition)
    addEvidence(diagnosis, ::add, outcomes)
    add(Keys.OBSERVED_AT, occurrence.observedAt.toString())
    add(Keys.DIAGNOSED_AT, diagnosis.completedAt.toString())
    add(Keys.INCIDENT_TOKEN, occurrence.occurrenceToken)
    add(Keys.WORKFLOW_ID, occurrence.workflowId)
    add(Keys.PARENT_WORKFLOW_ID, occurrence.parentWorkflowId)
    add(Keys.PHASE_ID, occurrence.phaseId)
    add(Keys.INVOCATION_ID, occurrence.invocationId)
    add(Keys.ATTEMPT, occurrence.attempt?.toString())
    addRecovery(recoveryAttempts, ::add, outcomes)
    add(Keys.RECOVERY_AT, recoveryAttempts.lastOrNull()?.updatedAt?.toString())
    listOf(
      Keys.WORKTREE_PATH,
      Keys.REPOSITORY_ROOT,
      Keys.PROMPTS,
      Keys.BRIEFING_TEXT,
      Keys.PRODUCER_OUTPUT_BODIES,
      Keys.ENVIRONMENT_VARIABLES,
      Keys.DATABASE_ROW_CONTENT,
    ).forEach { outcomes[it] = ProductReportFieldOutcome.DROPPED }

    val marker =
      if (incidentCommentToken == null) {
        "$FINGERPRINT_MARKER$fingerprint -->"
      } else {
        "$INCIDENT_MARKER$incidentCommentToken -->"
      }
    return createDraft(fingerprint, marker, values, outcomes, scanValues)
  }

  private fun createDraft(
    fingerprint: String,
    marker: String,
    values: Map<String, String?>,
    outcomes: MutableMap<String, ProductReportFieldOutcome>,
    scanValues: List<String>,
  ): ProductReportSanitization {
    val (body, bodyTruncated) = renderBody(values, outcomes, marker)
    outcomes[Keys.REPORT_BODY] =
      if (bodyTruncated) ProductReportFieldOutcome.TRUNCATED else ProductReportFieldOutcome.KEPT
    val (title, titleRedacted) =
      ProductReportRedaction.redact(values[Keys.FAILURE_CODE] ?: "block incident")
    val safeTitle = truncateUtf8(TITLE_PREFIX + title, MAX_TITLE_BYTES)
    outcomes[Keys.REPORT_TITLE] =
      when {
        safeTitle != TITLE_PREFIX + title -> ProductReportFieldOutcome.TRUNCATED
        titleRedacted -> ProductReportFieldOutcome.REDACTED
        else -> ProductReportFieldOutcome.KEPT
      }
    val frozenOutcomes = outcomes.toMap()
    val residualRenderedContent =
      ProductReportRedaction.hasResidualToken(body) ||
        ProductReportRedaction.hasResidualSecret(marker) ||
        ProductReportRedaction.hasResidualSecret(safeTitle.removePrefix(TITLE_PREFIX))
    return if (
      residualRenderedContent || scanValues.any(ProductReportRedaction::hasResidualSecret)
    ) {
      ProductReportRejected(ProductReportFailure.RESIDUAL_SECRET, fingerprint, frozenOutcomes)
    } else {
      ProductReportDraft(safeTitle, body, fingerprint, frozenOutcomes)
    }
  }

  private fun fingerprintIdentities(
    occurrence: BlockIncidentOccurrence,
    recoveryAttempts: List<BlockIncidentRecoveryAttempt>,
  ): List<String> =
    buildList {
      addAll(
        listOfNotNull(
          occurrence.occurrenceToken,
          occurrence.workflowId,
          occurrence.parentWorkflowId,
          occurrence.phaseRunId,
          occurrence.invocationId,
          occurrence.executionId,
        ),
      )
      recoveryAttempts.forEach { attempt ->
        add(attempt.attemptId)
        add(attempt.targetWorkflowId)
        attempt.targetInvocationId?.let(::add)
      }
    }

  private fun addEvidence(
    diagnosis: BlockIncidentDiagnosisRecord,
    add: (String, String?, Int, List<String>?, Boolean) -> Unit,
    outcomes: MutableMap<String, ProductReportFieldOutcome>,
  ) {
    val admitted = diagnosis.evidence.take(MAX_EVIDENCE)
    val items =
      admitted.map { evidence ->
        val (safe, redacted) = ProductReportRedaction.redact(evidence.summary)
        val clipped = truncateUtf8(safe, MAX_EVIDENCE_SUMMARY_BYTES)
        Triple(clipped, redacted, clipped != safe)
      }
    val assembled =
      admitted.zip(items).joinToString("\n") { (evidence, item) -> "${evidence.kind.wireValue}: ${item.first}" }
        .takeIf(String::isNotBlank)
    add(Keys.EVIDENCE, assembled, MAX_EVIDENCE * MAX_EVIDENCE_ITEM_CHARS, items.map { it.first }, false)
    outcomes[Keys.EVIDENCE] =
      when {
        diagnosis.evidence.size > MAX_EVIDENCE || items.any { it.third } ||
          (assembled?.length ?: 0) > MAX_EVIDENCE * MAX_EVIDENCE_ITEM_CHARS ->
          ProductReportFieldOutcome.TRUNCATED
        items.any { it.second } -> ProductReportFieldOutcome.REDACTED
        assembled == null -> ProductReportFieldOutcome.DROPPED
        else -> ProductReportFieldOutcome.KEPT
      }
  }

  private fun addRecovery(
    recoveryAttempts: List<BlockIncidentRecoveryAttempt>,
    add: (String, String?, Int, List<String>?, Boolean) -> Unit,
    outcomes: MutableMap<String, ProductReportFieldOutcome>,
  ) {
    if (recoveryAttempts.isEmpty()) {
      add(Keys.RECOVERY_ATTEMPT_ID, null, MAX_FIELD_CHARS, null, false)
      add(Keys.RECOVERY_OUTCOMES, null, MAX_FIELD_CHARS, null, false)
      return
    }
    val ids = recoveryAttempts.map { ProductReportRedaction.redact(it.attemptId) }
    val outcomesText =
      recoveryAttempts.map { attempt ->
        val (id, idRedacted) = ProductReportRedaction.redact(attempt.attemptId)
        val (status, statusRedacted) = ProductReportRedaction.redact(attempt.status.wireValue)
        val (time, timeRedacted) = ProductReportRedaction.redact(attempt.updatedAt.toString())
        Triple("$id: $status at $time", idRedacted || statusRedacted || timeRedacted, listOf(id, status, time))
      }
    add(
      Keys.RECOVERY_ATTEMPT_ID,
      ids.joinToString(", ") { it.first },
      MAX_FIELD_CHARS,
      ids.map { it.first },
      ids.any { it.second },
    )
    outcomes[Keys.RECOVERY_ATTEMPT_ID] =
      when {
        ids.joinToString(", ") { it.first }.length > MAX_FIELD_CHARS -> ProductReportFieldOutcome.TRUNCATED
        ids.any { it.second } -> ProductReportFieldOutcome.REDACTED
        else -> ProductReportFieldOutcome.KEPT
      }
    val joined = outcomesText.joinToString("\n") { it.first }
    add(
      Keys.RECOVERY_OUTCOMES,
      joined,
      MAX_FIELD_CHARS,
      outcomesText.flatMap { it.third },
      outcomesText.any { it.second },
    )
    outcomes[Keys.RECOVERY_OUTCOMES] =
      when {
        joined.length > MAX_FIELD_CHARS -> ProductReportFieldOutcome.TRUNCATED
        outcomesText.any { it.second } -> ProductReportFieldOutcome.REDACTED
        else -> ProductReportFieldOutcome.KEPT
      }
  }

  private fun renderBody(
    values: Map<String, String?>,
    outcomes: MutableMap<String, ProductReportFieldOutcome>,
    marker: String,
  ): Pair<String, Boolean> {
    val prefix = "Skill Bill block incident\n\n"
    val tail = "\n\n$marker"
    val budget = MAX_BODY_BYTES - prefix.toByteArray(Charsets.UTF_8).size - tail.toByteArray(Charsets.UTF_8).size
    val rendered = mutableListOf<String>()
    var used = 0
    var truncated = false
    values.forEach { (key, value) ->
      if (value == null) return@forEach
      val line = "$key: $value"
      val separator = if (rendered.isEmpty()) 0 else 1
      val remaining = budget - used - separator
      if (remaining <= 0) {
        outcomes[key] = ProductReportFieldOutcome.DROPPED
        truncated = true
        return@forEach
      }
      val clipped = truncateUtf8(line, remaining)
      if (clipped != line) {
        val valueBudget = (remaining - "$key: ".toByteArray(Charsets.UTF_8).size).coerceAtLeast(0)
        val safeValue = truncateUtf8(value, valueBudget)
        if (safeValue.isEmpty()) {
          outcomes[key] = ProductReportFieldOutcome.DROPPED
        } else {
          rendered += "$key: $safeValue"
          used += separator + rendered.last().toByteArray(Charsets.UTF_8).size
          outcomes[key] = ProductReportFieldOutcome.TRUNCATED
        }
        truncated = true
      } else {
        rendered += line
        used += separator + line.toByteArray(Charsets.UTF_8).size
      }
    }
    val body = prefix + rendered.joinToString("\n") + tail
    return body to truncated
  }

  private fun truncateUtf8(
    value: String,
    maxBytes: Int,
  ): String {
    if (maxBytes <= 0) return ""
    if (value.toByteArray(Charsets.UTF_8).size <= maxBytes) return value
    var end = value.length
    while (end > 0 && value.substring(0, end).toByteArray(Charsets.UTF_8).size > maxBytes) end--
    if (end > 0 && value[end - 1].isHighSurrogate()) end--
    var result = value.substring(0, end)
    for (size in 1 until "[redacted]".length) {
      val partial = "[redacted]".take(size)
      if (result.endsWith(partial) && value.length > result.length) {
        result = result.dropLast(partial.length)
        break
      }
    }
    return result
  }
}
