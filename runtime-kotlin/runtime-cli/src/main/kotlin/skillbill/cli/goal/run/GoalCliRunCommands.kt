package skillbill.cli.goal.run

import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import me.tatarka.inject.annotations.Inject
import skillbill.application.review.service.RuntimeOwnedReviewMode
import skillbill.cli.kernel.agent.invokingAgentResolutionHelp
import skillbill.cli.kernel.agent.requireSupportedOptionalAgentId
import skillbill.cli.kernel.cli.CliRunState
import skillbill.cli.kernel.cli.DocumentedCliCommand
import skillbill.cli.kernel.cli.formatOption
import skillbill.cli.kernel.cli.resolveCliRepositoryRoot
import skillbill.cli.kernel.payload.toFeatureTaskContinuationCliMap
import skillbill.cli.kernel.payload.toGoalContinuationCliMap
import skillbill.cli.model.CliRunInputs
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.review.ReviewFindingPayloadKeys
import skillbill.contracts.review.ReviewVerificationSignalKeys
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeGoalContinuationLaunchTokens
import skillbill.engine.goalrunner.findings.UnaddressedFindingsLedgerService
import skillbill.engine.goalrunner.model.GoalPreflightGateBlock
import skillbill.engine.goalrunner.model.GoalPreflightRequest
import skillbill.engine.goalrunner.model.GoalPreflightResult
import skillbill.engine.goalrunner.planning.GoalPlanningLogService
import skillbill.engine.goalrunner.planning.model.GoalPlanningLog
import skillbill.engine.goalrunner.planning.model.GoalPlanningLogAttempt
import skillbill.engine.goalrunner.planning.model.GoalPlanningLogRequest
import skillbill.engine.goalrunner.preflight.GoalPreflightService
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.GovernedReviewFailureCode
import skillbill.goalrunner.model.UnaddressedFindingsLedger
import skillbill.workflow.model.goalreview.FeatureTaskRuntimeRepairLedger
import skillbill.workflow.taskruntime.artifact.projectionWireMap
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeFindingVerificationDisposition

@Inject
class GoalPreflightCommand(
  private val service: GoalPreflightService,
  private val state: CliRunState,
  private val inputs: CliRunInputs,
) : DocumentedCliCommand(
    "preflight",
    "Show the read-only goal verdict, confirmation gate, and missing spec targets before launch.",
  ) {
  private val issueKey by argument(help = "Parent issue key for the decomposed goal.")
  private val repoRoot by option("--repo-root", help = "Repository root for the goal.")
  private val agent by option(
    "--agent",
    help = invokingAgentResolutionHelp("--agent"),
  )
  private val agentOverride by option(
    "--agent-override",
    help = "Agent to use for child subtask runs instead of the invoking agent.",
  )
  private val codeReviewMode by option(
    FeatureTaskRuntimeGoalContinuationLaunchTokens.CODE_REVIEW_MODE_FLAG,
    help = "Review mode: inline (default) or auto.",
  )
  private val agentAddonSlugs by option(
    "--agent-addon",
    help = "Raw agent add-on slug. Repeat to preserve caller order.",
  ).multiple()
  private val format by formatOption()

  override fun run() {
    val root = resolveCliRepositoryRoot(repoRoot, inputs)
    val invokedAgentId = resolveInvokedAgentId(agent, inputs.environment)
    val agentOverrideId = requireSupportedOptionalAgentId(agentOverride, "--agent-override")
    val result =
      service.preflight(
        GoalPreflightRequest(
          issueKey = issueKey,
          repoRoot = root,
          invokedAgentId = invokedAgentId,
          agentOverrideId = agentOverrideId,
          requestedReviewMode = parseCodeReviewMode(codeReviewMode),
          requestedAgentAddonSlugs = agentAddonSlugs,
          userHome = inputs.userHome,
          environment = inputs.environment,
        ),
      )
    val payload = result.toGoalPreflightCliMap()
    state.complete(payload, format)
  }
}

internal fun parseCodeReviewMode(raw: String?) =
  raw?.let { value ->
    RuntimeOwnedReviewMode.parse(value) ?: throw UsageError(RuntimeOwnedReviewMode.unknownModeMessage(value))
  }

