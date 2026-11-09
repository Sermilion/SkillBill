package skillbill.workflow.taskruntime.artifact.historical

import skillbill.contracts.workflow.featuretask.ACCEPTANCE_CRITERION_MIGRATION_CONTRACT_VERSION
import skillbill.error.migration.invalidAcceptanceCriterionMigration
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.taskruntime.artifact.exactInt
import skillbill.workflow.taskruntime.model.audit.history.AcceptanceCriterionMigrationContext
import skillbill.workflow.taskruntime.model.audit.history.AcceptanceCriterionMigrationProvenance
import skillbill.workflow.taskruntime.model.audit.history.AcceptanceCriterionMigrationStep
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import java.security.MessageDigest
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionMigrationKeys as Keys

fun AcceptanceCriterionMigrationProvenance.asMigrationWireMap(): FeatureTaskRuntimeWorkflowArtifactMap =
  FeatureTaskRuntimeWorkflowArtifactMap.from(
    mapOf(
      Keys.CONTRACT_VERSION to ACCEPTANCE_CRITERION_MIGRATION_CONTRACT_VERSION,
      Keys.WORKFLOW_ID to workflowId,
      Keys.ORIGINAL_ARTIFACTS_JSON to originalArtifactsJson,
      Keys.SOURCE_DIGEST to sourceDigest,
      Keys.STEPS to
        steps.map {
          mapOf(
            Keys.FAMILY to it.family,
            Keys.SOURCE_VERSION to it.sourceVersion,
            Keys.TARGET_VERSION to it.targetVersion,
          )
        },
      Keys.CONTEXTS to
        contexts.map {
          mapOf(
            Keys.FAMILY to it.family,
            Keys.CRITERION_REF to it.criterionRef,
            Keys.BUDGET_IDENTITY to it.budgetIdentity,
            Keys.HISTORICAL_CHARGES to it.historicalCharges,
            Keys.ATTRIBUTION_UNKNOWN to it.attributionUnknown,
            Keys.RECONCILIATION_REQUIRED to it.reconciliationRequired,
            Keys.ESCALATION_OWED to it.escalationOwed,
          )
        },
    ),
  ).also { it.toMigrationProvenance(workflowId) }

