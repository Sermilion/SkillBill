package skillbill.engine.featuretask.phase.planning

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_PLAN_BUNDLE_FEASIBILITY_CONTRACT_VERSION
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.featuretask.PlanBundleFeasibilityFailureCode
import skillbill.ports.taskruntime.FeatureTaskRuntimeWireArtifactValidator
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.artifact.decodePhaseRecordFromArtifact
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWireArtifactKind
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.planning.FeatureTaskRuntimePlanBundleFeasibilityState
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityAcceptedLine
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityApproval
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityApprovalCandidate
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityConflict
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityCycleHistory
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityDependency
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityDependencyHash
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityIdentity
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityLaunch
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityLaunchKind
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityLaunchOutcome
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityMode
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityReopening
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityRepositoryBaseline
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilitySettledLaunch
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityStateValidation
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilitySubtaskIdentity
import skillbill.contracts.workflow.featuretask.FeatureTaskRuntimePlanBundleFeasibilityPayloadKeys as K

@Inject
public class PlanBundleFeasibilityStateCodec(private val validator: FeatureTaskRuntimeWireArtifactValidator) {
  public fun encode(state: FeatureTaskRuntimePlanBundleFeasibilityState): FeatureTaskRuntimeWorkflowArtifactMap {
    requireValid(state)
    val wire = FeatureTaskRuntimeWorkflowArtifactMap.from(state.toWire())
    validator.validate(FeatureTaskRuntimeWireArtifactKind.PLAN_BUNDLE_FEASIBILITY, wire, LABEL)
    return wire
  }

  public fun decode(
    raw: Any?,
    sourceLabel: String,
  ): FeatureTaskRuntimePlanBundleFeasibilityState {
    if (raw !is Map<*, *> || raw.keys.any { it !is String }) {
      invalid(sourceLabel, "artifact must be an object with string keys")
    }
    val map = FeatureTaskRuntimeWorkflowArtifactMap.from(raw)
    val version = map[K.CONTRACT_VERSION] as? String
    if (version != FEATURE_TASK_RUNTIME_PLAN_BUNDLE_FEASIBILITY_CONTRACT_VERSION) {
      throw SkillBillRuntimeException(
        PlanBundleFeasibilityFailureCode.UNSUPPORTED_VERSION,
        "$sourceLabel has unsupported contract version '$version'.",
      )
    }
    validator.validate(FeatureTaskRuntimeWireArtifactKind.PLAN_BUNDLE_FEASIBILITY, map, sourceLabel)
    val state = Decoder(sourceLabel).read(map)
    requireValid(state, sourceLabel)
    return state
  }

  private fun requireValid(
    state: FeatureTaskRuntimePlanBundleFeasibilityState,
    source: String = LABEL,
  ) {
    when (val validation = state.validation()) {
      PlanBundleFeasibilityStateValidation.Valid -> Unit
      is PlanBundleFeasibilityStateValidation.Invalid -> invalid(source, validation.reason)
    }
  }

  private fun invalid(
    source: String,
    reason: String,
  ): Nothing =
    throw SkillBillRuntimeException(
      PlanBundleFeasibilityFailureCode.INVALID_STATE,
      "Invalid plan bundle feasibility '$source': $reason. Preserve the original artifact and recover with a " +
        "compatible runtime or restore the source workflow record.",
    )

