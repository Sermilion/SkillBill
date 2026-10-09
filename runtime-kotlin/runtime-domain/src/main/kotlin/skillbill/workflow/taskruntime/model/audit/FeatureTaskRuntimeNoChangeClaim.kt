package skillbill.workflow.taskruntime.model.audit

enum class FeatureTaskRuntimeNoChangeReason(val wireValue: String) {
  OUT_OF_REPO("out_of_repo"),
  ALREADY_SATISFIED("already_satisfied"),
  NOT_REPRODUCIBLE("not_reproducible"),
  ;

  companion object {
    fun fromWireOrNull(value: Any?): FeatureTaskRuntimeNoChangeReason? {
      val normalized = (value as? String)?.trim()?.lowercase()?.replace('-', '_')?.replace(' ', '_')
      return entries.firstOrNull { it.wireValue == normalized }
    }
  }
}

data class FeatureTaskRuntimeNoChangeCriterion(
  val criterionId: String,
  val verdict: FeatureTaskRuntimeNoChangeReason,
  val evidence: String,
)

data class FeatureTaskRuntimeNoChangeClaim(
  val reason: FeatureTaskRuntimeNoChangeReason,
  val criteria: List<FeatureTaskRuntimeNoChangeCriterion>,
  val citations: List<String>,
  val boundaryTrace: String,
  val owningSystem: String?,
  val suggestedHandoff: String,
) {
  companion object {
    const val KEY: String = "no_change"
  }
}
