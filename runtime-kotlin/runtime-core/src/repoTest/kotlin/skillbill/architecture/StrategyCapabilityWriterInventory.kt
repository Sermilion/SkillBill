package skillbill.architecture

internal object StrategyCapabilityWriterInventory {
  private const val PROGRESS = "skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunState"
  private const val SESSION = "skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopSession"
  private const val OWNER = "skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunTransitionOwner"

  val primitiveWriters: Set<String> =
    setOf(
      "reopenForReentry",
      "recordEdgeIteration",
      "invalidateProducerOutput",
      "reopenFromExplicitResume",
      "advanceReviewGeneration",
      "resetInvalidatedReviewGeneration",
      "recordCompleted",
      "restartAttemptBudget",
      "recordPhaseLaunched",
      "reserveReviewPass",
      "clearPersistedBlock",
      "discardStaleReentry",
      "clearBranchSetupBlock",
    ).mapTo(linkedSetOf()) { "$PROGRESS.$it" } +
      setOf(
        "transitionPendingReentry",
        "transitionReentryPair",
        "transitionToBlocked",
        "transitionToPaused",
        "transitionToDecomposed",
        "transitionAuditRetryFocusHint",
        "transitionResolvedBranch",
        "markCheckpointOwnershipDecided",
        "consumeOperatorBlockRetryCompletion",
        "markRecordRejectionSettlementPending",
        "clearRecordRejectionSettlementPending",
        "recordPhaseContentIdentities",
      ).map { "$SESSION.$it" }

  fun qualifiedWriterOwner(simpleName: String): String? =
    when (simpleName) {
      "FeatureTaskRuntimeRunState", PROGRESS -> PROGRESS
      "FeatureTaskRuntimeRunLoopSession", SESSION -> SESSION
      else -> null
    }

  fun violations(catalog: Map<String, CapabilitySymbol>): List<String> =
    catalog.values
      .filterNot { symbol ->
        listOf(OWNER, PROGRESS, SESSION).any { symbol.name == it || symbol.name.startsWith("$it.") }
      }.flatMap { symbol ->
        symbol.writerCalls.map { operation ->
          "${symbol.path}: ${symbol.name} calls $operation outside its transition owner"
        }
      }.distinct()
}