  private fun FeatureTaskRuntimePlanBundleFeasibilityState.toWire(): Map<String, Any?> =
    mapOf(
      K.CONTRACT_VERSION to FEATURE_TASK_RUNTIME_PLAN_BUNDLE_FEASIBILITY_CONTRACT_VERSION,
      K.REVISION to revision, K.MODE to mode.wireValue, K.IDENTITY to identity.wire(),
      K.ACCEPTED_LINES to acceptedLines.map { it.wire() },
      K.CONFLICTS to conflicts.map { mapOf(K.REF to it.ref, K.LINE to it.line) },
      K.CHANGED_SUBTASK_IDS to changedSubtaskIds, K.REQUIRED_REFS to requiredRefs, K.CARRIED_REFS to carriedRefs,
      K.REVIEW_ROUNDS_STARTED to reviewRoundsStarted, K.REPAIR_LAUNCHES_STARTED to repairLaunchesStarted,
      K.ACTIVE_LAUNCH to activeLaunch?.wire(), K.SETTLED_LAUNCHES to settledLaunches.map { it.wire() },
      K.LAST_REVIEW_VALUE to lastReviewValue,
      K.APPROVAL to
        approval?.let {
          mapOf(
            K.IMMUTABLE_FINGERPRINT to it.immutableFingerprint,
            K.CRITERION_LINES to it.criterionLines,
            K.TRANSITION_ID to it.transitionId,
          )
        },
      K.LEGACY_ORIGIN to legacyOrigin,
      K.APPROVAL_CANDIDATE to
        approvalCandidate?.let { candidate ->
          mapOf(
            K.SOURCE_REVISION to candidate.sourceRevision,
            K.IMMUTABLE_FINGERPRINT to candidate.immutableFingerprint,
            K.TRANSITION_ID to candidate.transitionId,
            K.PHASE_ID to candidate.phaseId,
            K.ATTEMPT to candidate.attempt,
            K.REVIEWER_AGENT_ID to candidate.reviewerAgentId,
            K.NORMALIZED_ENVELOPE to candidate.normalizedEnvelope,
            K.FILE_MANIFEST_BEFORE to candidate.fileManifestBefore,
            K.FILE_MANIFEST_AFTER to candidate.fileManifestAfter,
            K.FILE_MANIFEST_INTRODUCED to candidate.fileManifestIntroduced,
          )
        },
      K.CURRENT_CYCLE_ID to currentCycleId,
      K.CYCLE_HISTORY to
        cycleHistory.map { cycle ->
          mapOf(
            K.CYCLE_ID to cycle.cycleId,
            K.IDENTITY to cycle.identity.wire(),
            K.ACCEPTED_LINES to cycle.acceptedLines.map { it.wire() },
            K.CONFLICTS to cycle.conflicts.map { mapOf(K.REF to it.ref, K.LINE to it.line) },
            K.REVIEW_ROUNDS_STARTED to cycle.reviewRoundsStarted,
            K.REPAIR_LAUNCHES_STARTED to cycle.repairLaunchesStarted,
            K.SETTLED_LAUNCHES to cycle.settledLaunches.map { it.wire() },
            K.LAST_REVIEW_VALUE to cycle.lastReviewValue,
            K.APPROVAL to
              cycle.approval?.let { approval ->
                mapOf(
                  K.IMMUTABLE_FINGERPRINT to approval.immutableFingerprint,
                  K.CRITERION_LINES to approval.criterionLines,
                  K.TRANSITION_ID to approval.transitionId,
                )
              },
          )
        },
      K.REOPENINGS to
        reopenings.map { reopening ->
          mapOf(
            K.SOURCE_ATTEMPT to reopening.sourceAttempt,
            K.SOURCE_REVISION to reopening.sourceRevision,
            K.RETRY_LEDGER_SEQUENCE to reopening.retryLedgerSequence,
            K.REASON to reopening.reason,
            K.ORIGINAL_PHASE_RECORD to reopening.originalPhaseRecord.asWorkflowArtifactEntry(),
          )
        },
    )

  private fun PlanBundleFeasibilityIdentity.wire(): Map<String, Any?> =
    mapOf(
      K.PARENT_SHA256 to parentSha256,
      K.MANIFEST_SHA256 to manifestSha256,
      K.IMMUTABLE_MANIFEST_SHA256 to immutableManifestSha256,
      K.RAW_PARENT_TEXT to rawParentText,
      K.RAW_MANIFEST_TEXT to rawManifestText,
      K.SUBTASKS to
        subtasks.map { subtask ->
          mapOf(
            K.ID to subtask.id,
            K.SPEC_PATH to subtask.specPath,
            K.SPEC_SHA256 to subtask.specSha256,
            K.RAW_SPEC_TEXT to subtask.rawSpecText,
            K.CRITERION_REFS to subtask.criterionRefs,
            K.DEPENDENCIES to
              subtask.dependencies.map { dependency ->
                mapOf(
                  K.SUBTASK_ID to dependency.subtaskId,
                  K.OPTIONAL to dependency.optional,
                  K.SKIPPED to dependency.skipped,
                )
              },
          )
        },
    )

