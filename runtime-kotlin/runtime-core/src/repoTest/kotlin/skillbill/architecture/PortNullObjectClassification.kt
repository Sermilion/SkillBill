package skillbill.architecture

enum class PortNullObjectKind {
  TOTAL_REFUSAL,
  RECORDING_NULL_OBJECT,
  DELEGATION_COMPOSITE,
  DIAGNOSTIC_SINK,
}

enum class InterfaceNullObjectBodyForm {
  NULL,
  LITERAL,
  EMPTY_COLLECTION,
  UNIT_OR_EMPTY_BLOCK,
  THROW,
  ;

  companion object {
    private val literal = Regex("""true|false|-?\d[\d_]*(?:\.\d+)?[LlFfUu]*|"[^"$]*"""")
    private val emptyCollection =
      Regex("""empty(?:List|Set|Map)(?:<[^()]*>)?\(\)|(?:list|set|map)Of(?:<[^()]*>)?\(\)""")
    private val throwExpression = Regex("""throw\b[\s\S]*""")

    /** Names the Scope form of a member body expression, or null when it computes something. */
    fun classify(expression: String): InterfaceNullObjectBodyForm? =
      when {
        expression == "null" -> NULL
        expression == "Unit" -> UNIT_OR_EMPTY_BLOCK
        literal.matches(expression) -> LITERAL
        emptyCollection.matches(expression) -> EMPTY_COLLECTION
        throwExpression.matches(expression) -> THROW
        else -> null
      }

    /** A block body matches when it is empty or its only statement, with or without `return`, matches. */
    fun classifyBlockStatement(statement: String): InterfaceNullObjectBodyForm? =
      if (statement.isEmpty()) UNIT_OR_EMPTY_BLOCK else classify(statement.removePrefix("return ").trim())
  }
}

object PortNullObjectClassification {
  val classifiedObjects: Map<String, PortNullObjectKind> =
    mapOf(
      "UnavailableUnaddressedFindingsRepository" to PortNullObjectKind.TOTAL_REFUSAL,
      "UnavailableGoalRunnerControlRepository" to PortNullObjectKind.TOTAL_REFUSAL,
      "UnavailableSpecScratchStore" to PortNullObjectKind.TOTAL_REFUSAL,
      "UnavailableGoalRuntimeStateFileStore" to PortNullObjectKind.TOTAL_REFUSAL,
      "UnavailableDecompositionManifestStore" to PortNullObjectKind.TOTAL_REFUSAL,
      "UnavailableReviewRunLaneCompletenessRepository" to PortNullObjectKind.TOTAL_REFUSAL,
      "UnavailableReviewRunStageCompletenessRepository" to PortNullObjectKind.TOTAL_REFUSAL,
      "UnavailableReviewRunCompletenessRepository" to PortNullObjectKind.TOTAL_REFUSAL,
      "UnconfiguredRemoteTransportPort" to PortNullObjectKind.TOTAL_REFUSAL,
      "UnavailableCheckpointHistoryGitOperations" to PortNullObjectKind.TOTAL_REFUSAL,
      "UnavailableScopedStagingGitOperations" to PortNullObjectKind.TOTAL_REFUSAL,
      "UnavailableGoalSubtaskReviewGitOperations" to PortNullObjectKind.TOTAL_REFUSAL,
      "EmptyGoalRunnerControlRepository" to PortNullObjectKind.RECORDING_NULL_OBJECT,
      "EmptyAgentActivityStampRepository" to PortNullObjectKind.RECORDING_NULL_OBJECT,
      "EmptyWorktreeEditJournalRepository" to PortNullObjectKind.RECORDING_NULL_OBJECT,
      "NoopGoalRunnerAttemptLedgerStore" to PortNullObjectKind.RECORDING_NULL_OBJECT,
      "NoopGoalRunnerChildRepairStore" to PortNullObjectKind.RECORDING_NULL_OBJECT,
      "NoopIdeStatusValidator" to PortNullObjectKind.RECORDING_NULL_OBJECT,
      "AcceptingFeatureTaskRuntimeWireArtifactValidator" to PortNullObjectKind.RECORDING_NULL_OBJECT,
      "AcceptingFeatureTaskRuntimeWireArtifactValidator" to PortNullObjectKind.RECORDING_NULL_OBJECT,
      "AcceptingFeatureTaskRuntimeWireArtifactValidator" to PortNullObjectKind.RECORDING_NULL_OBJECT,
      "NoopRuntimePhaseFileManifestGitOperations" to PortNullObjectKind.RECORDING_NULL_OBJECT,
      "NoopWorkflowGitWorktreeOperations" to PortNullObjectKind.RECORDING_NULL_OBJECT,
      "NoopWorkflowGitRemoteOperations" to PortNullObjectKind.RECORDING_NULL_OBJECT,
      "NoopWorkflowGitCommitHistoryOperations" to PortNullObjectKind.RECORDING_NULL_OBJECT,
      "NoopWorkflowGitBranchOperations" to PortNullObjectKind.RECORDING_NULL_OBJECT,
      "NoopRepositoryFingerprintGitOperations" to PortNullObjectKind.RECORDING_NULL_OBJECT,
      "NoopGoalSubtaskReviewGitOperations" to PortNullObjectKind.RECORDING_NULL_OBJECT,
      "NoopWorkflowGitReleaseTagOperations" to PortNullObjectKind.RECORDING_NULL_OBJECT,
      "NoopWorkflowGitOperations" to PortNullObjectKind.DELEGATION_COMPOSITE,
      "NoopRuntimeTimingPort" to PortNullObjectKind.RECORDING_NULL_OBJECT,
      "NoopFeatureTaskRuntimeHeartbeat" to PortNullObjectKind.RECORDING_NULL_OBJECT,
      "NoopFeatureTaskRuntimeWorkerSupervisor" to PortNullObjectKind.RECORDING_NULL_OBJECT,
      "NoopRuntimeDiagnostics" to PortNullObjectKind.DIAGNOSTIC_SINK,
    )
}
