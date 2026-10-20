package skillbill.engine.featuretask.qa.receipt

import skillbill.engine.featuretask.model.execution.QaPolicy
import skillbill.engine.featuretask.model.execution.QaSkipReason
import skillbill.scaffold.model.QaPlatform

enum class QaVerdict(val wireValue: String) {
  PASSED("passed"),
  FINDINGS_OPEN("findings_open"),
  SKIPPED("skipped"),
  INCOMPLETE("incomplete"),
  ;

  companion object {
    fun fromWire(value: String?): QaVerdict? = entries.firstOrNull { it.wireValue == value }
  }
}

enum class QaTargetKind(val wireValue: String) {
  PHYSICAL("physical"),
  SIMULATOR("simulator"),
  ;

  companion object {
    fun fromWire(value: String?): QaTargetKind? = entries.firstOrNull { it.wireValue == value }
  }
}

enum class QaTargetSelection(val wireValue: String) {
  EXPLICIT("explicit"),
  SINGLE("single"),
  SUBSTITUTED("substituted"),
  ;

  companion object {
    fun fromWire(value: String?): QaTargetSelection? = entries.firstOrNull { it.wireValue == value }
  }
}

enum class QaFlowStatus(val wireValue: String) {
  EXERCISED("exercised"),
  UNTESTED("untested"),
  ;

  companion object {
    fun fromWire(value: String?): QaFlowStatus? = entries.firstOrNull { it.wireValue == value }
  }
}

enum class QaAssertionOutcome(val wireValue: String) {
  PASS("pass"),
  FAIL("fail"),
  ;

  companion object {
    fun fromWire(value: String?): QaAssertionOutcome? = entries.firstOrNull { it.wireValue == value }
  }
}

enum class QaIncompleteReason(val wireValue: String) {
  INTERRUPTED("interrupted"),
  AUTOMATION_BROKEN("automation_broken"),
  BUDGET_EXHAUSTED("budget_exhausted"),
  DEVICE_LOST("device_lost"),
  UNTESTED_FLOWS("untested_flows"),
  OTHER("other"),
  ;

  companion object {
    fun fromWire(value: String?): QaIncompleteReason? = entries.firstOrNull { it.wireValue == value }
  }
}

enum class QaFallbackKind(val wireValue: String) {
  SIMULATOR_SUBSTITUTION("simulator_substitution"),
  TARGET_SUBSTITUTION("target_substitution"),
  RETRY("retry"),
  ;

  companion object {
    fun fromWire(value: String?): QaFallbackKind? = entries.firstOrNull { it.wireValue == value }
  }
}

enum class QaCleanupOutcome(val wireValue: String) {
  RELEASED("released"),
  RETAINED("retained"),
  ;

  companion object {
    fun fromWire(value: String?): QaCleanupOutcome? = entries.firstOrNull { it.wireValue == value }
  }
}

data class QaTarget(
  val platform: QaPlatform,
  val deviceFingerprint: String,
  val kind: QaTargetKind,
  val selection: QaTargetSelection,
)

data class QaFlow(
  val id: String,
  val name: String,
  val status: QaFlowStatus,
  val hardwareOnly: Boolean,
  val untestedReason: String?,
)

data class QaAssertion(
  val flowId: String,
  val outcome: QaAssertionOutcome,
  val criteria: List<String> = emptyList(),
  val description: String = "",
)

data class QaFinding(
  val id: String,
  val affectedCriteria: List<String> = emptyList(),
  val evidencePaths: List<String>,
  val steps: List<String> = emptyList(),
  val expected: String = "",
  val observed: String = "",
)

data class QaFallback(
  val kind: QaFallbackKind,
  val reason: String,
)

data class QaRetainedResource(
  val kind: String,
  val identity: String,
)

data class QaCleanup(
  val outcome: QaCleanupOutcome,
  val retainedResources: List<QaRetainedResource>,
  val guidance: String?,
)

data class QaReceipt(
  val verdict: QaVerdict,
  val headRevision: String,
  val treeIdentity: String,
  val hasArtifact: Boolean,
  val policy: QaPolicy,
  val target: QaTarget?,
  val flows: List<QaFlow>,
  val assertions: List<QaAssertion>,
  val findings: List<QaFinding>,
  val skipReason: QaSkipReason?,
  val incompleteReason: QaIncompleteReason?,
  val reasonDetail: String?,
  val fallbacks: List<QaFallback>,
  val cleanup: QaCleanup,
  val wire: Map<String, Any?>,
  val warnings: List<String> = emptyList(),
)