  private fun PlanBundleFeasibilityLaunch.wire(): Map<String, Any?> =
    mapOf(
      K.TRANSITION_ID to transitionId,
      K.PHASE_ID to phaseId,
      K.ATTEMPT to attempt,
      K.KIND to kind.wireValue,
      K.BASELINE_IDENTITY to baselineIdentity.wire(),
      K.REPAIR_DIRECTORY to repairDirectory,
      K.INTENDED_AFTER_IDENTITY to intendedAfterIdentity?.wire(),
      K.CYCLE_ID to cycleId,
      K.SEQUENCE to sequence,
      K.INTENDED_REPAIR_VALUE to intendedRepairValue,
      K.REPOSITORY_BASELINE to
        repositoryBaseline?.let {
          mapOf(
            K.HEAD_COMMIT to it.headCommit,
            K.DIRTY_PATHS to it.dirtyPaths,
            K.CONTENT_IDENTITIES to it.contentIdentities,
          )
        },
      K.REPAIR_BEFORE_IDENTITY to repairBeforeIdentity?.wire(),
    )

  private fun PlanBundleFeasibilitySettledLaunch.wire(): Map<String, Any?> =
    PlanBundleFeasibilityLaunch(
      transitionId,
      phaseId,
      attempt,
      kind,
      baselineIdentity,
      repairDirectory,
      intendedAfterIdentity,
      cycleId,
      sequence,
      null,
      repositoryBaseline,
      repairBeforeIdentity,
    ).wire() +
      mapOf(K.OUTCOME to outcome.wireValue, K.VALUE to value)

  private fun PlanBundleFeasibilityAcceptedLine.wire(): Map<String, Any?> =
    mapOf(
      K.REF to ref,
      K.LINE to line,
      K.JUDGED_PARENT_SHA256 to judgedParentSha256,
      K.JUDGED_MANIFEST_SHA256 to judgedManifestSha256,
      K.JUDGED_SPEC_SHA256 to judgedSpecSha256,
      K.DEPENDENCY_HASHES to
        dependencyHashes.map { dependency ->
          mapOf(K.SUBTASK_ID to dependency.subtaskId, K.SPEC_SHA256 to dependency.specSha256)
        },
    )

