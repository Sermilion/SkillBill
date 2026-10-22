package skillbill.ports.featuretask.model

import skillbill.ports.idestatus.model.StandaloneQaOutcome

data class FeatureTaskQaOutcome(
  val workflowId: String,
  val outcome: StandaloneQaOutcome,
  val reason: String?,
  val qaFixAttempts: Int,
  val testedTreeIdentity: String?,
  val recordedAt: String,
  val findings: List<FeatureTaskQaFinding> = emptyList(),
  val reportText: String? = null,
) {
  init {
    require(workflowId.isNotBlank()) { "FeatureTaskQaOutcome.workflowId must not be blank." }
    require(qaFixAttempts in 0..MAX_QA_FIX_ATTEMPTS) { "FeatureTaskQaOutcome.qaFixAttempts is out of range." }
    require(reason == null || reason.isNotBlank()) { "FeatureTaskQaOutcome.reason must not be blank." }
  }

  val fixedFindings: List<FeatureTaskQaFinding>
    get() = findings.filter { it.status == FeatureTaskQaFindingStatus.FIXED }

  val notFixedFindings: List<FeatureTaskQaFinding>
    get() = findings.filter { it.status == FeatureTaskQaFindingStatus.NOT_FIXED }

  companion object {
    const val MAX_QA_FIX_ATTEMPTS: Int = 3
  }
}

data class FeatureTaskQaFinding(
  val id: String,
  val title: String,
  val evidencePaths: List<String>,
  val reportPath: String?,
  val status: FeatureTaskQaFindingStatus,
) {
  init {
    require(id.isNotBlank()) { "FeatureTaskQaFinding.id must not be blank." }
  }

  val evidencePath: String? get() = evidencePaths.firstOrNull() ?: reportPath
}

enum class FeatureTaskQaFindingStatus(val wireValue: String) {
  FIXED("fixed"),
  NOT_FIXED("not_fixed"),
  ;

  companion object {
    fun fromWire(value: String?): FeatureTaskQaFindingStatus? = entries.firstOrNull { it.wireValue == value }
  }
}
