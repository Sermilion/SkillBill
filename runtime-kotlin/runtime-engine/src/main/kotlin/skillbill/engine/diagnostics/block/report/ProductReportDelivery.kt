package skillbill.engine.diagnostics.block.report

import me.tatarka.inject.annotations.Inject
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.diagnostics.containableUnlessInterrupted
import skillbill.error.core.catchingNonCancellation
import skillbill.ports.blockincident.BlockIncidentTelemetryPort
import skillbill.ports.blockincident.ProductIssueCredentialPort
import skillbill.ports.blockincident.ProductIssueTrackerPort
import skillbill.ports.blockincident.ProductReportDeliveryOwnershipPort
import skillbill.ports.blockincident.model.ProductIssueResult
import skillbill.ports.blockincident.model.ProductReportDeliveryOwnership
import skillbill.ports.blockincident.model.ProductReportIssue
import skillbill.ports.blockincident.model.ProductReportPendingComment
import skillbill.ports.blockincident.model.ProductReportRecord
import skillbill.ports.blockincident.model.ProductReportTransition
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.db.model.FencedTransactionResult
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.filesystem.model.DatabaseFileObservation
import skillbill.text.sha256HexUtf8
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentDiagnosisRecord
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentOccurrence
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryAttempt
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryAttemptStatus
import skillbill.workflow.taskruntime.model.blockincident.report.ProductReportCommentState
import skillbill.workflow.taskruntime.model.blockincident.report.ProductReportDraft
import skillbill.workflow.taskruntime.model.blockincident.report.ProductReportFailure
import skillbill.workflow.taskruntime.model.blockincident.report.ProductReportRuntimeFacts
import skillbill.workflow.taskruntime.model.blockincident.report.ProductReportSanitizer
import skillbill.workflow.taskruntime.model.blockincident.report.ProductReportStatus
import java.time.Clock
import java.time.Duration
import java.time.Instant

