package skillbill.engine.featuretask.model.execution

enum class QaPolicy(val wireValue: String) {
  STANDALONE("standalone"),
  AUTO("auto"),
  ON("on"),
  OFF("off"),
  ;

  companion object {
    fun fromWire(value: String?): QaPolicy? = entries.firstOrNull { it.wireValue == value }
  }
}

enum class QaSkipReason(val wireValue: String) {
  CAPABILITY_ABSENT("capability_absent"),
  UNSUPPORTED_TARGET("unsupported_target"),
  DISABLED_POLICY("disabled_policy"),
  ISOLATION_UNAVAILABLE("isolation_unavailable"),
  AMBIGUOUS_TARGET("ambiguous_target"),
  UNAUTHORIZED_TARGET("unauthorized_target"),
  OFFLINE_TARGET("offline_target"),
  DEVICE_CONTENDED("device_contended"),
  EMPTY_SCOPE("empty_scope"),
  PRODUCTION_ONLY("production_only"),
  ;

  companion object {
    fun fromWire(value: String?): QaSkipReason? = entries.firstOrNull { it.wireValue == value }
  }
}
