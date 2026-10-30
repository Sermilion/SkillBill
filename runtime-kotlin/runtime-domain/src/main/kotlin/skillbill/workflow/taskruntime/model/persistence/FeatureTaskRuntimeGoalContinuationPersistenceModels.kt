package skillbill.workflow.taskruntime.model.persistence

data class FeatureTaskRuntimeGoalContinuationFieldAdoption(
  val field: String,
  val adoptedValue: String,
  val reason: String,
) {
  init {
    require(field.isNotBlank()) {
      "FeatureTaskRuntimeGoalContinuationFieldAdoption.field must be non-blank."
    }
    require(adoptedValue.isNotBlank()) {
      "FeatureTaskRuntimeGoalContinuationFieldAdoption.adoptedValue must be non-blank."
    }
    require(reason.isNotBlank()) {
      "FeatureTaskRuntimeGoalContinuationFieldAdoption.reason must be non-blank."
    }
  }
}

data class FeatureTaskRuntimeGoalPlanningImportSource(
  val kind: String,
  val planWorkflowId: String? = null,
  val detailsSha256: String? = null,
) {
  init {
    require(kind in KINDS) { "FeatureTaskRuntimeGoalPlanningImportSource.kind must be one of $KINDS." }
  }

  companion object {
    const val PLAN_WORKFLOW: String = "plan_workflow"
    const val LEGACY_TABLES: String = "legacy_tables"
    private val KINDS: Set<String> = setOf(PLAN_WORKFLOW, LEGACY_TABLES)
  }
}

data class FeatureTaskRuntimeGoalPlanningImport(
  val parentGoalWorkflowId: String,
  val normalizedIssueKey: String,
  val repositoryIdentity: String,
  val parentSpecHash: String,
  val decompositionManifestHash: String,
  val planningContractId: String,
  val planningContractVersion: String,
  val phaseOutputContractId: String,
  val phaseOutputContractVersion: String,
  val subtaskId: Int,
  val manifestOrder: Int,
  val governedSubSpecPath: String,
  val subSpecHash: String,
  val preplanPayloadSha256: String,
  val planPayloadSha256: String,
  val source: FeatureTaskRuntimeGoalPlanningImportSource? = null,
)
