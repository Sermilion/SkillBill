package skillbill.engine.featuretask.model.execution

public enum class ValidationGateCyclePhase {
  INITIAL_DISCOVERY,
  POST_REPAIR_VERIFY,
}

public enum class ValidationGateCommandFamily {
  BUILD,
  VALIDATION,
}
