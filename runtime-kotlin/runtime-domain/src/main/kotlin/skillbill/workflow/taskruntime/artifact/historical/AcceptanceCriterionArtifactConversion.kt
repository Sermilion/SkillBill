package skillbill.workflow.taskruntime.artifact.historical

import skillbill.contracts.workflow.featuretask.ACCEPTANCE_CRITERION_AUDIT_RESULT_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.ACCEPTANCE_CRITERION_AUDIT_RESULT_INTERACTION_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.ACCEPTANCE_CRITERION_AUDIT_RESULT_PREVIOUS_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.ACCEPTANCE_CRITERION_REPAIR_HANDOFF_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.ACCEPTANCE_CRITERION_REPAIR_HANDOFF_PREVIOUS_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.ACCEPTANCE_CRITERION_REPAIR_STATE_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.ACCEPTANCE_CRITERION_REPAIR_STATE_INTERACTION_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.ACCEPTANCE_CRITERION_REPAIR_STATE_PREVIOUS_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_IMPLEMENTATION_PROGRESS_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_IMPLEMENTATION_PROGRESS_PREVIOUS_CONTRACT_VERSION
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.model.repair.FocusedRepairStrategy
import skillbill.workflow.taskruntime.artifact.implementationFields
import skillbill.workflow.taskruntime.artifact.repairWireFields
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionEvidenceLifecycle
import skillbill.workflow.taskruntime.model.audit.history.AcceptanceCriterionArtifactConversion
import skillbill.workflow.taskruntime.model.audit.history.AcceptanceCriterionArtifactTarget
import skillbill.workflow.taskruntime.model.audit.history.AcceptanceCriterionMigrationContext
import skillbill.workflow.taskruntime.model.audit.history.AcceptanceCriterionMigrationStep
import skillbill.workflow.taskruntime.model.audit.history.AcceptanceCriterionMigrationTarget
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeImplementationProgress
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionMigrationKeys as MigrationKeys
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionProgressPayloadKeys as ProgressKeys
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionRepairPayloadKeys as Keys

fun AcceptanceCriterionMigrationTarget.targets(): List<AcceptanceCriterionArtifactTarget> =
  DurableWorkflowArtifactFamily.entries.mapNotNull { family ->
    val compatible = this == AcceptanceCriterionMigrationTarget.COMPATIBLE
    val version =
      when (family) {
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_ACCEPTANCE_CRITERION_REPAIR ->
          if (compatible) {
            ACCEPTANCE_CRITERION_REPAIR_STATE_INTERACTION_CONTRACT_VERSION
          } else {
            ACCEPTANCE_CRITERION_REPAIR_STATE_CONTRACT_VERSION
          }
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_ACCEPTANCE_CRITERION_AUDIT_RESULT ->
          if (compatible) {
            ACCEPTANCE_CRITERION_AUDIT_RESULT_INTERACTION_CONTRACT_VERSION
          } else {
            ACCEPTANCE_CRITERION_AUDIT_RESULT_CONTRACT_VERSION
          }
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_ACCEPTANCE_CRITERION_REPAIR_HANDOFF ->
          if (compatible) {
            ACCEPTANCE_CRITERION_REPAIR_HANDOFF_PREVIOUS_CONTRACT_VERSION
          } else {
            ACCEPTANCE_CRITERION_REPAIR_HANDOFF_CONTRACT_VERSION
          }
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_IMPLEMENTATION_PROGRESS ->
          if (compatible) {
            FEATURE_TASK_RUNTIME_IMPLEMENTATION_PROGRESS_PREVIOUS_CONTRACT_VERSION
          } else {
            FEATURE_TASK_RUNTIME_IMPLEMENTATION_PROGRESS_CONTRACT_VERSION
          }
        else -> return@mapNotNull null
      }
    AcceptanceCriterionArtifactTarget(family, version)
  }

