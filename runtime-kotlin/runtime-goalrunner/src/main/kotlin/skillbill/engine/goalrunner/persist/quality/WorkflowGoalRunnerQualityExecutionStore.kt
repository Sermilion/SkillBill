package skillbill.engine.goalrunner.persist.quality

import me.tatarka.inject.annotations.Inject
import skillbill.application.workflow.decomposition.findCompletedPlanWorkflowIdForProjectSpec
import skillbill.contracts.JsonCodec
import skillbill.contracts.workflow.goal.GOAL_FINALIZATION_HISTORY_CONTRACT_VERSION
import skillbill.contracts.workflow.goal.GOAL_QUALITY_EXECUTION_CONTRACT_VERSION
import skillbill.contracts.workflow.goal.GoalQualityExecutionPayloadKeys
import skillbill.engine.featuretask.lifecycle.assignment.WorkflowAgentAssignmentInspection
import skillbill.engine.featuretask.lifecycle.assignment.WorkflowAgentAssignmentStore
import skillbill.engine.goalrunner.history.GoalChildHistoryEvidenceStore
import skillbill.engine.goalrunner.model.history.GoalFinalizationHistoryPayloadKeys
import skillbill.engine.goalrunner.persist.GoalRunnerQualityExecutionStore
import skillbill.engine.goalrunner.persist.saveRunnerArtifactPatch
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.WorkflowFailureCode
import skillbill.goalrunner.model.quality.GoalQualityAdoptionEvidence
import skillbill.goalrunner.model.quality.GoalQualityExecutionAdmission
import skillbill.goalrunner.model.quality.GoalQualityExecutionInputs
import skillbill.goalrunner.model.quality.GoalQualityExecutionPlans
import skillbill.goalrunner.model.quality.GoalQualityExecutionRecord
import skillbill.goalrunner.model.quality.GoalQualityExecutionStatus
import skillbill.goalrunner.model.quality.GoalQualityPhaseProgress
import skillbill.goalrunner.model.quality.GoalQualityPreQaBuildState
import skillbill.goalrunner.model.quality.GoalQualityReadiness
import skillbill.goalrunner.model.quality.GoalQualityReadiness.CLEAN_UNSTARTED
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.process.IdentifierGeneratorPort
import skillbill.ports.taskruntime.FeatureTaskRuntimeWireArtifactValidator
import skillbill.ports.workflow.WorkflowSnapshotValidator
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.model.toSnapshot
import skillbill.workflow.decomposition.runtime.decompositionRuntime
import skillbill.workflow.decomposition.runtime.importedGoalQualityReadiness
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.decompositionStatus
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWireArtifactKind
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import java.io.IOException
import java.security.MessageDigest
import java.sql.SQLException
import kotlin.coroutines.cancellation.CancellationException