  private inner class Decoder(private val source: String) {
    public fun read(map: FeatureTaskRuntimeWorkflowArtifactMap): FeatureTaskRuntimePlanBundleFeasibilityState =
      FeatureTaskRuntimePlanBundleFeasibilityState(
        map.int(K.REVISION),
        map.choice(K.MODE, PlanBundleFeasibilityMode.entries) { it.wireValue },
        obj(map[K.IDENTITY]).identity(),
        map.objects(K.ACCEPTED_LINES).map { it.acceptedLine() },
        map.objects(K.CONFLICTS).map { conflict ->
          PlanBundleFeasibilityConflict(conflict.text(K.REF), conflict.text(K.LINE))
        },
        map.ints(K.CHANGED_SUBTASK_IDS),
        map.strings(K.REQUIRED_REFS),
        map.strings(K.CARRIED_REFS),
        map.int(K.REVIEW_ROUNDS_STARTED),
        map.int(K.REPAIR_LAUNCHES_STARTED),
        map.optionalObject(K.ACTIVE_LAUNCH)?.launch(),
        map.objects(K.SETTLED_LAUNCHES).map { it.settled() },
        map[K.LAST_REVIEW_VALUE] as? String,
        map.optionalObject(K.APPROVAL)?.let { approval ->
          PlanBundleFeasibilityApproval(
            approval.text(K.IMMUTABLE_FINGERPRINT),
            approval.strings(K.CRITERION_LINES),
            approval.text(K.TRANSITION_ID),
          )
        },
        map[K.LEGACY_ORIGIN] as? Boolean ?: fail(K.LEGACY_ORIGIN),
        approvalCandidate =
          map.optionalObject(K.APPROVAL_CANDIDATE)?.approvalCandidate(),
        currentCycleId = map[K.CURRENT_CYCLE_ID] as? String,
        cycleHistory =
          map.objectsOrEmpty(K.CYCLE_HISTORY).map { cycle ->
            PlanBundleFeasibilityCycleHistory(
              cycle.text(K.CYCLE_ID),
              obj(cycle[K.IDENTITY]).identity(),
              cycle.objects(K.ACCEPTED_LINES).map { it.acceptedLine() },
              cycle.objects(K.CONFLICTS).map { PlanBundleFeasibilityConflict(it.text(K.REF), it.text(K.LINE)) },
              cycle.int(K.REVIEW_ROUNDS_STARTED),
              cycle.int(K.REPAIR_LAUNCHES_STARTED),
              cycle.objects(K.SETTLED_LAUNCHES).map { it.settled() },
              cycle[K.LAST_REVIEW_VALUE] as? String,
              cycle.optionalObject(K.APPROVAL)?.let { approval ->
                PlanBundleFeasibilityApproval(
                  approval.text(K.IMMUTABLE_FINGERPRINT),
                  approval.strings(K.CRITERION_LINES),
                  approval.text(K.TRANSITION_ID),
                )
              },
            )
          },
        reopenings =
          map.objectsOrEmpty(K.REOPENINGS).map { reopening ->
            PlanBundleFeasibilityReopening(
              reopening.int(K.SOURCE_ATTEMPT),
              reopening[K.SOURCE_REVISION] as? Int,
              reopening.int(K.RETRY_LEDGER_SEQUENCE),
              reopening.text(K.REASON),
              decodePhaseRecordFromArtifact(reopening[K.ORIGINAL_PHASE_RECORD])
                ?: fail(K.ORIGINAL_PHASE_RECORD),
            )
          },
      )

    private fun Map<String, Any?>.approvalCandidate() =
      PlanBundleFeasibilityApprovalCandidate(
        int(K.SOURCE_REVISION),
        text(K.IMMUTABLE_FINGERPRINT),
        text(K.TRANSITION_ID),
        text(K.PHASE_ID),
        int(K.ATTEMPT),
        text(K.REVIEWER_AGENT_ID),
        text(K.NORMALIZED_ENVELOPE),
        strings(K.FILE_MANIFEST_BEFORE),
        strings(K.FILE_MANIFEST_AFTER),
        strings(K.FILE_MANIFEST_INTRODUCED),
      )

    private fun Map<String, Any?>.acceptedLine() =
      PlanBundleFeasibilityAcceptedLine(
        text(K.REF),
        text(K.LINE),
        text(K.JUDGED_PARENT_SHA256),
        text(K.JUDGED_MANIFEST_SHA256),
        text(K.JUDGED_SPEC_SHA256),
        objects(K.DEPENDENCY_HASHES).map {
          PlanBundleFeasibilityDependencyHash(it.int(K.SUBTASK_ID), it.text(K.SPEC_SHA256))
        },
      )

    private fun Map<String, Any?>.identity(): PlanBundleFeasibilityIdentity =
      PlanBundleFeasibilityIdentity(
        text(K.PARENT_SHA256),
        text(K.MANIFEST_SHA256),
        text(K.IMMUTABLE_MANIFEST_SHA256),
        objects(K.SUBTASKS).map { subtask ->
          PlanBundleFeasibilitySubtaskIdentity(
            subtask.int(K.ID),
            subtask.text(K.SPEC_PATH),
            subtask.text(K.SPEC_SHA256),
            subtask.strings(K.CRITERION_REFS),
            subtask.objects(K.DEPENDENCIES).map { dependency ->
              PlanBundleFeasibilityDependency(
                dependency.int(K.SUBTASK_ID),
                dependency.bool(K.OPTIONAL),
                dependency.bool(K.SKIPPED),
              )
            },
            subtask[K.RAW_SPEC_TEXT] as? String,
          )
        },
        this[K.RAW_PARENT_TEXT] as? String,
        this[K.RAW_MANIFEST_TEXT] as? String,
      )

    private fun Map<String, Any?>.launch(): PlanBundleFeasibilityLaunch =
      PlanBundleFeasibilityLaunch(
        text(K.TRANSITION_ID),
        text(K.PHASE_ID),
        int(K.ATTEMPT),
        choice(K.KIND, PlanBundleFeasibilityLaunchKind.entries) { it.wireValue },
        obj(this[K.BASELINE_IDENTITY]).identity(),
        this[K.REPAIR_DIRECTORY] as? String,
        this[K.INTENDED_AFTER_IDENTITY]?.let(::obj)?.identity(),
        this[K.CYCLE_ID]?.let { it as? String ?: fail(K.CYCLE_ID) },
        this[K.SEQUENCE]?.let { exactInt(it) ?: fail(K.SEQUENCE) } ?: 0,
        this[K.INTENDED_REPAIR_VALUE]?.let { it as? String ?: fail(K.INTENDED_REPAIR_VALUE) },
        this[K.REPOSITORY_BASELINE]?.let { value ->
          val baseline = obj(value)
          PlanBundleFeasibilityRepositoryBaseline(
            baseline.text(K.HEAD_COMMIT),
            baseline.strings(K.DIRTY_PATHS),
            baseline.stringMap(K.CONTENT_IDENTITIES),
          )
        },
        this[K.REPAIR_BEFORE_IDENTITY]?.let { value -> obj(value).identity() },
      )

    private fun Map<String, Any?>.settled(): PlanBundleFeasibilitySettledLaunch {
      val launch = launch()
      return PlanBundleFeasibilitySettledLaunch(
        launch.transitionId,
        launch.phaseId,
        launch.attempt,
        launch.kind,
        launch.baselineIdentity,
        launch.repairDirectory,
        launch.intendedAfterIdentity,
        choice(K.OUTCOME, PlanBundleFeasibilityLaunchOutcome.entries) { it.wireValue },
        text(K.VALUE),
        launch.cycleId,
        launch.sequence,
        launch.repositoryBaseline,
        launch.repairBeforeIdentity,
      )
    }

    private fun Map<String, Any?>.optionalObject(key: String) = this[key]?.let(::obj)

    private fun Map<String, Any?>.ints(key: String) =
      (this[key] as? List<*>)?.map { exactInt(it) ?: fail(key) } ?: fail(key)

    private fun Map<String, Any?>.text(key: String) = this[key] as? String ?: fail(key)

    private fun Map<String, Any?>.int(key: String) = exactInt(this[key]) ?: fail(key)

    private fun Map<String, Any?>.bool(key: String) = this[key] as? Boolean ?: fail(key)

    private fun Map<String, Any?>.strings(key: String) =
      (this[key] as? List<*>)?.map { it as? String ?: fail(key) } ?: fail(key)

    private fun Map<String, Any?>.stringMap(key: String): Map<String, String> =
      (this[key] as? Map<*, *>)?.entries?.associate { (entryKey, value) ->
        val name = entryKey as? String ?: fail(key)
        name to (value as? String ?: fail(key))
      } ?: fail(key)

    private fun Map<String, Any?>.objects(key: String) = (this[key] as? List<*>)?.map(::obj) ?: fail(key)

    private fun Map<String, Any?>.objectsOrEmpty(key: String) = if (containsKey(key)) objects(key) else emptyList()

    private fun <T> Map<String, Any?>.choice(
      key: String,
      entries: Collection<T>,
      wire: (T) -> String,
    ): T {
      val token = text(key)
      return entries.firstOrNull { wire(it) == token } ?: fail(key)
    }

    private fun obj(value: Any?): Map<String, Any?> =
      (value as? Map<*, *>)?.entries?.associate { (key, item) ->
        (key as? String ?: fail("object key")) to item
      } ?: fail("object")

    private fun exactInt(value: Any?): Int? =
      when (value) {
        is Int -> value
        is Long -> value.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
        is Short -> value.toInt()
        is Byte -> value.toInt()
        else -> null
      }

    private fun fail(key: String): Nothing = invalid(source, "malformed field: $key")
  }

  private companion object {
    const val LABEL = "plan_bundle_feasibility"
  }
}