fun convertAcceptanceCriterionArtifact(
  family: DurableWorkflowArtifactFamily,
  source: FeatureTaskRuntimeWorkflowArtifactMap,
  targetVersion: String,
): AcceptanceCriterionArtifactConversion {
  val versions = family.criterionVersions() ?: return AcceptanceCriterionArtifactConversion.Unsupported(family, null)
  val version = source[Keys.CONTRACT_VERSION] as? String
  if (version !in versions || targetVersion !in versions) {
    return AcceptanceCriterionArtifactConversion.Unsupported(
      family,
      if (version !in versions) version else targetVersion,
    )
  }
  if (versions.indexOf(version) >= versions.indexOf(targetVersion)) {
    return AcceptanceCriterionArtifactConversion.Current(source)
  }
  var payload = source
  val steps = mutableListOf<AcceptanceCriterionMigrationStep>()
  while (payload[Keys.CONTRACT_VERSION] != targetVersion) {
    val from = payload[Keys.CONTRACT_VERSION] as String
    val to = versions[versions.indexOf(from) + 1]
    payload = nextArtifactVersion(family, payload, from, to)
    steps += AcceptanceCriterionMigrationStep(family.label(), from, to)
  }
  return AcceptanceCriterionArtifactConversion.Converted(payload, steps, source.migrationContexts(family))
}

fun DurableWorkflowArtifactFamily.criterionVersions(): List<String>? =
  when (this) {
    DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_ACCEPTANCE_CRITERION_REPAIR ->
      listOf(
        ACCEPTANCE_CRITERION_REPAIR_STATE_PREVIOUS_CONTRACT_VERSION,
        ACCEPTANCE_CRITERION_REPAIR_STATE_INTERACTION_CONTRACT_VERSION,
        ACCEPTANCE_CRITERION_REPAIR_STATE_CONTRACT_VERSION,
      )
    DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_ACCEPTANCE_CRITERION_AUDIT_RESULT ->
      listOf(
        ACCEPTANCE_CRITERION_AUDIT_RESULT_PREVIOUS_CONTRACT_VERSION,
        ACCEPTANCE_CRITERION_AUDIT_RESULT_INTERACTION_CONTRACT_VERSION,
        ACCEPTANCE_CRITERION_AUDIT_RESULT_CONTRACT_VERSION,
      )
    DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_ACCEPTANCE_CRITERION_REPAIR_HANDOFF ->
      listOf(
        ACCEPTANCE_CRITERION_REPAIR_HANDOFF_PREVIOUS_CONTRACT_VERSION,
        ACCEPTANCE_CRITERION_REPAIR_HANDOFF_CONTRACT_VERSION,
      )
    DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_IMPLEMENTATION_PROGRESS ->
      listOf(
        FEATURE_TASK_RUNTIME_IMPLEMENTATION_PROGRESS_PREVIOUS_CONTRACT_VERSION,
        FEATURE_TASK_RUNTIME_IMPLEMENTATION_PROGRESS_CONTRACT_VERSION,
      )
    else -> null
  }

private fun FeatureTaskRuntimeWorkflowArtifactMap.strictRepair(): FeatureTaskRuntimeWorkflowArtifactMap {
  val historical = toHistoricalRepairState("repair-migration")
  val fields = historical.state.repairWireFields().toMutableMap()
  fields[MigrationKeys.MIGRATION_SOURCE_VERSION] = historical.contractVersion
  return FeatureTaskRuntimeWorkflowArtifactMap.from(fields)
}

private fun FeatureTaskRuntimeWorkflowArtifactMap.strictProgress(): FeatureTaskRuntimeWorkflowArtifactMap {
  val old = toHistoricalImplementationProgress("progress-migration")
  val fields =
    FeatureTaskRuntimeImplementationProgress(
      old.criterionIds,
      old.currentIndex,
      old.workerCount,
      old.launchCount,
    )
      .implementationFields().toMutableMap()
  fields[MigrationKeys.MIGRATION_SOURCE_VERSION] =
    FEATURE_TASK_RUNTIME_IMPLEMENTATION_PROGRESS_PREVIOUS_CONTRACT_VERSION
  return FeatureTaskRuntimeWorkflowArtifactMap.from(fields)
}

