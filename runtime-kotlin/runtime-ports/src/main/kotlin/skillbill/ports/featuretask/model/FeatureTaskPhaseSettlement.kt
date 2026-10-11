package skillbill.ports.featuretask.model

data class FeatureTaskPhaseSettlement(
  val workflowId: String,
  val phaseId: String,
  val attempt: Int,
  val kind: FeatureTaskPhaseSettlementKind,
  val envelopeJson: String,
  val recordedAt: String,
) {
  init {
    require(workflowId.isNotBlank()) { "workflowId must be non-blank." }
    require(phaseId.isNotBlank()) { "phaseId must be non-blank." }
    require(attempt >= 1) { "attempt must be >= 1." }
    require(envelopeJson.isNotBlank()) { "envelopeJson must be non-blank." }
    require(recordedAt.isNotBlank()) { "recordedAt must be non-blank." }
  }
}

sealed interface FeatureTaskPhaseSettlementReasonLookup {
  data class Unique(val settlement: FeatureTaskPhaseSettlement) : FeatureTaskPhaseSettlementReasonLookup

  data object Missing : FeatureTaskPhaseSettlementReasonLookup

  data object Ambiguous : FeatureTaskPhaseSettlementReasonLookup

  data object BudgetExceeded : FeatureTaskPhaseSettlementReasonLookup
}

sealed interface FeatureTaskPhaseSettlementKind {
  val wireValue: String

  data object Complete : FeatureTaskPhaseSettlementKind {
    override val wireValue: String = "complete"
  }

  data object Block : FeatureTaskPhaseSettlementKind {
    override val wireValue: String = "block"
  }

  data class Unknown(override val wireValue: String) : FeatureTaskPhaseSettlementKind {
    init {
      require(wireValue.isNotBlank()) { "wireValue must be non-blank." }
    }
  }

  companion object {
    @Deprecated("Renamed to Complete", ReplaceWith("FeatureTaskPhaseSettlementKind.Complete"))
    val COMPLETE: FeatureTaskPhaseSettlementKind
      get() = Complete

    @Deprecated("Renamed to Block", ReplaceWith("FeatureTaskPhaseSettlementKind.Block"))
    val BLOCK: FeatureTaskPhaseSettlementKind
      get() = Block

    fun fromWire(value: String): FeatureTaskPhaseSettlementKind =
      when (value) {
        Complete.wireValue -> Complete
        Block.wireValue -> Block
        else -> Unknown(value)
      }
  }
}