internal fun GoalPreflightResult.toGoalPreflightCliMap(): Map<String, Any?> =
  linkedMapOf(
    SharedPayloadKeys.VERDICT to verdict,
    SharedPayloadKeys.ISSUE_KEY to issueKey,
    "candidate" to candidate?.toFeatureTaskContinuationCliMap(),
    "candidates" to candidates.map { it.toFeatureTaskContinuationCliMap() },
    "goal" to goal?.toGoalContinuationCliMap(),
    "gate_block" to gateBlock?.toGoalPreflightGateBlockMap(),
    "rehydrate_targets" to
      rehydrateTargets.map {
        linkedMapOf(
          SharedPayloadKeys.ISSUE_KEY to it.issueKey,
          "linear_issue_id" to it.linearIssueId,
          "target_path" to it.targetPath,
        )
      },
    "manifest_missing" to manifestMissing,
  )

private fun GoalPreflightGateBlock.toGoalPreflightGateBlockMap(): Map<String, Any?> =
  linkedMapOf(
    SharedPayloadKeys.ISSUE_KEY to issueKey,
    "feature_name" to featureName,
    "subtasks" to
      subtasks.map { subtask ->
        linkedMapOf(
          "id" to subtask.id,
          "name" to subtask.name,
          SharedPayloadKeys.STATUS to subtask.status,
          "dependencies" to
            subtask.dependencies.map { dependency ->
              linkedMapOf(
                SharedPayloadKeys.SUBTASK_ID to dependency.subtaskId,
                "optional" to dependency.optional,
                "skipped" to dependency.skipped,
                "note" to dependency.note,
              )
            },
        )
      },
    "expected_first_runnable_subtask" to expectedFirstRunnableSubtask,
    "child_agent" to childAgent,
    "child_agent_override" to childAgentOverride,
    "review_mode" to reviewMode,
    "agent_addons" to
      agentAddons.map { addon ->
        linkedMapOf("slug" to addon.slug, "description" to addon.description)
      },
  )

@Inject
class GoalPlanningLogCommand(
  private val planningLogService: GoalPlanningLogService,
  private val state: CliRunState,
  private val inputs: CliRunInputs,
) : DocumentedCliCommand(
    "planning-log",
    "Show the durable goal-planning attempt log: start/end times, durations, outcomes, and failure reasons.",
  ) {
  private val issueKey by argument(help = "Parent issue key for the decomposed goal.")
  private val repoRoot by option("--repo-root", help = "Repository root for checked-in manifest recovery.")
  private val subtask by option(
    "--subtask",
    help = "Show only attempts for this subtask id. Use 0 for the shared preplan.",
  ).int()
  private val failuresOnly by option(
    "--failures-only",
    help = "Show only failed attempts.",
  ).flag(default = false)

  override fun run() {
    val log =
      planningLogService.log(
        GoalPlanningLogRequest(
          issueKey = issueKey,
          repoRoot = resolveCliRepositoryRoot(repoRoot, inputs),
          subtaskId = subtask,
          failuresOnly = failuresOnly,
        ),
      )
    val payload =
      linkedMapOf<String, Any?>(
        SharedPayloadKeys.ISSUE_KEY to log.issueKey,
        "parent_workflow_id" to log.parentWorkflowId,
        "total_attempts" to log.totalAttempts,
        "succeeded_attempts" to log.succeededAttempts,
        "failed_attempts" to log.failedAttempts,
        "first_attempt_failures" to log.firstAttemptFailures,
        "phases_observed" to log.phasesObserved,
        "total_planning_ms" to log.totalPlanningMs,
        "attempts" to
          log.attempts.map { attempt ->
            linkedMapOf<String, Any?>(
              SharedPayloadKeys.PHASE_ID to attempt.phaseId,
              SharedPayloadKeys.SUBTASK_ID to attempt.subtaskId,
              "attempt" to attempt.attempt,
              "started_at" to attempt.startedAt?.toString(),
              "finished_at" to attempt.finishedAt?.toString(),
              "duration_ms" to attempt.durationMs,
              "timestamps_inconsistent" to attempt.timestampsInconsistent,
              "outcome" to attempt.outcome,
              "rule" to attempt.rule,
              "reason" to attempt.reason,
              "agent_id" to attempt.agentId,
              "rejected_output_identity" to attempt.rejectedOutputIdentity,
              "rejected_output_bytes" to attempt.rejectedOutputBytes,
            )
          },
      )
    state.completeText(renderPlanningLog(log), payload)
  }
}

