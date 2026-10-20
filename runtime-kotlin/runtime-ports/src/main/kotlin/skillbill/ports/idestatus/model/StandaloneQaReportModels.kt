package skillbill.ports.idestatus.model

data class StandaloneQaReport(
  val criteria: List<StandaloneQaCriterion>,
) {
  val failed: List<StandaloneQaCriterion> get() = criteria.filter { it.status == StandaloneQaCriterionStatus.FAILED }

  val passed: List<StandaloneQaCriterion> get() = criteria.filter { it.status == StandaloneQaCriterionStatus.PASSED }
}

data class StandaloneQaCriterion(
  val id: String?,
  val status: StandaloneQaCriterionStatus,
  val checks: List<StandaloneQaCheck>,
  val findings: List<StandaloneQaCriterionFinding>,
) {
  init {
    require(id == null || id.isNotBlank()) { "StandaloneQaCriterion.id must be null or nonblank." }
  }
}

enum class StandaloneQaCriterionStatus(val wireValue: String) {
  PASSED("passed"),
  FAILED("failed"),
  ;

  companion object {
    fun fromWire(value: String?): StandaloneQaCriterionStatus? = entries.firstOrNull { it.wireValue == value }
  }
}

data class StandaloneQaCheck(
  val description: String,
  val passed: Boolean,
)

data class StandaloneQaCriterionFinding(
  val id: String,
  val steps: List<String>,
  val expected: String,
  val observed: String,
)