fun FeatureTaskRuntimeWorkflowArtifactMap.toMigrationProvenance(
  source: String,
  expectedWorkflowId: String? = null,
): AcceptanceCriterionMigrationProvenance {
  validateMigrationHeader(source, expectedWorkflowId)
  val digest = migrationText(Keys.SOURCE_DIGEST, source)
  if (!Regex("^[a-f0-9]{64}$").matches(digest)) {
    throw invalidAcceptanceCriterionMigration(
      source,
      "Invalid source digest",
    )
  }
  val steps = migrationSteps(source)
  val contexts = migrationContexts(source)
  validateMigrationHistory(steps, contexts, source)
  val original = migrationText(Keys.ORIGINAL_ARTIFACTS_JSON, source)
  val actualDigest =
    MessageDigest.getInstance("SHA-256")
      .digest(original.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
  if (digest != actualDigest) {
    throw invalidAcceptanceCriterionMigration(
      source,
      "Source digest differs from retained bytes",
    )
  }
  return AcceptanceCriterionMigrationProvenance(
    migrationText(Keys.WORKFLOW_ID, source),
    original,
    digest,
    steps,
    contexts,
  )
}

private fun FeatureTaskRuntimeWorkflowArtifactMap.migrationText(
  key: String,
  source: String,
): String =
  (this[key] as? String)?.takeIf { it.isNotBlank() }
    ?: throw invalidAcceptanceCriterionMigration(source, "$key must be nonblank text")

private fun FeatureTaskRuntimeWorkflowArtifactMap.migrationBoolean(
  key: String,
  source: String,
): Boolean = this[key] as? Boolean ?: throw invalidAcceptanceCriterionMigration(source, "$key must be boolean")

private fun FeatureTaskRuntimeWorkflowArtifactMap.migrationRows(
  key: String,
  source: String,
): List<FeatureTaskRuntimeWorkflowArtifactMap> =
  (this[key] as? List<*>)?.map {
    FeatureTaskRuntimeWorkflowArtifactMap.from(it).also { row ->
      if (!row.isObject) throw invalidAcceptanceCriterionMigration(source, "$key contains a non-object")
    }
  } ?: throw invalidAcceptanceCriterionMigration(source, "$key must be an array")

private val PROVENANCE_FIELDS =
  setOf(
    Keys.CONTRACT_VERSION,
    Keys.WORKFLOW_ID,
    Keys.ORIGINAL_ARTIFACTS_JSON,
    Keys.SOURCE_DIGEST,
    Keys.STEPS,
    Keys.CONTEXTS,
  )
private val STEP_FIELDS = setOf(Keys.FAMILY, Keys.SOURCE_VERSION, Keys.TARGET_VERSION)
private val CONTEXT_FIELDS =
  setOf(
    Keys.FAMILY,
    Keys.CRITERION_REF,
    Keys.BUDGET_IDENTITY,
    Keys.HISTORICAL_CHARGES,
    Keys.ATTRIBUTION_UNKNOWN,
    Keys.RECONCILIATION_REQUIRED,
    Keys.ESCALATION_OWED,
  )

private fun FeatureTaskRuntimeWorkflowArtifactMap.migrationSteps(source: String) =
  migrationRows(Keys.STEPS, source).map {
    if (it.keys != STEP_FIELDS) throw invalidAcceptanceCriterionMigration(source, "Invalid conversion step")
    AcceptanceCriterionMigrationStep(
      it.migrationText(Keys.FAMILY, source),
      it.migrationText(Keys.SOURCE_VERSION, source),
      it.migrationText(Keys.TARGET_VERSION, source),
    )
  }

private fun FeatureTaskRuntimeWorkflowArtifactMap.migrationContexts(source: String) =
  migrationRows(Keys.CONTEXTS, source).map {
    if (it.keys != CONTEXT_FIELDS) throw invalidAcceptanceCriterionMigration(source, "Invalid context fields")
    val ref = it.migrationText(Keys.CRITERION_REF, source)
    val charges = it.exactInt(Keys.HISTORICAL_CHARGES)
    if (!Regex("^AC-[0-9]{3}$").matches(ref) || charges == null || charges < 0) {
      throw invalidAcceptanceCriterionMigration(source, "Invalid criterion or historical charges")
    }
    AcceptanceCriterionMigrationContext(
      it.migrationText(Keys.FAMILY, source),
      ref,
      it.migrationText(Keys.BUDGET_IDENTITY, source),
      charges,
      it.migrationBoolean(Keys.ATTRIBUTION_UNKNOWN, source),
      it.migrationBoolean(Keys.RECONCILIATION_REQUIRED, source),
      it.migrationBoolean(Keys.ESCALATION_OWED, source),
    )
  }

private fun validateMigrationHistory(
  steps: List<AcceptanceCriterionMigrationStep>,
  contexts: List<AcceptanceCriterionMigrationContext>,
  source: String,
) {
  if (contexts.distinctBy { it.family to it.criterionRef }.size != contexts.size) {
    throw invalidAcceptanceCriterionMigration(source, "Duplicate criterion context")
  }
  validateMigrationSteps(steps, source)
  val contextFamilies =
    setOf(
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_ACCEPTANCE_CRITERION_REPAIR.label(),
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_IMPLEMENTATION_PROGRESS.label(),
    )
  if (contexts.any { it.family !in contextFamilies }) {
    throw invalidAcceptanceCriterionMigration(source, "Unknown accounting context family")
  }
}

private fun FeatureTaskRuntimeWorkflowArtifactMap.validateMigrationHeader(
  source: String,
  expectedWorkflowId: String?,
) {
  if (expectedWorkflowId != null && this[Keys.WORKFLOW_ID] != expectedWorkflowId) {
    throw invalidAcceptanceCriterionMigration(source, "Retained workflow identity differs")
  }
  val supported = this[Keys.CONTRACT_VERSION] == ACCEPTANCE_CRITERION_MIGRATION_CONTRACT_VERSION
  if (!isObject || keys != PROVENANCE_FIELDS || !supported) {
    throw invalidAcceptanceCriterionMigration(source, "Unsupported version or fields")
  }
}

private fun validateMigrationSteps(
  steps: List<AcceptanceCriterionMigrationStep>,
  source: String,
) {
  steps.groupBy { it.family }.forEach { (familyLabel, chain) ->
    val versions =
      DurableWorkflowArtifactFamily.entries
        .singleOrNull { it.label() == familyLabel }?.criterionVersions()
        ?: throw invalidAcceptanceCriterionMigration(source, "Unknown migration family")
    val supportedSteps =
      chain.all { step ->
        val index = versions.indexOf(step.sourceVersion)
        index >= 0 && versions.getOrNull(index + 1) == step.targetVersion
      }
    val contiguous = chain.zipWithNext().all { (before, after) -> before.targetVersion == after.sourceVersion }
    if (!supportedSteps || !contiguous) {
      throw invalidAcceptanceCriterionMigration(source, "Invalid ordered conversion chain")
    }
  }
}
