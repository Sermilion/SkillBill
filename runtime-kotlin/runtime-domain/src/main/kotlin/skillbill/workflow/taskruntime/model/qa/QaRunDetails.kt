package skillbill.workflow.taskruntime.model.qa

enum class QaAssertionOutcome(val wireValue: String) {
  PASSED("passed"),
  FAILED("failed"),
  NOT_EXERCISED("not_exercised"),
  ;

  companion object {
    fun fromWire(value: String?): QaAssertionOutcome? = entries.firstOrNull { it.wireValue == value }
  }
}

data class QaRunAssertion(
  val outcome: QaAssertionOutcome,
  val title: String,
  val detail: String,
  val evidencePaths: List<String> = emptyList(),
)

data class QaRunTemporaryEdit(
  val path: String?,
  val detail: String,
  val notRestored: Boolean,
)

data class QaRunCoverage(
  val kind: String,
  val verdict: String,
  val reason: String?,
  val backend: String?,
)

data class QaRunRetainedResource(
  val kind: String,
  val identity: String,
)

data class QaRunFinding(
  val id: String,
  val title: String,
  val observed: String?,
  val expected: String?,
  val steps: String?,
  val evidencePaths: List<String> = emptyList(),
)

data class QaRunDetails(
  val reportPath: String? = null,
  val invocationDirectory: String? = null,
  val skipReason: String? = null,
  val assertions: List<QaRunAssertion> = emptyList(),
  val coverage: List<QaRunCoverage> = emptyList(),
  val retainedResources: List<QaRunRetainedResource> = emptyList(),
  val temporaryEdits: List<QaRunTemporaryEdit> = emptyList(),
  val findings: List<QaRunFinding> = emptyList(),
)
