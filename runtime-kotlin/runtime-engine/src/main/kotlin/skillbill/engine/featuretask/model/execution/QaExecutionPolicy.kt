package skillbill.engine.featuretask.model.execution

public enum class QaPolicy(public val wireValue: String) {
  STANDALONE("standalone"),
  AUTO("auto"),
  ON("on"),
  OFF("off"),
  ;

  public companion object {
    public fun fromWire(value: String?): QaPolicy? = entries.firstOrNull { it.wireValue == value }
  }
}

public enum class QaSkipReason(public val wireValue: String) {
  CAPABILITY_ABSENT("capability_absent"),
  UNSUPPORTED_TARGET("unsupported_target"),
  DISABLED_POLICY("disabled_policy"),
  ISOLATION_UNAVAILABLE("isolation_unavailable"),
  UNAUTHORIZED_TARGET("unauthorized_target"),
  OFFLINE_TARGET("offline_target"),
  DEVICE_CONTENDED("device_contended"),
  EMPTY_SCOPE("empty_scope"),
  PRODUCTION_ONLY("production_only"),
  DOCKER_UNAVAILABLE("docker_unavailable"),
  ENVIRONMENT_UNAVAILABLE("environment_unavailable"),
  ISOLATION_REJECTED("isolation_rejected"),
  SETUP_BUDGET_EXCEEDED("setup_budget_exceeded"),
  BROWSER_UNAVAILABLE("browser_unavailable"),
  ;

  public companion object {
    public fun fromWire(value: String?): QaSkipReason? = entries.firstOrNull { it.wireValue == value }
  }
}