@Inject
public class ProductReportDelivery(
  private val database: DatabaseSessionFactory,
  private val clock: Clock,
  private val tracker: ProductIssueTrackerPort,
  private val credential: ProductIssueCredentialPort,
  private val diagnostics: RuntimeDiagnostics,
  private val telemetry: BlockIncidentTelemetryPort,
  private val runtimeFacts: ProductReportRuntimeFactsCollector,
  private val ownership: ProductReportDeliveryOwnershipPort,
) {
  internal fun deliver(limit: Int = BATCH_LIMIT) {
    val batchSize = limit.coerceIn(1, BATCH_LIMIT)
    val candidates =
      catchingNonCancellation {
        database.read { unit ->
          CandidateBatch(unit.productReports.dueIds(clock.instant(), batchSize), unit.databaseFileObservation)
        }
      }.getOrElse { failure ->
        containableUnlessInterrupted(failure)
        warn("Product report delivery scan failed.", failure)
        return
      }
    if (candidates.ids.isEmpty()) return
    val observation = candidates.observation ?: return
    val token =
      catchingNonCancellation {
        credential.token()
      }.getOrElse { failure ->
        containableUnlessInterrupted(failure)
        warn("Product issue credential lookup failed.", failure)
        return
      }
    candidates.ids.forEach { id ->
      catchingNonCancellation {
        deliverOne(id, token, observation)
      }.getOrElse { failure ->
        containableUnlessInterrupted(failure)
        warn("Product report delivery failed for $id.", failure)
      }
    }
  }

  private fun deliverOne(
    reportId: String,
    token: String?,
    expectedObservation: DatabaseFileObservation,
  ) {
    ownership.acquire(database.resolveDbPath(), reportId).useOwnership { lock ->
      when (lock) {
        ProductReportDeliveryOwnership.Contended -> return
        is ProductReportDeliveryOwnership.Failed -> {
          warn("Product report ownership could not be established for $reportId.", lock.cause)
          return
        }
        is ProductReportDeliveryOwnership.Held -> Unit
      }
      val snapshot =
        database.read { unit -> ReportSnapshot(unit.productReports.byId(reportId), unit.databaseFileObservation) }
      if (snapshot.observation != expectedObservation) return
      val report = snapshot.report ?: return
      val observation = expectedObservation
      when {
        token == null -> recordMissingAuth(report, observation)
        report.status in DELIVERED_STATUSES -> deliverComments(report, token, observation)
        else -> processSubmission(report, token, observation)
      }
    }
  }

  private fun processSubmission(
    observed: ProductReportRecord,
    token: String,
    observation: DatabaseFileObservation,
  ) {
    val resumed = prepareSubmission(observed.reportId, observation) ?: return
    val abandoned = observed.status == ProductReportStatus.SUBMITTING
    val unresolved = resumed.createUnresolved
    if (!unresolved && !resumed.attemptOpen && resumed.attempts >= MAX_ATTEMPTS) {
      val exhausted =
        transitionCommitted(
          ProductReportTransition(
            resumed.reportId,
            resumed.status,
            ProductReportStatus.FAILED,
            ProductReportFailure.RETRIES_EXHAUSTED,
            null,
            resumed.attempts,
            clock.instant().plus(RETRY_DELAYS[MAX_ATTEMPTS - 1]),
            clock.instant(),
            attemptOpen = false,
          ),
          observation,
        )
      exhausted?.let(::emitReported)
      return
    }
    val attempts = if (unresolved || resumed.attemptOpen || abandoned) resumed.attempts else resumed.attempts + 1
    val claimed =
      (
        transitionCommitted(
          ProductReportTransition(
            resumed.reportId,
            resumed.status,
            ProductReportStatus.SUBMITTING,
            null,
            null,
            attempts,
            null,
            clock.instant(),
            attemptOpen = true,
          ),
          observation,
        ) != null
      )
    if (!claimed) return
    val active = reload(resumed.reportId, observation) ?: return
    val lookup = tracker.findByFingerprint(active.fingerprint, token)
    reconcileLookup(active, lookup, token, observation)
  }

  private fun prepareSubmission(
    reportId: String,
    observation: DatabaseFileObservation,
  ): ProductReportRecord? {
    val current = reload(reportId, observation) ?: return null
    if (!submissionDue(current)) return null
    if (current.status != ProductReportStatus.SUBMITTING) return current
    val changed =
      transitionCommitted(
        ProductReportTransition(
          current.reportId,
          current.status,
          ProductReportStatus.AMBIGUOUS,
          null,
          null,
          current.attempts,
          clock.instant(),
          clock.instant(),
        ),
        observation,
      )
    return changed
  }

  private fun submissionDue(report: ProductReportRecord): Boolean =
    when {
      report.status !in SUBMISSION_STATUSES && report.status != ProductReportStatus.SUBMITTING -> false
      report.status == ProductReportStatus.LOCAL_ONLY -> report.failure == ProductReportFailure.MISSING_AUTH
      report.status in setOf(ProductReportStatus.PENDING, ProductReportStatus.AMBIGUOUS) ->
        report.nextAttemptAt?.isAfter(clock.instant()) != true
      else -> true
    }

  private fun reconcileLookup(
    active: ProductReportRecord,
    lookup: ProductIssueResult,
    token: String,
    observation: DatabaseFileObservation,
  ) {
    val attempts = active.attempts
    when (lookup) {
      is ProductIssueResult.Found -> {
        val creator =
          lookup.originReportId == active.reportId ||
            active.issue?.let { it.number == lookup.number && it.url == lookup.url } == true
        settleIssue(
          active,
          ProductReportIssue(lookup.number, lookup.url),
          creator,
          token,
          observation,
        )
      }
      ProductIssueResult.NotFound -> {
        if (active.createUnresolved) {
          if (attempts >= MAX_ATTEMPTS) {
            transitionCommitted(
              ProductReportTransition(
                active.reportId,
                active.status,
                ProductReportStatus.FAILED,
                ProductReportFailure.RETRIES_EXHAUSTED,
                null,
                attempts,
                clock.instant().plus(RETRY_DELAYS[MAX_ATTEMPTS - 1]),
                clock.instant(),
                attemptOpen = false,
                createUnresolved = false,
              ),
              observation,
            )?.let(::emitReported)
          } else {
            (
              transitionCommitted(
                ProductReportTransition(
                  active.reportId,
                  active.status,
                  ProductReportStatus.PENDING,
                  null,
                  null,
                  attempts,
                  clock.instant().plus(RETRY_DELAYS[(attempts - 1).coerceIn(0, MAX_ATTEMPTS - 1)]),
                  clock.instant(),
                  attemptOpen = false,
                  createUnresolved = false,
                ),
                observation,
              ) != null
            )
          }
          return
        }
        submitAfterNotFound(active, token, observation, attempts)
      }
      ProductIssueResult.Ambiguous -> settleFailure(active, ProductIssueFailure.AMBIGUOUS, attempts, observation)
      is ProductIssueResult.Failed ->
        settleFailure(active, ProductIssueFailure.Failed(lookup.failure, lookup.retryAt), attempts, observation)
      is ProductIssueResult.Created ->
        settleIssue(
          active,
          ProductReportIssue(lookup.number, lookup.url),
          true,
          token,
          observation,
        )
    }
  }

  private fun submitAfterNotFound(
    active: ProductReportRecord,
    token: String,
    observation: DatabaseFileObservation,
    attempts: Int,
  ) {
    if (attempts > MAX_ATTEMPTS || !active.attemptOpen) {
      transitionCommitted(
        ProductReportTransition(
          active.reportId,
          active.status,
          ProductReportStatus.FAILED,
          ProductReportFailure.RETRIES_EXHAUSTED,
          null,
          attempts,
          clock.instant().plus(RETRY_DELAYS[MAX_ATTEMPTS - 1]),
          clock.instant(),
          attemptOpen = false,
        ),
        observation,
      )
        ?.let(::emitReported)
      return
    }
    val payload = createPayload(active)
    if (payload == null) {
      warn("Product report create payload was withheld for ${active.reportId}.")
      transitionCommitted(
        ProductReportTransition(
          active.reportId,
          active.status,
          ProductReportStatus.FAILED,
          ProductReportFailure.RESIDUAL_SECRET,
          null,
          attempts,
          null,
          clock.instant(),
          attemptOpen = false,
        ),
        observation,
      )?.let(::emitReported)
      return
    }
    if (!saveCreateIntent(active.reportId, payload, observation)) return
    when (val created = tracker.create(active.issueTitle(), payload, listOf("skill-bill-report"), token)) {
      is ProductIssueResult.Created -> {
        settleIssue(
          active,
          ProductReportIssue(created.number, created.url),
          true,
          token,
          observation,
        )
      }
      ProductIssueResult.Ambiguous -> settleFailure(active, ProductIssueFailure.AMBIGUOUS, attempts, observation)
      is ProductIssueResult.Failed -> {
        settleFailure(active, ProductIssueFailure.Failed(created.failure, created.retryAt), attempts, observation)
      }
      else -> {
        settleFailure(
          active,
          ProductIssueFailure.Failed(ProductReportFailure.SUBMISSION_FAILED, null),
          attempts,
          observation,
        )
      }
    }
  }

  private fun createPayload(active: ProductReportRecord): String? {
    val draft =
      ProductReportDraft(
        active.issueTitle(),
        active.createPayload ?: active.body,
        active.fingerprint,
        active.redactionOutcomes,
      )
    return active.createPayload ?: ProductReportSanitizer.createPayload(draft, active.reportId)?.body
  }

  private fun saveCreateIntent(
    reportId: String,
    payload: String,
    observation: DatabaseFileObservation,
  ): Boolean =
    database.transactionFenced(observation) { unit ->
      unit.productReports.recordCreateIntent(reportId, payload, sha256HexUtf8(payload), clock.instant())
    }.committedTrue()

  private fun settleIssue(
    report: ProductReportRecord,
    issue: ProductReportIssue,
    createdHere: Boolean,
    token: String,
    observation: DatabaseFileObservation,
  ) {
    val status = if (createdHere) ProductReportStatus.DELIVERED else ProductReportStatus.LINKED_EXISTING
    val settled =
      transitionCommitted(
        ProductReportTransition(
          report.reportId,
          report.status,
          status,
          null,
          issue,
          report.attempts,
          null,
          clock.instant(),
          attemptOpen = false,
          createUnresolved = false,
        ),
        observation,
      ) ?: return
    if (!createdHere) {
      report.links.filter { it.commentState == ProductReportCommentState.NOT_REQUIRED }.forEach { link ->
        database.transactionFenced(observation) { unit ->
          unit.productReports.markComment(
            report.reportId,
            link.occurrenceToken,
            ProductReportCommentState.NOT_REQUIRED,
            ProductReportCommentState.PENDING,
          )
        }
      }
    }
    emitReported(settled)
    deliverComments(settled, token, observation)
  }

  private fun settleFailure(
    report: ProductReportRecord,
    outcome: ProductIssueFailure,
    attempts: Int,
    observation: DatabaseFileObservation,
  ) {
    val retry = retryOutcome(report, outcome, attempts)
    val finalStatus = retry.status
    val failure = retry.failure
    val dueAt = retry.dueAt
    val storedFailure =
      if (finalStatus == ProductReportStatus.FAILED) ProductReportFailure.RETRIES_EXHAUSTED else failure
    val ambiguous = finalStatus == ProductReportStatus.AMBIGUOUS
    val remoteFailure = if (report.createUnresolved && report.lastRemoteFailure != null) null else failure
    val change =
      transitionCommitted(
        ProductReportTransition(
          report.reportId,
          report.status,
          finalStatus,
          storedFailure,
          null,
          attempts,
          dueAt,
          clock.instant(),
          attemptOpen = ambiguous,
          lastRemoteFailure = remoteFailure,
          createUnresolved = ambiguous,
        ),
        observation,
      )
    if (change == null) return
    warn("Product report ${report.reportId} remains ${finalStatus.wireValue}.")
    if (finalStatus == ProductReportStatus.FAILED) emitReported(change)
  }

  private fun retryOutcome(
    report: ProductReportRecord,
    outcome: ProductIssueFailure,
    attempts: Int,
  ): RetryOutcome {
    val now = clock.instant()
    val normalAt = now.plus(RETRY_DELAYS[(attempts - 1).coerceIn(0, MAX_ATTEMPTS - 1)])
    val failure =
      when (outcome) {
        ProductIssueFailure.AMBIGUOUS -> ProductReportFailure.SUBMISSION_FAILED
        is ProductIssueFailure.Failed -> outcome.failure
      }
    val ambiguous = outcome == ProductIssueFailure.AMBIGUOUS || report.createUnresolved
    val finalStatus =
      when {
        ambiguous -> ProductReportStatus.AMBIGUOUS
        attempts >= MAX_ATTEMPTS -> ProductReportStatus.FAILED
        else -> ProductReportStatus.PENDING
      }
    val dueAt =
      when {
        failure == ProductReportFailure.RATE_LIMITED ->
          (outcome as? ProductIssueFailure.Failed)?.retryAt?.let { maxOf(normalAt, it) }
            ?: now.plus(Duration.ofHours(1))
        else -> normalAt
      }
    return RetryOutcome(
      finalStatus,
      failure,
      dueAt,
    )
  }

  private data class RetryOutcome(
    val status: ProductReportStatus,
    val failure: ProductReportFailure,
    val dueAt: Instant,
  )

  private fun recordMissingAuth(
    report: ProductReportRecord,
    observation: DatabaseFileObservation,
  ) {
    if (report.status == ProductReportStatus.LOCAL_ONLY && report.failure == ProductReportFailure.MISSING_AUTH) return
    if (report.status !in SUBMISSION_STATUSES && report.status != ProductReportStatus.SUBMITTING) return
    if (report.status in setOf(ProductReportStatus.PENDING, ProductReportStatus.AMBIGUOUS) &&
      report.nextAttemptAt?.isAfter(clock.instant()) == true
    ) {
      return
    }
    transitionCommitted(
      ProductReportTransition(
        report.reportId,
        report.status,
        ProductReportStatus.LOCAL_ONLY,
        ProductReportFailure.MISSING_AUTH,
        null,
        report.attempts,
        null,
        clock.instant(),
      ),
      observation,
    )?.let(::emitReported)
  }

  private fun deliverComments(
    report: ProductReportRecord,
    token: String,
    observation: DatabaseFileObservation,
  ) {
    val issue = report.issue ?: return
    report.links.forEach { staleLink ->
      catchingNonCancellation {
        val facts = commentFacts(staleLink.occurrenceToken) ?: return@forEach
        val prepared = preparePendingComment(report.reportId, issue, staleLink.occurrenceToken, facts, observation)
        val target =
          (prepared as? FencedTransactionResult.Committed)?.value as? CommentPreparation.Ready
            ?: run {
              if (prepared is FencedTransactionResult.Committed && prepared.value == CommentPreparation.Rejected) {
                warn(
                  "Product report comment was withheld after residual-secret rejection " +
                    "for ${report.reportId}.",
                )
              }
              return@forEach
            }
        postComment(report, CommentDispatch(staleLink.occurrenceToken, target.target), facts, observation, token)
      }.getOrElse { failure ->
        containableUnlessInterrupted(failure)
        warn("Product report comment failed for ${report.reportId}.", failure)
      }
    }
  }

  private fun postComment(
    report: ProductReportRecord,
    dispatch: CommentDispatch,
    facts: ProductReportRuntimeFacts,
    observation: DatabaseFileObservation,
    token: String,
  ) {
    val issue = report.issue ?: return
    val target = dispatch.target
    val occurrenceToken = dispatch.occurrenceToken
    when (tracker.comment(issue.number, target.body, token)) {
      is ProductIssueResult.Found, is ProductIssueResult.Created -> {
        val acknowledgement =
          acknowledgeComment(
            report,
            occurrenceToken,
            target,
            facts,
            observation,
          )
        if (acknowledgement is FencedTransactionResult.Committed &&
          acknowledgement.value == CommentAcknowledgement.Rejected
        ) {
          warn(
            "Product report comment was withheld after residual-secret rejection " +
              "for ${report.reportId}.",
          )
        }
      }
      is ProductIssueResult.Failed ->
        warn("Product report comment lookup or write failed for ${report.reportId}.")
      ProductIssueResult.Ambiguous ->
        warn("Product report comment result is ambiguous for ${report.reportId}.")
      else -> warn("Product report comment target was not found for ${report.reportId}.")
    }
  }

  private fun preparePendingComment(
    reportId: String,
    issue: ProductReportIssue,
    occurrenceToken: String,
    facts: ProductReportRuntimeFacts,
    observation: DatabaseFileObservation,
  ): FencedTransactionResult<CommentPreparation> =
    database.transactionFenced(observation) { unit ->
      val report = unit.productReports.byId(reportId) ?: return@transactionFenced CommentPreparation.Missing
      if (report.status !in DELIVERED_STATUSES || report.issue != issue) {
        return@transactionFenced CommentPreparation.Missing
      }
      val incident =
        unit.blockIncidents.find(occurrenceToken)?.occurrence
          ?: return@transactionFenced CommentPreparation.Missing
      val diagnosis =
        unit.blockIncidents.diagnoses(occurrenceToken).lastOrNull()
          ?: return@transactionFenced CommentPreparation.Missing
      val attempts = unit.blockIncidentRecoveries.history(occurrenceToken)
      val terminal = attempts.filter { it.status in TERMINAL_RECOVERY_STATUSES }
      unit.productReports.pendingRecoveryComments(
        occurrenceToken,
        terminal.map { it.attemptId }.toSet(),
        clock.instant(),
      )
      val link =
        unit.productReports.byId(reportId)?.links?.firstOrNull { it.occurrenceToken == occurrenceToken }
          ?: return@transactionFenced CommentPreparation.Missing
      if (report.status == ProductReportStatus.LINKED_EXISTING &&
        link.commentState == ProductReportCommentState.NOT_REQUIRED
      ) {
        unit.productReports.markComment(
          reportId,
          occurrenceToken,
          ProductReportCommentState.NOT_REQUIRED,
          ProductReportCommentState.PENDING,
        )
      }
      val pending =
        unit.productReports.byId(reportId)?.links?.firstOrNull { it.occurrenceToken == occurrenceToken }
          ?: return@transactionFenced CommentPreparation.Missing
      if (pending.commentState != ProductReportCommentState.PENDING) {
        return@transactionFenced CommentPreparation.Missing
      }
      val draft =
        sanitizeComment(incident, diagnosis, attempts, facts)
          ?: return@transactionFenced CommentPreparation.Rejected
      val target = pendingTarget(draft, terminal)
      if (!unit.productReports.savePendingComment(reportId, occurrenceToken, target)) {
        return@transactionFenced CommentPreparation.Missing
      }
      CommentPreparation.Ready(target)
    }

  private fun acknowledgeComment(
    delivered: ProductReportRecord,
    occurrenceToken: String,
    target: ProductReportPendingComment,
    facts: ProductReportRuntimeFacts,
    observation: DatabaseFileObservation,
  ): FencedTransactionResult<CommentAcknowledgement> =
    database.transactionFenced(observation) { unit ->
      val reportId = delivered.reportId
      val issue = delivered.issue
      val report = unit.productReports.byId(reportId) ?: return@transactionFenced CommentAcknowledgement.Missing
      if (report.status !in DELIVERED_STATUSES || report.issue != issue) {
        return@transactionFenced CommentAcknowledgement.Missing
      }
      val incident =
        unit.blockIncidents.find(occurrenceToken)?.occurrence
          ?: return@transactionFenced CommentAcknowledgement.Missing
      val diagnosis =
        unit.blockIncidents.diagnoses(occurrenceToken).lastOrNull()
          ?: return@transactionFenced CommentAcknowledgement.Missing
      val attempts = unit.blockIncidentRecoveries.history(occurrenceToken)
      val terminal = attempts.filter { it.status in TERMINAL_RECOVERY_STATUSES }
      val latest =
        sanitizeComment(incident, diagnosis, attempts, facts)
          ?: return@transactionFenced CommentAcknowledgement.Rejected
      val latestTarget = pendingTarget(latest, terminal)
      if (latestTarget != target) {
        unit.productReports.savePendingComment(reportId, occurrenceToken, latestTarget)
        return@transactionFenced CommentAcknowledgement.Advanced
      }
      if (unit.productReports.acknowledgePendingComment(
          reportId,
          occurrenceToken,
          target,
          terminal.map { it.attemptId }.toSet(),
        )
      ) {
        CommentAcknowledgement.Posted
      } else {
        CommentAcknowledgement.Advanced
      }
    }

  private fun sanitizeComment(
    incident: BlockIncidentOccurrence,
    diagnosis: BlockIncidentDiagnosisRecord,
    attempts: List<BlockIncidentRecoveryAttempt>,
    facts: ProductReportRuntimeFacts,
  ): ProductReportDraft? =
    ProductReportSanitizer.sanitize(
      incident,
      diagnosis,
      attempts,
      facts.copy(
        providerId = diagnosis.providerId,
        phaseStrategyId = diagnosis.phaseStrategyId,
        modelId = diagnosis.modelId,
      ),
      incidentCommentToken = incident.occurrenceToken,
    ) as? ProductReportDraft

  private fun commentFacts(occurrenceToken: String): ProductReportRuntimeFacts? {
    val diagnosis =
      database.read { unit -> unit.blockIncidents.diagnoses(occurrenceToken).lastOrNull() } ?: return null
    return runtimeFacts.collect(diagnosis)
  }

  private fun pendingTarget(
    draft: ProductReportDraft,
    terminalAttempts: List<BlockIncidentRecoveryAttempt>,
  ): ProductReportPendingComment {
    val represented =
      terminalAttempts.filter { attempt ->
        val line = "${attempt.attemptId}: ${attempt.status.wireValue} at ${attempt.updatedAt}"
        draft.body.contains(line)
      }.map { it.attemptId }.toSet()
    return ProductReportPendingComment(draft.body, sha256HexUtf8(draft.body), represented)
  }

  internal fun recoverySettled(
    attempt: BlockIncidentRecoveryAttempt,
    dispatch: Boolean = true,
  ) {
    val reference =
      catchingNonCancellation {
        database.read { unit ->
          ReportReference(
            unit.productReports.reportIdForIncident(attempt.occurrenceToken),
            unit.databaseFileObservation,
          )
        }
      }.getOrElse { failure ->
        containableUnlessInterrupted(failure)
        warn("Product report recovery lookup failed for ${attempt.attemptId}.", failure)
        return
      }
    val reportId = reference.reportId ?: return
    val expectedObservation = reference.observation ?: return
    catchingNonCancellation {
      ownership.acquire(database.resolveDbPath(), reportId).useOwnership { lock ->
        when (lock) {
          ProductReportDeliveryOwnership.Contended -> return@useOwnership
          is ProductReportDeliveryOwnership.Failed -> {
            warn("Product report ownership could not be established for $reportId.", lock.cause)
            return@useOwnership
          }
          is ProductReportDeliveryOwnership.Held -> Unit
        }
        val terminalIds =
          database.read { unit ->
            if (unit.databaseFileObservation != expectedObservation) return@read emptySet()
            unit.blockIncidentRecoveries.history(attempt.occurrenceToken)
              .filter { it.status in TERMINAL_RECOVERY_STATUSES }.map { it.attemptId }.toSet()
          }
        database.transactionFenced(expectedObservation) { unit ->
          unit.productReports.pendingRecoveryComments(attempt.occurrenceToken, terminalIds, clock.instant())
        }
      }
    }.onFailure { failure ->
      containableUnlessInterrupted(failure)
      warn("Product report recovery eligibility could not be stored for ${attempt.attemptId}.", failure)
    }
    if (!dispatch) return
    catchingNonCancellation { deliver() }.onFailure { failure ->
      containableUnlessInterrupted(failure)
      warn("Product report recovery delivery failed for ${attempt.attemptId}.", failure)
    }
  }

  private fun emitReported(report: ProductReportRecord) {
    report.links.forEach { link ->
      catchingNonCancellation {
        val incident = database.read { unit -> unit.blockIncidents.find(link.occurrenceToken)?.occurrence }
        incident?.let { telemetry.reported(it, report) }
      }.onFailure { failure ->
        containableUnlessInterrupted(failure)
        warn("Product report telemetry lookup failed for ${report.reportId}.", failure)
      }
    }
  }

  private fun reload(
    reportId: String,
    observation: DatabaseFileObservation,
  ): ProductReportRecord? =
    database.read { unit ->
      if (unit.databaseFileObservation != observation) null else unit.productReports.byId(reportId)
    }

  private fun transitionCommitted(
    change: ProductReportTransition,
    observation: DatabaseFileObservation,
  ): ProductReportRecord? =
    (
      database.transactionFenced(observation) { unit ->
        if (unit.productReports.transition(change)) unit.productReports.byId(change.reportId) else null
      } as? FencedTransactionResult.Committed
    )?.value

  private fun ProductReportRecord.issueTitle(): String {
    val code = Regex("(?m)^failure_code: (.+)$").find(body)?.groupValues?.get(1)
    return "Skill Bill: ${code ?: "block incident"}".take(ISSUE_TITLE_LIMIT)
  }

  private fun warn(
    message: String,
    failure: Throwable? = null,
  ) {
    RuntimeDiagnosticsBestEffortWarning.record(diagnostics, message, failure)
  }

  private inline fun ProductReportDeliveryOwnership.useOwnership(block: (ProductReportDeliveryOwnership) -> Unit) {
    try {
      block(this)
    } finally {
      (this as? ProductReportDeliveryOwnership.Held)?.close()
    }
  }

  private fun <T> FencedTransactionResult<T>.committedTrue(): Boolean =
    this is FencedTransactionResult.Committed && value == true

  private data class CommentDispatch(val occurrenceToken: String, val target: ProductReportPendingComment)

  private data class CandidateBatch(val ids: List<String>, val observation: DatabaseFileObservation?)

  private data class ReportReference(val reportId: String?, val observation: DatabaseFileObservation?)

  private data class ReportSnapshot(val report: ProductReportRecord?, val observation: DatabaseFileObservation?)

  private sealed interface CommentPreparation {
    data class Ready(val target: ProductReportPendingComment) : CommentPreparation

    data object Rejected : CommentPreparation

    data object Missing : CommentPreparation
  }

  private enum class CommentAcknowledgement { Posted, Advanced, Rejected, Missing }

  private sealed interface ProductIssueFailure {
    data object AMBIGUOUS : ProductIssueFailure

    data class Failed(val failure: ProductReportFailure, val retryAt: Instant?) : ProductIssueFailure
  }

  private companion object {
    const val ISSUE_TITLE_LIMIT = 200
    const val BATCH_LIMIT = 4
    const val MAX_ATTEMPTS = 3
    val RETRY_DELAYS = listOf(Duration.ofMinutes(1), Duration.ofMinutes(10), Duration.ofHours(1))
    val SUBMISSION_STATUSES =
      setOf(ProductReportStatus.PENDING, ProductReportStatus.AMBIGUOUS, ProductReportStatus.LOCAL_ONLY)
    val DELIVERED_STATUSES = setOf(ProductReportStatus.DELIVERED, ProductReportStatus.LINKED_EXISTING)
    val TERMINAL_RECOVERY_STATUSES =
      setOf(BlockIncidentRecoveryAttemptStatus.FAILED, BlockIncidentRecoveryAttemptStatus.RESUMED)
  }
}