private fun FeatureTaskRuntimeWorkflowArtifactMap.strictHandoff(): FeatureTaskRuntimeWorkflowArtifactMap =
  FeatureTaskRuntimeWorkflowArtifactMap.from(
    this - ProgressKeys.COMPLETED_MUTATION - ProgressKeys.INDEPENDENT_PROGRESS +
      mapOf(
        Keys.CONTRACT_VERSION to ACCEPTANCE_CRITERION_REPAIR_HANDOFF_CONTRACT_VERSION,
        MigrationKeys.MIGRATION_SOURCE_VERSION to ACCEPTANCE_CRITERION_REPAIR_HANDOFF_PREVIOUS_CONTRACT_VERSION,
        ProgressKeys.EVIDENCE_LIFECYCLE to AcceptanceCriterionEvidenceLifecycle.UNKNOWN_HISTORICAL_AUTHORITY.wireValue,
      ),
  )

fun FeatureTaskRuntimeWorkflowArtifactMap.migrationContexts(
  family: DurableWorkflowArtifactFamily,
): List<AcceptanceCriterionMigrationContext> =
  when (family) {
    DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_ACCEPTANCE_CRITERION_REPAIR -> {
      val old = toHistoricalRepairState("repair-migration-context").state
      old.criteria.map {
        val charges = old.unsuccessfulTotals.getOrDefault(it.canonicalRef, 0)
        AcceptanceCriterionMigrationContext(
          family.label(),
          it.canonicalRef,
          "historical-outcome:${old.budgetStartOutcomeIndex}",
          charges,
          old.outcomes.any { outcome -> outcome.criterionRef == it.canonicalRef } ||
            (this[ProgressKeys.COMPLETED_MUTATIONS] as? List<*>)?.isNotEmpty() == true,
          it.canonicalRef in old.unresolvedQueue || it.canonicalRef in old.pendingAuditRefs,
          it.canonicalRef in old.unresolvedQueue && charges >= FocusedRepairStrategy.MAX_UNSUCCESSFUL_OUTCOMES,
        )
      }
    }
    DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_IMPLEMENTATION_PROGRESS -> {
      val old = toHistoricalImplementationProgress("progress-migration-context")
      old.criterionIds.map {
        val charges = if (it == old.criterionIds.getOrNull(old.currentIndex)) old.workerCount else 0
        AcceptanceCriterionMigrationContext(
          family.label(),
          it,
          "historical-worker-budget",
          charges,
          old.launchCount > 0,
          it !in old.closedIds,
          it !in old.closedIds && charges >= FocusedRepairStrategy.MAX_UNSUCCESSFUL_OUTCOMES,
        )
      }
    }
    else -> emptyList()
  }

private fun nextArtifactVersion(
  family: DurableWorkflowArtifactFamily,
  payload: FeatureTaskRuntimeWorkflowArtifactMap,
  from: String,
  to: String,
): FeatureTaskRuntimeWorkflowArtifactMap =
  when (family) {
    DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_ACCEPTANCE_CRITERION_REPAIR ->
      payload.upgradeRepairStateFromPrevious() ?: payload.strictRepair()
    DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_ACCEPTANCE_CRITERION_AUDIT_RESULT ->
      payload.upgradeAuditResultFromPrevious() ?: FeatureTaskRuntimeWorkflowArtifactMap.from(
        payload + mapOf(Keys.CONTRACT_VERSION to to, MigrationKeys.MIGRATION_SOURCE_VERSION to from),
      )
    DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_IMPLEMENTATION_PROGRESS -> payload.strictProgress()
    DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_ACCEPTANCE_CRITERION_REPAIR_HANDOFF -> payload.strictHandoff()
    else -> error("Only criterion families have conversion versions")
  }
