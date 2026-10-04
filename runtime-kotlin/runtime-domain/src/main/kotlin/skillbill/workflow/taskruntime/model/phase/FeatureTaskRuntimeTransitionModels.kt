package skillbill.workflow.taskruntime.model.phase

import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict

sealed interface FeatureTaskRuntimeNextPhase {
  /** Re-enter or advance to [phaseId]. A backward re-entry additionally carries its loop context. */
  data class Next(
    val phaseId: String,
    val loopId: String? = null,
    val edgeIteration: Int? = null,
  ) : FeatureTaskRuntimeNextPhase {
    init {
      require(phaseId.isNotBlank()) { "FeatureTaskRuntimeNextPhase.Next.phaseId must be non-blank." }
      edgeIteration?.let { iteration ->
        require(iteration >= 1) {
          "FeatureTaskRuntimeNextPhase.Next.edgeIteration must be >= 1 when present, was $iteration."
        }
      }
    }
  }

  /** The pipeline ran to its end with no re-entry: the run advances to terminal success. */
  data object TerminalAdvance : FeatureTaskRuntimeNextPhase

  /**
   * A backward edge matched its triggering verdict but its per-edge cap is reached: the run blocks
   * loudly carrying the loop id, the exhausted iteration count, and the unresolved verdict.
   */
  data class TerminalBlock(
    val loopId: String,
    val edgeIteration: Int,
    val unresolvedVerdict: FeatureTaskRuntimeVerdict,
  ) : FeatureTaskRuntimeNextPhase {
    init {
      require(loopId.isNotBlank()) { "FeatureTaskRuntimeNextPhase.TerminalBlock.loopId must be non-blank." }
      require(edgeIteration >= 1) {
        "FeatureTaskRuntimeNextPhase.TerminalBlock.edgeIteration must be >= 1, was $edgeIteration."
      }
    }
  }
}

sealed interface FeatureTaskRuntimeTransitionResult {
  data class Resolved(val next: FeatureTaskRuntimeNextPhase) : FeatureTaskRuntimeTransitionResult

  data class PhaseOrderViolation(val phaseId: String, val message: String) : FeatureTaskRuntimeTransitionResult
}

enum class FeatureTaskRuntimeCapExhaustionBehavior {
  BLOCK,
  ADVANCE,
}

enum class FeatureTaskRuntimeBackwardEdgeCapScope {
  PER_RUN,
  PER_SUBTASK,
}

data class FeatureTaskRuntimeBackwardEdge(
  val fromPhaseId: String,
  val triggeringVerdict: FeatureTaskRuntimeVerdict,
  val destinationPhaseId: String,
  val loopId: String,
  val perEdgeCap: Int?,
  val capExhaustionBehavior: FeatureTaskRuntimeCapExhaustionBehavior =
    FeatureTaskRuntimeCapExhaustionBehavior.BLOCK,
  val capScope: FeatureTaskRuntimeBackwardEdgeCapScope = FeatureTaskRuntimeBackwardEdgeCapScope.PER_SUBTASK,
  val warnAfterIterations: Int? = null,
) {
  init {
    require(warnAfterIterations == null || warnAfterIterations >= 1) {
      "FeatureTaskRuntimeBackwardEdge.warnAfterIterations must be null or >= 1, was $warnAfterIterations."
    }
    require(fromPhaseId.isNotBlank()) { "FeatureTaskRuntimeBackwardEdge.fromPhaseId must be non-blank." }
    require(destinationPhaseId.isNotBlank()) { "FeatureTaskRuntimeBackwardEdge.destinationPhaseId must be non-blank." }
    require(loopId.isNotBlank()) { "FeatureTaskRuntimeBackwardEdge.loopId must be non-blank." }
    require(perEdgeCap == null || perEdgeCap >= 1) {
      "FeatureTaskRuntimeBackwardEdge.perEdgeCap must be null or >= 1, was $perEdgeCap."
    }
  }
}

data class FeatureTaskRuntimePhaseEntryGate(
  val phaseId: String,
  val requiredPhaseId: String,
  val requiredVerdict: FeatureTaskRuntimeVerdict,
) {
  init {
    require(phaseId.isNotBlank()) { "FeatureTaskRuntimePhaseEntryGate.phaseId must be non-blank." }
    require(requiredPhaseId.isNotBlank()) {
      "FeatureTaskRuntimePhaseEntryGate.requiredPhaseId must be non-blank."
    }
    require(phaseId != requiredPhaseId) {
      "FeatureTaskRuntimePhaseEntryGate.requiredPhaseId must differ from phaseId, both were '$phaseId'."
    }
  }
}

