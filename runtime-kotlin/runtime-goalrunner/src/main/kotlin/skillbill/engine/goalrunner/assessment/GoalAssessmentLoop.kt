package skillbill.engine.goalrunner.assessment

import skillbill.engine.goal.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalAssessmentRecordRead
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.goalrunner.assessment.GoalAssessmentLoopRules
import skillbill.goalrunner.assessment.model.GoalAssessmentExitReason
import skillbill.goalrunner.assessment.model.GoalAssessmentFixStatus
import skillbill.goalrunner.assessment.model.GoalAssessmentFixValidation
import skillbill.goalrunner.assessment.model.GoalAssessmentRecord
import skillbill.goalrunner.assessment.model.GoalAssessmentRound
import skillbill.workflow.model.repair.FocusedRepairAction

internal class GoalAssessmentLoop(
  private val services: GoalAssessmentRunServices,
  private val fixStep: GoalAssessmentFixStep,
  private val assessRound: (GoalRunnerManifestState, GoalRunnerRunRequest, Int) -> GoalAssessmentRoundResult,
) {
  fun run(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
  ): GoalAssessmentRecord {
    val record =
      stored(state) ?: return GoalAssessmentRecord(issueKey = state.manifest.issueKey, exitReason = UNAVAILABLE)
    return if (record.exitReason != null) record else drive(state, request, record)
  }

  private fun drive(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    stored: GoalAssessmentRecord,
  ): GoalAssessmentRecord {
    var record = stored
    var roundNumber = record.rounds.size.coerceAtLeast(1)
    while (true) {
      when (val step = advance(state, request, record, roundNumber)) {
        is LoopStep.Continue -> {
          record = step.record
          roundNumber = step.nextRound
        }
        is LoopStep.Exit -> return exit(state, step.record, step.reason)
      }
    }
  }

  private fun advance(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    record: GoalAssessmentRecord,
    roundNumber: Int,
  ): LoopStep {
    val existing = record.rounds.getOrNull(roundNumber - 1)
    if (existing != null) existingFixStep(state, request, record, roundNumber, existing)?.let { return it }
    existing?.targetRef?.let { return repair(state, request, record, roundNumber, it) }
    val assessed =
      when (val result = assessRound(state, request, roundNumber)) {
        is GoalAssessmentRoundResult.Unavailable -> return LoopStep.Exit(
          result.record.takeIf { it.rounds.size >= record.rounds.size } ?: record,
          UNAVAILABLE,
        )
        is GoalAssessmentRoundResult.Assessed -> result.record
      }
    return when (val action = GoalAssessmentLoopRules.next(assessed)) {
      FocusedRepairAction.Accepted -> LoopStep.Exit(assessed, GoalAssessmentExitReason.CONVERGED)
      is FocusedRepairAction.Blocked -> LoopStep.Exit(assessed, GoalAssessmentExitReason.PERSISTENT_FINDING)
      is FocusedRepairAction.Plan -> repair(state, request, assessed, roundNumber, action.targetRef)
      else -> error("Assessment repair must plan, block or accept after verification.")
    }
  }

  private fun existingFixStep(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    record: GoalAssessmentRecord,
    roundNumber: Int,
    existing: GoalAssessmentRound,
  ): LoopStep? {
    val existingFix = existing.fix ?: return null
    return when (existingFix.status) {
      GoalAssessmentFixStatus.FAILED, GoalAssessmentFixStatus.NOT_RUN -> LoopStep.Exit(record, UNAVAILABLE)
      GoalAssessmentFixStatus.NO_CHANGE ->
        if (existingFix.validation == GoalAssessmentFixValidation.NOT_RUN &&
          existingFix.commitSha == null && existingFix.changedFiles.isEmpty()
        ) {
          LoopStep.Continue(record, roundNumber + 1)
        } else {
          LoopStep.Exit(record, UNAVAILABLE)
        }
      GoalAssessmentFixStatus.COMPLETED ->
        if (existingFix.validation == GoalAssessmentFixValidation.FAILED ||
          existingFix.validation == GoalAssessmentFixValidation.NOT_RUN && existingFix.commitSha != null
        ) {
          LoopStep.Exit(record, UNAVAILABLE)
        } else {
          repair(state, request, record, roundNumber, requireNotNull(existing.targetRef))
        }
    }
  }

  private fun repair(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    record: GoalAssessmentRecord,
    roundNumber: Int,
    targetRef: String,
  ): LoopStep {
    val rounds = record.rounds.map { if (it.round == roundNumber) it.copy(targetRef = targetRef) else it }
    val selected = record.copy(rounds = rounds)
    if (!services.persist(state, selected)) return LoopStep.Exit(selected, UNAVAILABLE)
    val repaired = fixStep.run(state, request, selected, roundNumber, fixSet(targetRef))
    if (repaired.exitReason == UNAVAILABLE) return LoopStep.Exit(repaired, UNAVAILABLE)
    val fix = repaired.rounds.last().fix
    return when {
      fix == null || fix.status == GoalAssessmentFixStatus.FAILED -> LoopStep.Exit(repaired, UNAVAILABLE)
      fix.status == GoalAssessmentFixStatus.NO_CHANGE &&
        fix.validation == GoalAssessmentFixValidation.NOT_RUN &&
        fix.commitSha == null && fix.changedFiles.isEmpty() ->
        LoopStep.Continue(repaired, roundNumber + 1)
      fix.status == GoalAssessmentFixStatus.COMPLETED &&
        fix.validation == GoalAssessmentFixValidation.PASSED && fix.commitSha != null ->
        LoopStep.Continue(repaired, roundNumber + 1)
      else -> LoopStep.Exit(repaired, UNAVAILABLE)
    }
  }

  private sealed interface LoopStep {
    data class Continue(val record: GoalAssessmentRecord, val nextRound: Int) : LoopStep

    data class Exit(val record: GoalAssessmentRecord, val reason: GoalAssessmentExitReason) : LoopStep
  }

  private fun fixSet(ref: String): GoalAssessmentFixSet =
    GoalAssessmentFixSet(
      findingRefs = listOf(ref).filter { it.startsWith("GA-") },
      regressionRefs = listOf(ref).filter { it.startsWith("TR-") },
      scenarioRefs = listOf(ref).filter { it.startsWith("IS-") },
    )

  fun skipNoChanges(state: GoalRunnerManifestState) {
    val record = stored(state) ?: return
    if (record.exitReason != null) return
    services.warn("seam=goal_assessment value_expected=assessment value_used=skipped reason=no_changes")
    services.persist(state, record.copy(exitReason = GoalAssessmentExitReason.SKIPPED_NO_CHANGES))
  }

  private fun stored(state: GoalRunnerManifestState): GoalAssessmentRecord? =
    when (val read = services.outcomeStore.prepareGoalAssessmentRecord(state.parentWorkflowId)) {
      GoalAssessmentRecordRead.Absent -> GoalAssessmentRecord(issueKey = state.manifest.issueKey)
      is GoalAssessmentRecordRead.Recorded -> read.record
      is GoalAssessmentRecordRead.Corrupt -> {
        services.warn("assessment cannot accept the goal because its stored record is unreadable: ${read.reason}")
        null
      }
    }

  private fun exit(
    state: GoalRunnerManifestState,
    record: GoalAssessmentRecord,
    reason: GoalAssessmentExitReason,
  ): GoalAssessmentRecord {
    val exited =
      record.copy(
        exitReason = reason,
        openFindingRefs = GoalAssessmentLoopRules.openFindings(record).map { it.findingRef },
      )
    return if (services.persist(state, exited)) exited else exited.copy(exitReason = UNAVAILABLE)
  }

  private companion object {
    val UNAVAILABLE = GoalAssessmentExitReason.ASSESSMENT_UNAVAILABLE
  }
}
