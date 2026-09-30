package skillbill.architecture

internal object StrategyCapabilityTransitiveGraph {
  fun violations(
    sources: List<CapabilitySource>,
    roots: Set<String>,
  ): List<String> {
    val catalog = StrategyCapabilitySourceCatalog.parse(sources)
    val findings = linkedSetOf<String>()
    findings += StrategyCapabilityWriterInventory.violations(catalog)
    catalog.values.filter { symbol -> symbol.sourcePaths.any(roots::contains) }.forEach { root ->
      val reviewRoot = root.sourcePaths.any(::reviewConsumer)
      val forbidden =
        (
          rawAuthority -
            (if (reviewRoot) setOf("skillbill.engine.featuretask.slot.PhaseRunner") else emptySet())
        ) +
          (if (reviewRoot) emptySet() else reviewAuthority)
      val visited = mutableSetOf<String>()

      fun visit(
        name: String,
        trail: List<String>,
      ) {
        if (name in forbidden) {
          findings += "${root.path} reaches $name through ${trail.joinToString(" -> ")}"
          return
        }
        if (!visited.add(name)) return
        val symbol = catalog[name] ?: return
        symbol.unresolved.forEach { missing ->
          findings +=
            "${root.path} has unresolved governed authority edge $missing through ${trail.joinToString(" -> ")}"
        }
        val edges = symbol.edges + if (name == root.name) symbol.incomingTypes else emptySet()
        edges.forEach { edge -> visit(edge, trail + edge) }
      }
      visit(root.name, listOf(root.name))
    }
    return findings.toList()
  }

  private fun reviewConsumer(path: String): Boolean = "/slot/codereview/" in path || path.startsWith("codereview/")

  private val rawAuthority =
    setOf(
      "skillbill.engine.featuretask.slot.state.PhaseRunState",
      "skillbill.engine.featuretask.slot.PhaseRunner",
      "skillbill.engine.featuretask.slot.state.PhaseRunRecords",
      "skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunState",
      "skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopSession",
      "skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunTransitionOwner",
      "skillbill.engine.featuretask.slot.attempt.PhaseAttemptRunHost",
      "skillbill.engine.featuretask.slot.attempt.PhaseAttemptRunLoopBindingAccess",
      "skillbill.engine.featuretask.slot.attempt.PhaseOutputSettlementContext",
      "skillbill.engine.featuretask.slot.attempt.PhaseCheckpointRemediationContext",
      "skillbill.engine.featuretask.slot.attempt.PhaseQualityGateCycleContext",
      "skillbill.engine.featuretask.slot.attempt.PhaseRuntimeFinalizationContext",
      "skillbill.engine.featuretask.slot.attempt.PhaseRunLoopAttemptScope",
    )

  private val reviewAuthority =
    setOf(
      "skillbill.engine.featuretask.slot.state.PhaseReviewStepBinding",
      "skillbill.engine.featuretask.slot.state.PhaseReviewPassState",
      "skillbill.engine.featuretask.slot.state.PhaseReviewSettlementState",
      "skillbill.engine.featuretask.slot.state.PhaseFindingVerificationState",
      "skillbill.engine.featuretask.slot.state.PhaseReviewFindingObservations",
      "skillbill.engine.featuretask.slot.state.PhaseRepairReceiptState",
      "skillbill.engine.featuretask.slot.state.PhaseReviewGenerationState",
    )
}