internal fun durationField(attempt: GoalPlanningLogAttempt): String =
  when {
    attempt.timestampsInconsistent -> "inconsistent"
    else -> attempt.durationMs?.toString() ?: "none"
  }

internal fun renderPlanningLog(log: GoalPlanningLog): String =
  buildString {
    appendLine("issue_key=${log.issueKey} parent_workflow_id=${log.parentWorkflowId ?: "none"}")
    if (log.parentWorkflowId == null) {
      appendLine("no prepared goal found for this issue key in this repository")
      return@buildString
    }
    appendLine(
      "attempts=${log.totalAttempts} succeeded=${log.succeededAttempts} failed=${log.failedAttempts} " +
        "first_attempt_failures=${log.firstAttemptFailures} phases=${log.phasesObserved} " +
        "total_planning_ms=${log.totalPlanningMs}",
    )
    log.attempts.forEach { attempt ->
      appendLine(
        "phase=${attempt.phaseId} attempt=${attempt.attempt} " +
          "started=${attempt.startedAt ?: "unknown"} finished=${attempt.finishedAt ?: "in_flight"} " +
          "duration_ms=${durationField(attempt)} outcome=${attempt.outcome}",
      )
      attempt.reason?.let { reason ->
        appendLine("  rule=${attempt.rule} agent=${attempt.agentId ?: "unknown"} $reason")
      }
      attempt.rejectedOutputIdentity?.let { identity ->
        appendLine(
          "  rejected_output=$identity bytes=${attempt.rejectedOutputBytes ?: 0} " +
            "(read it with: skill-bill feature-task rejected-output " +
            "--workflow ${log.parentWorkflowId} --phase ${attempt.phaseId} " +
            "--attempt ${attempt.attempt} --raw-output)",
        )
      }
    }
  }

