package skillbill.engine.featuretask.model.execution

enum class ValidationGateCyclePhase {
  INITIAL_DISCOVERY,
  POST_REPAIR_VERIFY,
}

enum class ValidationGateCommandFamily {
  BUILD,
  VALIDATION,
}
