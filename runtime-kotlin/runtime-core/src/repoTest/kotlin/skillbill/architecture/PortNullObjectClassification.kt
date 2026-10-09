package skillbill.architecture

enum class PortNullObjectKind {
  TOTAL_REFUSAL,
  RECORDING_NULL_OBJECT,
  DELEGATION_COMPOSITE,
  DIAGNOSTIC_SINK,
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