@Inject
class GoalFindingsCommand(
  private val ledgerService: UnaddressedFindingsLedgerService,
  private val state: CliRunState,
) : DocumentedCliCommand("findings", "Show the goal-wide unaddressed-findings ledger.") {
  private val issueKey by option("--issue-key", help = "Parent issue key.").required()

  override fun run() {
    val ledger = ledgerService.ledger(issueKey) ?: throw ledgerAbsent()
    val repairLedgers = ledgerService.repairLedgersByWorkflow(issueKey) ?: throw ledgerAbsent()
    val verificationDispositions = ledgerService.verificationDispositions(issueKey) ?: throw ledgerAbsent()
    state.completeText(
      findingsText(ledger, repairLedgers, verificationDispositions),
      findingsPayload(ledger, repairLedgers, verificationDispositions),
    )
  }

  private fun ledgerAbsent(): SkillBillRuntimeException =
    SkillBillRuntimeException(
      GovernedReviewFailureCode.UNADDRESSED_FINDINGS_LEDGER_ABSENT,
      "No goal exists for issue key '$issueKey'.",
    )

  private fun findingsPayload(
    ledger: UnaddressedFindingsLedger,
    repairLedgers: Map<String, FeatureTaskRuntimeRepairLedger>,
    verificationDispositions: List<FeatureTaskRuntimeFindingVerificationDisposition>,
  ): LinkedHashMap<String, Any?> =
    linkedMapOf(
      SharedPayloadKeys.ISSUE_KEY to ledger.issueKey,
      "unaddressed_findings" to ledger.findings.size,
      "severity_breakdown" to ledger.severityBreakdown,
      ReviewVerificationSignalKeys.REVIEW_FINDINGS to
        ledger.findings.map { finding ->
          linkedMapOf(
            SharedPayloadKeys.SUBTASK_ID to finding.subtaskId,
            SharedPayloadKeys.WORKFLOW_ID to finding.workflowId,
            "review_pass_number" to finding.reviewPassNumber,
            "finding_ordinal" to finding.findingOrdinal,
            "severity" to finding.severity,
            ReviewFindingPayloadKeys.ISSUE_CATEGORY to finding.issueCategory,
            "location" to finding.location,
            SharedPayloadKeys.SUMMARY to finding.summary,
            ReviewFindingPayloadKeys.CLAIM_VERDICT to finding.claimVerdict?.wireValue,
            ReviewFindingPayloadKeys.SCOPE_DISPOSITION to finding.scopeDisposition?.wireValue,
            ReviewFindingPayloadKeys.CITATIONS to
              finding.citations.map { citation ->
                linkedMapOf("path" to citation.path, "line" to citation.line)
              },
            ReviewFindingPayloadKeys.SEVERITY_ADJUSTMENT to
              finding.severityAdjustment?.let { adjustment ->
                linkedMapOf(
                  "direction" to adjustment.direction.wireValue,
                  "justification" to adjustment.justification,
                )
              },
            "verification_disposition" to finding.verificationDisposition,
            "verification_reason" to finding.verificationReason,
          )
        },
      "repair_ledger" to
        repairLedgers.map { (workflowId, repairLedger) ->
          linkedMapOf<String, Any?>(
            SharedPayloadKeys.WORKFLOW_ID to workflowId,
            "entries" to repairLedger.entries.map { it.projectionWireMap() },
          )
        },
      "finding_verification_dispositions" to
        verificationDispositions.map { disposition ->
          linkedMapOf(
            ReviewFindingPayloadKeys.FINDING_ID to disposition.findingId,
            "disposition" to disposition.disposition.wireValue,
            "reason" to disposition.reason,
            "boundary_context_unavailable" to disposition.boundaryContextUnavailable,
            "selected_boundary_headings" to
              disposition.selectedBoundaryHeadings.map { heading ->
                linkedMapOf(
                  "heading_id" to heading.headingId,
                  "source_path" to heading.sourcePath,
                )
              },
          )
        },
    )

  private fun findingsText(
    ledger: UnaddressedFindingsLedger,
    repairLedgers: Map<String, FeatureTaskRuntimeRepairLedger>,
    verificationDispositions: List<FeatureTaskRuntimeFindingVerificationDisposition>,
  ): String =
    buildString {
      appendLine("issue_key=${ledger.issueKey} unaddressed_findings=${ledger.findings.size}")
      ledger.findings.forEach { finding ->
        appendLine(
          "subtask=${finding.subtaskId} pass=${finding.reviewPassNumber} " +
            "severity=${finding.severity} category=${finding.issueCategory} " +
            "location=${finding.location} ${finding.summary}" +
            finding.claimVerdict?.let { " claim_verdict=${it.wireValue}" }.orEmpty() +
            finding.scopeDisposition?.let { " scope_disposition=${it.wireValue}" }.orEmpty() +
            finding.verificationDisposition?.let { " verification_disposition=$it" }.orEmpty() +
            finding.verificationReason?.let { " verification_reason=$it" }.orEmpty(),
        )
      }
      repairLedgers.forEach { (workflowId, repairLedger) ->
        repairLedger.entries.forEach { entry ->
          appendLine(
            "repair workflow=$workflowId finding=${entry.disturbanceRef} status=${entry.status.wireValue} " +
              "severity=${entry.severity} round=${entry.originRound} status_round=${entry.statusRound} " +
              "constructs=${entry.constructs.joinToString(",") { it.symbol }} ${entry.intent}",
          )
        }
      }
      verificationDispositions.forEach { disposition ->
        appendLine(
          "verification finding=${disposition.findingId} disposition=${disposition.disposition.wireValue} " +
            "boundary_context_unavailable=${disposition.boundaryContextUnavailable} " +
            "selected_boundary_headings=${
              disposition.selectedBoundaryHeadings.joinToString(";") { "${it.headingId}@${it.sourcePath}" }
            }",
        )
      }
    }
}