public class WorkflowGoalRunnerQualityExecutionStore
  @Inject
  public constructor(
    private val database: DatabaseSessionFactory,
    private val snapshotValidator: WorkflowSnapshotValidator,
    private val artifactValidator: FeatureTaskRuntimeWireArtifactValidator,
    private val identifiers: IdentifierGeneratorPort,
    private val historyEvidence: GoalChildHistoryEvidenceStore,
    private val workflowAgentAssignments: WorkflowAgentAssignmentStore,
  ) : GoalRunnerQualityExecutionStore {
    private val engine = WorkflowEngine()

    override fun goalQualityExecution(parentWorkflowId: String): GoalQualityExecutionRecord? =
      withQualityPersistence {
        database.read { unitOfWork ->
          val parent = runtimeParentOrNull(unitOfWork, parentWorkflowId) ?: return@read null
          val family = DurableWorkflowArtifactFamily.GOAL_QUALITY_EXECUTION
          if (!family.contains(parent.artifacts)) {
            null
          } else {
            decode(family.value(parent.artifacts)).also { validateParentIdentity(it, parentWorkflowId) }
          }
        }
      }

    override fun retainedGoalQualityExecution(parentWorkflowId: String): GoalQualityExecutionRead? =
      withQualityPersistence {
        database.read { unitOfWork ->
          val parent = runtimeParentOrNull(unitOfWork, parentWorkflowId) ?: return@read null
          val family = DurableWorkflowArtifactFamily.GOAL_QUALITY_EXECUTION
          if (!family.contains(parent.artifacts)) {
            null
          } else {
            readRetainedForObservation(family.value(parent.artifacts), parentWorkflowId)
          }
        }
      }

    override fun prepareGoalQualityExecution(parentWorkflowId: String): GoalQualityExecutionRead? =
      withQualityPersistence {
        try {
          val needsMigration =
            database.read { unitOfWork ->
              val parent = runtimeParentOrNull(unitOfWork, parentWorkflowId) ?: return@read null
              val payload =
                DurableWorkflowArtifactFamily.GOAL_QUALITY_EXECUTION.value(parent.artifacts) ?: return@read false
              val source = JsonCodec.anyToStringAnyMap(payload) ?: return@read false
              source[GoalQualityExecutionPayloadKeys.CONTRACT_VERSION] == "0.7"
            }
          if (needsMigration == null) return@withQualityPersistence null
          if (!needsMigration) return@withQualityPersistence retainedGoalQualityExecution(parentWorkflowId)
          database.transaction { unitOfWork ->
            val parent = parent(unitOfWork, parentWorkflowId)
            val family = DurableWorkflowArtifactFamily.GOAL_QUALITY_EXECUTION
            val payload = family.value(parent.artifacts) ?: return@transaction null
            val source =
              JsonCodec.anyToStringAnyMap(payload)
                ?: invalid("Stored goal quality execution is not an object.")
            validate(source)
            val sourceVersion = source[GoalQualityExecutionPayloadKeys.CONTRACT_VERSION] as String
            if (sourceVersion != "0.7") {
              return@transaction readRetained(payload).also { retained ->
                if (retained is GoalQualityExecutionRead.Current) {
                  validateParentIdentity(retained.record, parentWorkflowId)
                }
              }
            }
            val converted = migrateGoalQualityExecutionRouting07(source, parentWorkflowId)
            validateCurrentRecord(converted)
            persist(unitOfWork, parent, converted)
            GoalQualityExecutionRead.Current(converted)
          }
        } catch (failure: SkillBillRuntimeException) {
          if (failure.code != WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION) throw failure
          retainedGoalQualityExecution(parentWorkflowId)
        }
      }

    override fun chooseFreshGoalQualityExecution(parentWorkflowId: String): Boolean =
      withQualityPersistence {
        database.transaction { unitOfWork ->
          val parent = prepareQualityInUnitOfWork(unitOfWork, parentWorkflowId)
          val payload =
            DurableWorkflowArtifactFamily.GOAL_QUALITY_EXECUTION.value(parent.artifacts)
              ?: return@transaction false
          when (val retained = readRetained(payload)) {
            is GoalQualityExecutionRead.Current -> {
              val current = retained.record
              validateParentIdentity(current, parentWorkflowId)
              when {
                current.readiness == GoalQualityReadiness.HISTORICAL_AMBIGUITY -> {
                  persist(unitOfWork, parent, current.copy(readiness = CLEAN_UNSTARTED, execution = null))
                  true
                }
                current.execution?.status?.isUnsettled() == true -> retireActiveExecution(unitOfWork, parentWorkflowId)
                else -> false
              }
            }
            is GoalQualityExecutionRead.Ambiguous -> {
              val current =
                GoalQualityExecutionRecord(
                  readiness = GoalQualityReadiness.HISTORICAL_AMBIGUITY,
                  adoptionEvidence =
                    GoalQualityAdoptionEvidence(
                      retained.sourceContractVersion,
                      JsonCodec.mapToJsonString(retained.originalPayload),
                    ),
                )
              persist(unitOfWork, parent, current.copy(readiness = CLEAN_UNSTARTED))
              true
            }
            is GoalQualityExecutionRead.Invalid ->
              invalid("Stored goal quality execution is invalid and cannot be reset.")
            is GoalQualityExecutionRead.Historical ->
              if (retained.status != GoalQualityExecutionStatus.RETIRED && retained.invocationId != null) {
                retireActiveExecution(unitOfWork, parentWorkflowId)
              } else {
                false
              }
          }
        }
      }

    override fun settleGoalQualityReadinessFromCompletedPlan(parentWorkflowId: String): Boolean =
      withQualityPersistence {
        database.transaction { unitOfWork ->
          val parent = prepareQualityInUnitOfWork(unitOfWork, parentWorkflowId)
          val payload =
            DurableWorkflowArtifactFamily.GOAL_QUALITY_EXECUTION.value(parent.artifacts)
              ?: return@transaction false
          val retained = readRetained(payload)
          val current =
            when (retained) {
              is GoalQualityExecutionRead.Current -> retained.record
              is GoalQualityExecutionRead.Ambiguous ->
                GoalQualityExecutionRecord(
                  readiness = GoalQualityReadiness.HISTORICAL_AMBIGUITY,
                  adoptionEvidence =
                    GoalQualityAdoptionEvidence(
                      retained.sourceContractVersion,
                      JsonCodec.mapToJsonString(retained.originalPayload),
                    ),
                )
              is GoalQualityExecutionRead.Historical -> {
                if (retained.hasAuthoritativePlansAndProgress ||
                  retained.status == GoalQualityExecutionStatus.RETIRED
                ) {
                  return@transaction false
                }
                GoalQualityExecutionRecord(
                  readiness = GoalQualityReadiness.HISTORICAL_AMBIGUITY,
                  adoptionEvidence =
                    GoalQualityAdoptionEvidence(
                      retained.sourceContractVersion,
                      JsonCodec.mapToJsonString(retained.originalPayload),
                    ),
                )
              }
              is GoalQualityExecutionRead.Invalid ->
                invalid("Stored goal quality execution is invalid and cannot be settled from inferred evidence.")
            }
          validateParentIdentity(current, parentWorkflowId)
          val manifest = parent.decompositionRuntime() ?: return@transaction false
          val settled =
            current.readiness == GoalQualityReadiness.HISTORICAL_AMBIGUITY &&
              importedGoalQualityReadiness(
                manifest,
                unitOfWork.workflowStates.findCompletedPlanWorkflowIdForProjectSpec(manifest),
              ) == CLEAN_UNSTARTED
          if (settled) persist(unitOfWork, parent, current.copy(readiness = CLEAN_UNSTARTED, execution = null))
          settled
        }
      }

    override fun resolvedGoalQualityChildIds(
      parentWorkflowId: String,
      expectedSelection: List<Pair<Int, String?>>,
    ): List<String> =
      withQualityPersistence {
        database.read { unitOfWork ->
          val parent = parent(unitOfWork, parentWorkflowId)
          val manifest = parent.decompositionRuntime() ?: invalid("Parent has no durable goal manifest.")
          if (manifest.subtasks.map { it.id to it.workflowId } != expectedSelection) {
            invalid("The goal child selection changed while final quality was being admitted.")
          }
          manifest.subtasks.mapNotNull { subtask ->
            subtask.workflowId ?: historyEvidence.restoredChildId(unitOfWork, parentWorkflowId, subtask.id)
          }
        }
      }

    override fun admitGoalQualityExecution(
      parentWorkflowId: String,
      admission: GoalQualityAdmissionRequest,
    ): GoalQualityExecutionRecord =
      withQualityPersistence {
        database.transaction { unitOfWork ->
          admitRecordedQuality(unitOfWork, parentWorkflowId, admission)
        }
      }

    override fun goalQualityPhaseProgress(
      parentWorkflowId: String,
      invocationId: String,
      definitionId: String,
      acceptedPlan: String,
    ): GoalQualityPhaseProgress =
      withQualityPersistence {
        database.read { unitOfWork ->
          val parent = parent(unitOfWork, parentWorkflowId)
          val record = decode(DurableWorkflowArtifactFamily.GOAL_QUALITY_EXECUTION.value(parent.artifacts))
          val execution = record.execution ?: invalid("Admitted quality execution is absent.")
          val plans = execution.executionPlans ?: invalid("Admitted quality execution plans are absent.")
          if (execution.invocationId != invocationId || !ownsProgress(execution, definitionId)) {
            invalid("The goal-quality phase belongs to a retired or different invocation.")
          }
          val selected = selectedPlan(plans, definitionId)
          if (selected != acceptedPlan) invalid("The goal-quality phase plan does not match its admitted descriptor.")
          progressFor(requireNotNull(execution.progress), definitionId)
        }
      }

    override fun persistGoalQualityPhaseProgress(write: GoalQualityPhaseProgressWrite): Boolean =
      withQualityPersistence {
        val parentWorkflowId = write.parentWorkflowId
        val definitionId = write.definitionId
        val progress = write.progress
        val finalValidationContentFingerprint = write.finalValidationContentFingerprint

        validatePhaseProgress(progress)
        database.transaction { unitOfWork ->
          val parent = prepareQualityInUnitOfWork(unitOfWork, parentWorkflowId)
          val family = DurableWorkflowArtifactFamily.GOAL_QUALITY_EXECUTION
          val payload = family.value(parent.artifacts) ?: return@transaction false
          val current = decode(payload)
          val execution = current.execution ?: return@transaction false
          val plans = execution.executionPlans ?: return@transaction false
          if (!allowsQualityProgressWrite(execution, write, selectedPlan(plans, definitionId))) {
            return@transaction false
          }
          val existing = progressFor(requireNotNull(execution.progress), definitionId)
          if (!validProgressSourceWrite(
              write,
              execution,
            ) || !validQaSourceWitnessWrite(write, execution)
          ) {
            return@transaction false
          }
          if (existing == progress) return@transaction matchesExistingProgressSource(write, execution)
          if (existing.terminalCompleted || existing.terminalBlockedStep != null) return@transaction false
          val updatedProgress = nextPhaseProgress(existing, progress) ?: return@transaction false
          val updated =
            updateProgress(
              current,
              definitionId,
              updatedProgress,
              finalValidationContentFingerprint,
              write.preQaBuildContentFingerprint,
            )
          persist(unitOfWork, parent, updated)
          true
        }
      }

    override fun reenterGoalQualityBuild(proof: GoalQualityBuildSourceProof): GoalQualityExecutionRecord? =
      withQualityPersistence {
        database.transaction { unitOfWork ->
          val parent = prepareQualityInUnitOfWork(unitOfWork, proof.parentWorkflowId)
          val payload =
            DurableWorkflowArtifactFamily.GOAL_QUALITY_EXECUTION.value(parent.artifacts)
              ?: return@transaction null
          val current = decode(payload)
          val execution = current.execution ?: return@transaction null
          if (!acceptsInitialBuildReentry(execution, proof)) return@transaction null
          val next = current.startNewPreQaBuildOccurrence()
          persist(unitOfWork, parent, next)
          next
        }
      }

    internal fun retireActiveExecution(
      unitOfWork: UnitOfWork,
      parentWorkflowId: String,
    ): Boolean =
      withQualityPersistence {
        val parent = prepareQualityInUnitOfWork(unitOfWork, parentWorkflowId)
        val family = DurableWorkflowArtifactFamily.GOAL_QUALITY_EXECUTION
        val payload = family.value(parent.artifacts) ?: return@withQualityPersistence false
        val (current, historicalRetired) =
          retirableQualityRecord(readRetained(payload), parentWorkflowId)
            ?: return@withQualityPersistence false
        val retired = current.retire()
        if (retired == current && !historicalRetired) return@withQualityPersistence false
        persist(unitOfWork, parent, if (historicalRetired) current else retired)
        resetGoalFinalizationHistory(unitOfWork, parentWorkflowId)
        true
      }

    private fun resetGoalFinalizationHistory(
      unitOfWork: UnitOfWork,
      parentWorkflowId: String,
    ) {
      val refreshedParent = parent(unitOfWork, parentWorkflowId)
      val existingHistory = DurableWorkflowArtifactFamily.GOAL_FINALIZATION_HISTORY.value(refreshedParent.artifacts)
      if (existingHistory != null) {
        val source =
          JsonCodec.anyToStringAnyMap(existingHistory)
            ?: invalidFinalHistory("Finalization history is not an object.")
        val upgraded = GoalFinalizationHistoryMigration.upgrade(source)
        validateFinalizationHistory(upgraded)
        if (upgraded != source) {
          engine.saveRunnerArtifactPatch(
            unitOfWork.workflowStates,
            WorkflowFamily.TASK_RUNTIME,
            refreshedParent,
            mapOf(DurableWorkflowArtifactFamily.GOAL_FINALIZATION_HISTORY.entry(upgraded)),
          )
        }
      }
      val finalHistory =
        mapOf(
          GoalFinalizationHistoryPayloadKeys.CONTRACT_VERSION to GOAL_FINALIZATION_HISTORY_CONTRACT_VERSION,
          GoalFinalizationHistoryPayloadKeys.REPAIR_CONTEXT to null,
          GoalFinalizationHistoryPayloadKeys.COMPLETION to null,
        )
      validateFinalizationHistory(finalHistory)
      engine.saveRunnerArtifactPatch(
        unitOfWork.workflowStates,
        WorkflowFamily.TASK_RUNTIME,
        refreshedParent,
        mapOf(DurableWorkflowArtifactFamily.GOAL_FINALIZATION_HISTORY.entry(finalHistory)),
      )
    }

    override fun transitionGoalQualityExecution(
      parentWorkflowId: String,
      expectedInvocationId: String,
      expected: GoalQualityExecutionRecord,
      next: GoalQualityExecutionRecord,
    ): Boolean =
      withQualityPersistence {
        database.transaction { unitOfWork ->
          val parent = prepareQualityInUnitOfWork(unitOfWork, parentWorkflowId)
          val family = DurableWorkflowArtifactFamily.GOAL_QUALITY_EXECUTION
          val payload = family.value(parent.artifacts) ?: invalid("Admitted quality execution is absent.")
          val current = decode(payload)
          validateParentIdentity(current, parentWorkflowId)
          val execution = current.execution ?: invalid("Admitted quality execution is absent.")
          if (execution.invocationId == expectedInvocationId && next == current) return@transaction true
          if (execution.invocationId != expectedInvocationId || !sameOuterSnapshot(current, expected)) {
            return@transaction false
          }
          val nextExecution = next.execution ?: return@transaction false
          val adoptedExecution = nextExecution.copy(progress = execution.progress)
          val nextWithLatestProgress = next.copy(execution = adoptedExecution)
          val buildTransition = validBuildTransition(execution, adoptedExecution)
          val sameRecordEnvelope =
            next.readiness == GoalQualityReadiness.ADMITTED && next.contractVersion == current.contractVersion &&
              next.adoptionEvidence == current.adoptionEvidence &&
              next.migrationEvidence == current.migrationEvidence
          val settledBuild =
            execution.preQaBuild in setOf(GoalQualityPreQaBuildState.PASSED, GoalQualityPreQaBuildState.WARNED)
          if (blocksQualityTransition(
              buildTransition,
              validTransition(current, nextWithLatestProgress),
              sameRecordEnvelope,
              settledBuild,
            )
          ) {
            return@transaction false
          }
          val terminal =
            nextWithLatestProgress.execution?.status in
              setOf(
                GoalQualityExecutionStatus.SETTLED_PASSED,
                GoalQualityExecutionStatus.SETTLED_SKIPPED,
                GoalQualityExecutionStatus.SETTLED_WARNED,
              )
          if (terminal && !hasFinalRepairContext(unitOfWork, parent)) return@transaction false
          persist(unitOfWork, parent(unitOfWork, parentWorkflowId), nextWithLatestProgress)
          true
        }
      }

    private fun parent(
      unitOfWork: UnitOfWork,
      parentWorkflowId: String,
    ): WorkflowStateSnapshot {
      val snapshot =
        unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, parentWorkflowId)
          ?: invalid("Parent workflow '$parentWorkflowId' is missing.")
      snapshotValidator.validate(snapshot, WorkflowFamily.TASK_RUNTIME.definition.workflowName)
      return snapshot
    }

    private fun runtimeParentOrNull(
      unitOfWork: UnitOfWork,
      parentWorkflowId: String,
    ): WorkflowStateSnapshot? {
      val row = unitOfWork.workflowStates.getFeatureTaskWorkflow(parentWorkflowId) ?: return null
      if ((row.mode ?: FeatureTaskWorkflowMode.PROSE) != FeatureTaskWorkflowMode.RUNTIME) return null
      val snapshot = row.toSnapshot()
      snapshotValidator.validate(snapshot, WorkflowFamily.TASK_RUNTIME.definition.workflowName)
      return snapshot
    }

    private fun prepareQualityInUnitOfWork(
      unitOfWork: UnitOfWork,
      parentWorkflowId: String,
    ): WorkflowStateSnapshot {
      val snapshot = parent(unitOfWork, parentWorkflowId)
      val family = DurableWorkflowArtifactFamily.GOAL_QUALITY_EXECUTION
      val payload = family.value(snapshot.artifacts) ?: return snapshot
      val source = JsonCodec.anyToStringAnyMap(payload) ?: invalid("Stored goal quality execution is not an object.")
      if (source[GoalQualityExecutionPayloadKeys.CONTRACT_VERSION] != "0.7") return snapshot
      validate(source)
      val converted = migrateGoalQualityExecutionRouting07(source, parentWorkflowId)
      validateCurrentRecord(converted)
      persist(unitOfWork, snapshot, converted)
      return parent(unitOfWork, parentWorkflowId)
    }

    private fun selectionFingerprint(
      unitOfWork: UnitOfWork,
      parent: WorkflowStateSnapshot,
    ): String {
      val manifest = parent.decompositionRuntime() ?: invalid("Parent has no durable goal manifest.")
      val retainedEvidence = historyEvidence.read(unitOfWork, parent.workflowId)
      val identity =
        buildList {
          add(parent.workflowId)
          manifest.subtasks.forEach { subtask ->
            add(subtask.id.toString())
            val childId =
              subtask.workflowId ?: historyEvidence.restoredChildId(unitOfWork, parent.workflowId, subtask.id)
            if (childId == null && subtask.status.decompositionStatus() == DecompositionStatus.SKIPPED) {
              add(DecompositionStatus.SKIPPED.wireValue)
              return@forEach
            }
            val selectedChildId = childId ?: invalid("Selected subtask '${subtask.id}' has no recorded child identity.")
            add(selectedChildId)
            val child = unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, selectedChildId)
            val currentPlan =
              child?.let {
                JsonCodec.anyToStringAnyMap(
                  DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.value(it.artifacts),
                )
              }
            val retained =
              retainedEvidence.singleOrNull {
                it.subtaskId == subtask.id && it.childWorkflowId == selectedChildId
              }
            val plan = currentPlan ?: retained?.executionPlan
            val definition = historyEvidence.definition(plan)
            val retainedDefinition = retained?.executionPlan?.let(historyEvidence::definition)
            if (currentPlan != null && retainedDefinition != null && retainedDefinition != definition) {
              invalid("Selected child '$selectedChildId' conflicts with its preserved definition.")
            }
            add(definition.id)
            add(definition.semanticRevision.toString())
          }
        }.joinToString("\u0000")
      return MessageDigest.getInstance("SHA-256")
        .digest(identity.toByteArray())
        .joinToString("") { "%02x".format(it) }
    }

    private fun readRetained(value: Any?): GoalQualityExecutionRead {
      val map = JsonCodec.anyToStringAnyMap(value) ?: invalid("Stored goal quality execution is not an object.")
      validate(map)
      val sourceVersion = map[GoalQualityExecutionPayloadKeys.CONTRACT_VERSION] as String
      if (sourceVersion == GOAL_QUALITY_EXECUTION_CONTRACT_VERSION) {
        val record = GoalQualityExecutionRecord.fromPersistenceWire(map)
        validateCurrentRecord(record)
        return GoalQualityExecutionRead.Current(record)
      }
      if (sourceVersion == "0.7") {
        val execution = JsonCodec.anyToStringAnyMap(map[GoalQualityExecutionPayloadKeys.EXECUTION])
        val recordedParentId =
          execution?.get(GoalQualityExecutionPayloadKeys.PARENT_WORKFLOW_ID) as? String
            ?: invalid("Stored 0.7 quality execution has no parent identity.")
        validateCurrentRecord(migrateGoalQualityExecutionRouting07(map, recordedParentId))
        return GoalQualityExecutionRead.Ambiguous(
          sourceVersion,
          map.toMap(),
          execution[GoalQualityExecutionPayloadKeys.INVOCATION_ID] as? String,
          execution[GoalQualityExecutionPayloadKeys.STATUS] == GoalQualityExecutionStatus.RETIRED.wireValue,
        )
      }
      if (sourceVersion !in setOf("0.1", "0.2", "0.3", "0.4", "0.5", "0.6")) {
        invalid("Stored goal quality execution uses unsupported contract version '$sourceVersion'.")
      }
      return decodeLegacyQualityRead(map, sourceVersion)
    }

    private fun readRetainedForObservation(
      value: Any?,
      parentWorkflowId: String,
    ): GoalQualityExecutionRead =
      try {
        readRetained(value).also { retained ->
          when (retained) {
            is GoalQualityExecutionRead.Current -> validateParentIdentity(retained.record, parentWorkflowId)
            is GoalQualityExecutionRead.Historical ->
              if (retained.parentWorkflowId != null && retained.parentWorkflowId != parentWorkflowId) {
                invalid(
                  "Stored goal quality execution belongs to '${retained.parentWorkflowId}', " +
                    "not '$parentWorkflowId'.",
                )
              }
            is GoalQualityExecutionRead.Ambiguous, is GoalQualityExecutionRead.Invalid -> Unit
          }
        }
      } catch (failure: SkillBillRuntimeException) {
        if (failure.code != WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION) throw failure
        val map = JsonCodec.anyToStringAnyMap(value)
        GoalQualityExecutionRead.Invalid(
          map?.get(GoalQualityExecutionPayloadKeys.CONTRACT_VERSION) as? String,
          value,
          failure.code,
        )
      }

    private fun validateCurrentRecord(record: GoalQualityExecutionRecord) {
      if ((record.readiness == GoalQualityReadiness.ADMITTED) != (record.execution != null) ||
        record.readiness == GoalQualityReadiness.HISTORICAL_AMBIGUITY && record.execution != null
      ) {
        invalid("Stored goal quality readiness and admitted execution disagree.")
      }
      validateAdoptionArchive(record)
      validateMigrationArchive(record)
      record.execution?.let { execution ->
        validateExecutionInputs(execution)
        validateExecutionIdentity(execution)
        validateExecutionDisposition(execution, currentContract = true)
      }
    }

    private fun adoptHistoricalPayload(
      retained: GoalQualityExecutionRead.Historical,
      executionPlans: GoalQualityExecutionPlans,
    ): Map<String, Any?> {
      val keys = GoalQualityExecutionPayloadKeys
      val payload = retained.originalPayload.toMutableMap()
      val execution =
        JsonCodec.anyToStringAnyMap(payload[keys.EXECUTION])
          ?: invalid("Historical quality execution payload has no execution object.")
      val plans =
        mapOf(
          keys.BUILD_PLAN to executionPlans.build,
          keys.QA_PLAN to executionPlans.qa,
          keys.VALIDATION_PLAN to executionPlans.validation,
          keys.PLAN_FINGERPRINT to executionPlans.fingerprint,
        )
      val oldProgress =
        JsonCodec.anyToStringAnyMap(execution[keys.PROGRESS])
          ?: invalid("Historical quality execution has no authoritative phase progress.")
      val progress = oldProgress + (keys.BUILD_PROGRESS to GoalQualityPhaseProgress().toPersistenceWire())
      val governingInputs =
        retained.governingInputs
          ?: invalid("Historical quality execution has no retained governing inputs.")
      val upgradedExecution =
        execution +
          mapOf(
            keys.EXECUTION_PLANS to plans,
            keys.PROGRESS to progress,
            keys.GOVERNING_INPUTS to governingInputs.toPersistenceWire(),
            keys.GOVERNING_INPUT_FINGERPRINT to governingInputFingerprint(governingInputs),
            keys.FINAL_VALIDATION_CONTENT_FINGERPRINT to
              execution[keys.FINAL_VALIDATION_CONTENT_FINGERPRINT] as? String,
            keys.PRE_QA_BUILD to GoalQualityPreQaBuildState.PENDING.wireValue,
            keys.PRE_QA_BUILD_CONTENT_FINGERPRINT to null,
          )
      val adoption =
        payload[keys.ADOPTION_EVIDENCE] as? Map<*, *>
          ?: mapOf(
            keys.SOURCE_CONTRACT_VERSION to retained.sourceContractVersion,
            keys.ORIGINAL_PAYLOAD_JSON to JsonCodec.mapToJsonString(retained.originalPayload),
          )
      val adopted =
        payload +
          mapOf(
            keys.CONTRACT_VERSION to GOAL_QUALITY_EXECUTION_CONTRACT_VERSION,
            keys.EXECUTION to upgradedExecution,
            keys.ADOPTION_EVIDENCE to adoption,
          )
      validate(adopted)
      return adopted
    }

    private fun decode(value: Any?): GoalQualityExecutionRecord {
      val retained = readRetained(value)
      if (retained is GoalQualityExecutionRead.Current) return retained.record
      invalid("A supported historical quality execution must be read through its retained projection before use.")
    }

    private fun validateAdoptionArchive(record: GoalQualityExecutionRecord) {
      record.adoptionEvidence?.let { evidence ->
        val originalObject =
          JsonCodec.parseObjectOrNull(evidence.originalPayloadJson)
            ?: invalid("Stored goal quality adoption evidence is not valid JSON.")
        val original =
          JsonCodec.anyToStringAnyMap(JsonCodec.jsonElementToValue(originalObject))
            ?: invalid("Stored goal quality adoption evidence is not an object.")
        if (original[GoalQualityExecutionPayloadKeys.CONTRACT_VERSION] != evidence.sourceContractVersion ||
          original.containsKey(GoalQualityExecutionPayloadKeys.ADOPTION_EVIDENCE)
        ) {
          invalid("Stored goal quality adoption evidence has a conflicting source version or nested archive.")
        }
        validate(original)
      }
    }

    private fun validateMigrationArchive(record: GoalQualityExecutionRecord) {
      record.migrationEvidence?.let { evidence ->
        val originalObject =
          JsonCodec.parseObjectOrNull(evidence.originalPayloadJson)
            ?: invalid("Stored goal quality migration evidence is not valid JSON.")
        val original =
          JsonCodec.anyToStringAnyMap(JsonCodec.jsonElementToValue(originalObject))
            ?: invalid("Stored goal quality migration evidence is not an object.")
        if (evidence.sourceContractVersion != "0.7" ||
          original[GoalQualityExecutionPayloadKeys.CONTRACT_VERSION] != evidence.sourceContractVersion
        ) {
          invalid("Stored goal quality migration evidence has a conflicting source version.")
        }
        validate(original)
      }
    }

    private fun ensureNewInvocationRepairContext(
      unitOfWork: UnitOfWork,
      parent: WorkflowStateSnapshot,
    ) {
      val family = DurableWorkflowArtifactFamily.GOAL_FINALIZATION_HISTORY
      val existing = family.value(parent.artifacts)
      val source =
        existing?.let {
          JsonCodec.anyToStringAnyMap(it)
            ?: throw SkillBillRuntimeException(
              WorkflowFailureCode.INVALID_GOAL_FINALIZATION_HISTORY,
              "Stored finalization history is not an object.",
            )
        }
          ?: mapOf(
            GoalFinalizationHistoryPayloadKeys.CONTRACT_VERSION to GOAL_FINALIZATION_HISTORY_CONTRACT_VERSION,
            GoalFinalizationHistoryPayloadKeys.REPAIR_CONTEXT to null,
            GoalFinalizationHistoryPayloadKeys.COMPLETION to null,
          )
      val payload = GoalFinalizationHistoryMigration.upgrade(source)
      if (existing != null) {
        validateFinalizationHistory(payload)
      }
      val context = payload[GoalFinalizationHistoryPayloadKeys.REPAIR_CONTEXT]
      val initialized =
        if (context != null) {
          payload
        } else {
          payload +
            mapOf(
              GoalFinalizationHistoryPayloadKeys.REPAIR_CONTEXT to
                mapOf(
                  GoalFinalizationHistoryPayloadKeys.OUTPUTS to emptyList<Any>(),
                  GoalFinalizationHistoryPayloadKeys.WARNING to null,
                ),
              GoalFinalizationHistoryPayloadKeys.COMPLETION to null,
            )
        }
      validateFinalizationHistory(initialized)
      if (initialized == source) return
      engine.saveRunnerArtifactPatch(
        unitOfWork.workflowStates,
        WorkflowFamily.TASK_RUNTIME,
        parent,
        mapOf(family.entry(initialized)),
      )
    }

    private fun hasFinalRepairContext(
      unitOfWork: UnitOfWork,
      parent: WorkflowStateSnapshot,
    ): Boolean {
      val payload = DurableWorkflowArtifactFamily.GOAL_FINALIZATION_HISTORY.value(parent.artifacts) ?: return false
      val map = JsonCodec.anyToStringAnyMap(payload) ?: invalid("Finalization history is not an object.")
      val upgraded = GoalFinalizationHistoryMigration.upgrade(map)
      validateFinalizationHistory(upgraded)
      if (upgraded != map) {
        engine.saveRunnerArtifactPatch(
          unitOfWork.workflowStates,
          WorkflowFamily.TASK_RUNTIME,
          parent,
          mapOf(DurableWorkflowArtifactFamily.GOAL_FINALIZATION_HISTORY.entry(upgraded)),
        )
      }
      return upgraded[GoalFinalizationHistoryPayloadKeys.REPAIR_CONTEXT] != null
    }

    private fun validateFinalizationHistory(payload: Map<String, Any?>) {
      artifactValidator.validate(
        FeatureTaskRuntimeWireArtifactKind.GOAL_FINALIZATION_HISTORY,
        FeatureTaskRuntimeWorkflowArtifactMap.from(payload),
        DurableWorkflowArtifactFamily.GOAL_FINALIZATION_HISTORY.label(),
      )
    }

    private fun invalidFinalHistory(reason: String): Nothing =
      throw SkillBillRuntimeException(WorkflowFailureCode.INVALID_GOAL_FINALIZATION_HISTORY, reason)

    private fun validate(payload: Map<String, Any?>) {
      artifactValidator.validate(
        FeatureTaskRuntimeWireArtifactKind.GOAL_QUALITY_EXECUTION,
        FeatureTaskRuntimeWorkflowArtifactMap.from(payload),
        DurableWorkflowArtifactFamily.GOAL_QUALITY_EXECUTION.label(),
      )
    }

    private fun <T> withQualityPersistence(block: () -> T): T =
      try {
        block()
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (failure: SkillBillRuntimeException) {
        throw failure
      } catch (failure: IOException) {
        throw SkillBillRuntimeException(
          WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION,
          "Required goal quality execution persistence operation failed.",
          failure,
        )
      } catch (failure: SQLException) {
        throw SkillBillRuntimeException(
          WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION,
          "Required goal quality execution persistence operation failed.",
          failure,
        )
      }

    private fun admitRecordedQuality(
      unitOfWork: UnitOfWork,
      parentWorkflowId: String,
      admission: GoalQualityAdmissionRequest,
    ): GoalQualityExecutionRecord {
      val parent = prepareQualityInUnitOfWork(unitOfWork, parentWorkflowId)
      val storedPayload = DurableWorkflowArtifactFamily.GOAL_QUALITY_EXECUTION.value(parent.artifacts)
      val retained = storedPayload?.let(::readRetained)
      if (admission.expectedRetainedRead != null && retained != admission.expectedRetainedRead) {
        invalid("Stored goal quality evidence changed before transactional adoption; reload the retained execution.")
      }
      validateRoutingAdmission(unitOfWork, parentWorkflowId, retained, admission.governingInputs)
      val adopted = retainedQualityRecord(unitOfWork, parent, parentWorkflowId, retained, admission)
      return finishAdmittedQuality(unitOfWork, parent, retained, adopted, admission)
    }

    private fun validateRoutingAdmission(
      unitOfWork: UnitOfWork,
      parentWorkflowId: String,
      retained: GoalQualityExecutionRead?,
      inputs: GoalQualityExecutionInputs,
    ) {
      val fresh =
        retained == null ||
          (
            retained is GoalQualityExecutionRead.Current &&
              retained.record.readiness == GoalQualityReadiness.CLEAN_UNSTARTED && retained.record.execution == null
          )
      val workflowId = inputs.agentAssignmentsWorkflowId
      val fingerprint = inputs.agentAssignmentsFingerprint
      val suppliedReference = workflowId != null || fingerprint != null
      if (suppliedReference && (workflowId == null || fingerprint == null)) {
        invalid(
          "Goal-quality routing reference is incomplete; preserve the parent and repair the reference " +
            "before retrying.",
        )
      }
      when (val inspection = workflowAgentAssignments.inspect(unitOfWork, parentWorkflowId)) {
        is WorkflowAgentAssignmentInspection.Present ->
          validatePresentRoutingAdmission(inspection, fresh, suppliedReference, workflowId, fingerprint)
        WorkflowAgentAssignmentInspection.Legacy ->
          if (suppliedReference) {
            invalid(
              "Goal-quality routing reference has no accepted parent artifact; preserve the parent and " +
                "repair the evidence.",
            )
          }
        is WorkflowAgentAssignmentInspection.Problem ->
          invalid("Parent routing cannot be verified: ${inspection.recoveryMessage}")
      }
      if (retained is GoalQualityExecutionRead.Historical && suppliedReference) {
        invalid("Historical goal-quality execution must retain its recorded paired-null routing reference.")
      }
    }

    private fun retainedQualityRecord(
      unitOfWork: UnitOfWork,
      parent: WorkflowStateSnapshot,
      parentWorkflowId: String,
      retained: GoalQualityExecutionRead?,
      admission: GoalQualityAdmissionRequest,
    ): AdoptedQuality =
      when (retained) {
        null -> AdoptedQuality(GoalQualityExecutionRecord(readiness = CLEAN_UNSTARTED))
        is GoalQualityExecutionRead.Current -> AdoptedQuality(retained.record)
        is GoalQualityExecutionRead.Ambiguous ->
          AdoptedQuality(
            GoalQualityExecutionRecord(
              readiness = GoalQualityReadiness.HISTORICAL_AMBIGUITY,
              adoptionEvidence =
                GoalQualityAdoptionEvidence(
                  retained.sourceContractVersion,
                  JsonCodec.mapToJsonString(retained.originalPayload),
                ),
            ),
          )
        is GoalQualityExecutionRead.Historical ->
          adoptHistoricalQuality(unitOfWork, parent, parentWorkflowId, retained, admission)
        is GoalQualityExecutionRead.Invalid ->
          invalid("Stored goal quality execution is invalid and cannot be adopted.")
      }

    private fun adoptHistoricalQuality(
      unitOfWork: UnitOfWork,
      parent: WorkflowStateSnapshot,
      parentWorkflowId: String,
      retained: GoalQualityExecutionRead.Historical,
      admission: GoalQualityAdmissionRequest,
    ): AdoptedQuality {
      if (retained.status != null && retained.status != GoalQualityExecutionStatus.RETIRED &&
        !retained.hasAuthoritativePlansAndProgress
      ) {
        invalid("Historical quality execution lacks authoritative plans or progress and remains ambiguous.")
      }
      if (retained.status == GoalQualityExecutionStatus.RETIRED || retained.invocationId == null) {
        return AdoptedQuality(
          GoalQualityExecutionRecord(
            readiness = CLEAN_UNSTARTED,
            adoptionEvidence =
              GoalQualityAdoptionEvidence(
                retained.sourceContractVersion,
                JsonCodec.mapToJsonString(retained.originalPayload),
              ),
          ),
        )
      }
      requireHistoricalAdmissionMatches(
        retained,
        parentWorkflowId,
        selectionFingerprint(unitOfWork, parent),
        admission.governingInputs,
        admission.executionPlans,
      )
      val projected =
        GoalQualityExecutionRecord.fromPersistenceWire(adoptHistoricalPayload(retained, admission.executionPlans))
      val proofMatches =
        matchesRetainedProof(admission.validationSourceProof, retained.sourceContractVersion, projected)
      val adopted =
        adoptLegacyExecution(
          projected,
          retained.sourceContractVersion,
          retained.originalPayload,
          admission.validationSourceProof,
          proofMatches,
        )
      persist(unitOfWork, parent, adopted)
      val sourceCurrent = proofMatches && admission.validationSourceProof?.sourceIsCurrent == true
      return AdoptedQuality(adopted, proofMatches, sourceCurrent)
    }

    private fun finishAdmittedQuality(
      unitOfWork: UnitOfWork,
      parent: WorkflowStateSnapshot,
      retained: GoalQualityExecutionRead?,
      adopted: AdoptedQuality,
      admission: GoalQualityAdmissionRequest,
    ): GoalQualityExecutionRecord {
      var record = adopted.record
      var proofMatchesStored = adopted.proofMatchesStored
      var sourceProofCurrent = adopted.sourceProofCurrent
      validateParentIdentity(record, parent.workflowId)
      if (record.readiness == GoalQualityReadiness.HISTORICAL_AMBIGUITY) {
        invalid(
          "This parent has ambiguous historical quality state. An operator must " +
            "decide whether to start a fresh quality execution " +
            "(sona goal fresh-quality <issue-key> --reason \"<why>\") or provide " +
            "authoritative original evidence.",
        )
      }
      if (retained is GoalQualityExecutionRead.Current) {
        proofMatchesStored =
          matchesRetainedProof(admission.validationSourceProof, retained.record.contractVersion, record)
        sourceProofCurrent = proofMatchesStored && admission.validationSourceProof?.sourceIsCurrent == true
      }
      validateRetainedAdmission(
        record,
        selectionFingerprint(unitOfWork, parent),
        admission.governingInputs,
        admission.executionPlans,
      )
      val admitted =
        record.admit(
          GoalQualityExecutionAdmission(
            parent.workflowId,
            "phr-${identifiers.randomToken()}",
            admission.baselineContentFingerprint,
            selectionFingerprint(unitOfWork, parent),
            governingInputFingerprint(admission.governingInputs),
            admission.governingInputs,
            admission.executionPlans,
          ),
        )
      val reconciled = reconciledAdmission(admitted, proofMatchesStored, sourceProofCurrent)
      if (reconciled != record) persist(unitOfWork, parent, reconciled)
      if (admitted.execution?.invocationId != record.execution?.invocationId) {
        ensureNewInvocationRepairContext(unitOfWork, parent(unitOfWork, parent.workflowId))
      }
      return reconciled
    }

    private fun persist(
      unitOfWork: UnitOfWork,
      parent: WorkflowStateSnapshot,
      record: GoalQualityExecutionRecord,
    ) {
      val payload = record.toPersistenceWire()
      validate(payload)
      engine.saveRunnerArtifactPatch(
        unitOfWork.workflowStates,
        WorkflowFamily.TASK_RUNTIME,
        parent,
        mapOf(DurableWorkflowArtifactFamily.GOAL_QUALITY_EXECUTION.entry(payload)),
      )
    }
  }

