package skillbill.error.featuretask

class InvalidPhaseStrategyCompositionError(
  val reason: String,
) : IllegalArgumentException("Invalid phase strategy composition: $reason")