data class FeatureTaskRuntimeTransitionDeclaration(
  val forwardPhaseIds: List<String>,
  val backwardEdges: List<FeatureTaskRuntimeBackwardEdge> = emptyList(),
  val loopOnlyPhaseIds: Set<String> = emptySet(),
  val entryGates: List<FeatureTaskRuntimePhaseEntryGate> = emptyList(),
  val loopOnlySuccessors: Map<String, String> = emptyMap(),
) {
  fun entryGateViolation(
    phaseId: String,
    settledVerdictsByPhaseId: Map<String, FeatureTaskRuntimeVerdict>,
  ): FeatureTaskRuntimePhaseEntryGate? =
    entryGates.firstOrNull { gate ->
      gate.phaseId == phaseId && settledVerdictsByPhaseId[gate.requiredPhaseId] != gate.requiredVerdict
    }

  fun spanBetween(
    destinationPhaseId: String,
    sourcePhaseId: String,
  ): List<String> {
    val destinationIndex = forwardPhaseIds.indexOf(destinationPhaseId)
    val sourceIndex = forwardPhaseIds.indexOf(sourcePhaseId)
    return if (destinationIndex in 0..sourceIndex) {
      forwardPhaseIds.subList(destinationIndex, sourceIndex + 1)
    } else {
      listOf(destinationPhaseId)
    }
  }

  init {
    require(forwardPhaseIds.isNotEmpty()) {
      "FeatureTaskRuntimeTransitionDeclaration.forwardPhaseIds must list at least one phase."
    }
    require(forwardPhaseIds.none(String::isBlank)) {
      "FeatureTaskRuntimeTransitionDeclaration.forwardPhaseIds must not contain blank ids."
    }
    require(forwardPhaseIds.toSet().size == forwardPhaseIds.size) {
      "FeatureTaskRuntimeTransitionDeclaration.forwardPhaseIds must be distinct."
    }
    require(loopOnlyPhaseIds.all { it in forwardPhaseIds }) {
      "FeatureTaskRuntimeTransitionDeclaration.loopOnlyPhaseIds must be a subset of forwardPhaseIds."
    }
    loopOnlySuccessors.forEach { (source, successor) ->
      require(source in loopOnlyPhaseIds) {
        "FeatureTaskRuntimeTransitionDeclaration.loopOnlySuccessors source '$source' must be loop-only."
      }
      require(successor in loopOnlyPhaseIds) {
        "FeatureTaskRuntimeTransitionDeclaration.loopOnlySuccessors successor '$successor' must be loop-only; " +
          "a successor the forward edge already reaches needs no declaration."
      }
      require(forwardPhaseIds.indexOf(source) < forwardPhaseIds.indexOf(successor)) {
        "FeatureTaskRuntimeTransitionDeclaration.loopOnlySuccessors must run forward: '$source' precedes " +
          "'$successor' in the pipeline."
      }
    }
    backwardEdges.forEach { edge ->
      require(edge.fromPhaseId in forwardPhaseIds) {
        "FeatureTaskRuntimeBackwardEdge.fromPhaseId '${edge.fromPhaseId}' is not in the forward pipeline."
      }
      require(edge.destinationPhaseId in forwardPhaseIds) {
        "FeatureTaskRuntimeBackwardEdge.destinationPhaseId '${edge.destinationPhaseId}' is not in the forward pipeline."
      }
    }
    entryGates.forEach { gate ->
      val gatedIndex = forwardPhaseIds.indexOf(gate.phaseId)
      val requiredIndex = forwardPhaseIds.indexOf(gate.requiredPhaseId)
      require(gatedIndex >= 0) {
        "FeatureTaskRuntimePhaseEntryGate.phaseId '${gate.phaseId}' is not in the forward pipeline."
      }
      require(requiredIndex >= 0) {
        "FeatureTaskRuntimePhaseEntryGate.requiredPhaseId '${gate.requiredPhaseId}' is not in the forward pipeline."
      }
      require(requiredIndex < gatedIndex) {
        "FeatureTaskRuntimePhaseEntryGate requires '${gate.requiredPhaseId}' to precede '${gate.phaseId}' in the " +
          "forward pipeline, but it is at index $requiredIndex against $gatedIndex."
      }
    }
  }
}

data class FeatureTaskRuntimeTransitionContext(
  val settledVerdictsByPhaseId: Map<String, FeatureTaskRuntimeVerdict> = emptyMap(),
)