internal fun allowedNextStatuses(status: GoalQualityExecutionStatus): Set<GoalQualityExecutionStatus> =
  when (status) {
    GoalQualityExecutionStatus.QA_PENDING ->
      setOf(
        GoalQualityExecutionStatus.VALIDATION_DECISION_PENDING,
        GoalQualityExecutionStatus.REQUIRED_VALIDATION_PENDING,
      )
    GoalQualityExecutionStatus.VALIDATION_DECISION_PENDING ->
      setOf(
        GoalQualityExecutionStatus.VALIDATION_DECISION_PENDING,
        GoalQualityExecutionStatus.REQUIRED_VALIDATION_PENDING,
        GoalQualityExecutionStatus.SETTLED_PASSED,
        GoalQualityExecutionStatus.SETTLED_SKIPPED,
        GoalQualityExecutionStatus.SETTLED_WARNED,
      )
    GoalQualityExecutionStatus.REQUIRED_VALIDATION_PENDING ->
      setOf(
        GoalQualityExecutionStatus.VALIDATION_RUNNING,
        GoalQualityExecutionStatus.SETTLED_WARNED,
      )
    GoalQualityExecutionStatus.VALIDATION_RUNNING ->
      setOf(
        GoalQualityExecutionStatus.VALIDATION_COMPLETED_PENDING_SETTLEMENT,
        GoalQualityExecutionStatus.SETTLED_PASSED,
        GoalQualityExecutionStatus.SETTLED_WARNED,
      )
    GoalQualityExecutionStatus.VALIDATION_COMPLETED_PENDING_SETTLEMENT ->
      setOf(
        GoalQualityExecutionStatus.SETTLED_PASSED,
        GoalQualityExecutionStatus.SETTLED_SKIPPED,
        GoalQualityExecutionStatus.SETTLED_WARNED,
      )
    GoalQualityExecutionStatus.SETTLED_PASSED,
    GoalQualityExecutionStatus.SETTLED_SKIPPED,
    GoalQualityExecutionStatus.SETTLED_WARNED,
    GoalQualityExecutionStatus.RETIRED,
    -> emptySet()
  }
