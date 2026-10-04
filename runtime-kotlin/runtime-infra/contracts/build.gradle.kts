plugins {
  id("skillbill.jvm-library")
  id("skillbill.repo-test")
  id("skillbill.quality")
  id("skillbill.governed-resources")
}

dependencies {
  implementation(project(":runtime-ports"))
  implementation(project(":runtime-domain"))
  implementation(project(":runtime-contracts"))
  implementation(libs.kotlin.inject.runtime)
  implementation(libs.json.schema.validator)
  implementation(libs.jackson.databind)
  implementation(libs.jackson.dataformat.yaml)
  testImplementation(testFixtures(project(":runtime-ports")))
  testImplementation(testFixtures(project(":runtime-infra:host")))
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.kotlin.test)
}

governedResources {
  sourceRoot.set(rootProject.layout.projectDirectory.dir("../orchestration/contracts"))
  destination.set("skillbill/infrastructure/contracts")

  copy(
    "copyAgentAddonSchema",
    "agent-addon-schema.yaml",
    "SKILL-122: canonical agent-addon schema",
  )
  copy(
    "copyReviewContextSchema",
    "review-context-schema.yaml",
    "SKILL-125: canonical review-context schema",
    "skillbill/contracts",
  )
  copy(
    "copyPlatformPackSchema",
    "platform-pack-schema.yaml",
    "SKILL-52: canonical platform-pack schema. Run from the repo root and ensure " +
      "orchestration/contracts/platform-pack-schema.yaml exists",
  )
  copy(
    "copyNativeAgentCompositionSchema",
    "native-agent-composition-schema.yaml",
    "SKILL-52: canonical native-agent composition schema",
  )
  copy(
    "copyNativeAgentLinkInventorySchema",
    "native-agent-link-inventory-schema.yaml",
    "SKILL-129: canonical native-agent link inventory schema",
  )
  copy(
    "copyWorkflowStateSchema",
    "workflow-state-schema.yaml",
    "SKILL-52: canonical workflow-state schema",
  )
  copy(
    "copyInstallPlanSchema",
    "install-plan-schema.yaml",
    "SKILL-52: canonical install-plan schema",
  )
  copy(
    "copyDecompositionManifestSchema",
    "decomposition-manifest-schema.yaml",
    "SKILL-52: canonical decomposition manifest schema",
  )
  copy(
    "copyDecompositionManifestBundleJournalSchema",
    "decomposition-manifest-bundle-journal-schema.yaml",
    "SKILL-248: canonical decomposition manifest bundle journal schema",
  )
  copy(
    "copyGoalObservabilityEventSchema",
    "goal-observability-event-schema.yaml",
    "SKILL-61: canonical goal-observability event schema",
  )
  copy(
    "copyGoalProgressEventSchema",
    "goal-progress-event-schema.yaml",
    "SKILL-64: canonical goal progress event schema",
  )
  copy(
    "copyIdeStatusSchema",
    "ide-status-schema.yaml",
    "SKILL-148: canonical IDE status schema",
  )
  copy(
    "copyGoalSubtaskReviewStateSchema",
    "goal-subtask-review-state-schema.yaml",
    "SKILL-119: canonical goal-subtask review-state schema",
  )
  copy(
    "copyRejectedOutputDiagnosticSchema",
    "rejected-output-diagnostic-schema.yaml",
    "SKILL-134: canonical rejected-output diagnostic schema",
  )
  copy(
    "copyProducerOutputEvidenceSchema",
    "producer-output-evidence-schema.yaml",
    "SKILL-152: canonical producer output evidence schema",
  )
  copy(
    "copyFeatureTaskRuntimeWorkerOwnershipSchema",
    "feature-task-runtime-worker-ownership-schema.yaml",
    "SKILL-120: canonical feature-task runtime worker-ownership schema",
  )
  copy(
    "copyFeatureTaskExecutionIdentitySchema",
    "feature-task-execution-identity-schema.yaml",
    "SKILL-120: canonical feature-task execution-identity schema",
  )
  copy(
    "copyFeatureTaskRuntimeHandoffEnvelopeSchema",
    "feature-task-runtime-handoff-envelope-schema.yaml",
    "SKILL-137: canonical handoff-envelope schema",
  )
  copy(
    "copyFeatureTaskRuntimePhaseLaunchBriefingSchema",
    "feature-task-runtime-phase-launch-briefing-schema.yaml",
    "SKILL-146: canonical phase-launch-briefing schema",
  )
  copy(
    "copyFeatureTaskRuntimePhaseHandoffSchema",
    "feature-task-runtime-phase-handoff-schema.yaml",
    "SKILL-146: canonical phase-handoff schema",
  )
  copy(
    "copyFeatureTaskRuntimePersistenceSchema",
    "feature-task-runtime-persistence-schema.yaml",
    "SKILL-146: canonical feature-task-runtime persistence schema",
  )
  copy(
    "copyFeatureTaskRuntimeProjectionMeasurementSchema",
    "feature-task-runtime-projection-measurement-schema.yaml",
    "SKILL-146: canonical projection-measurement schema",
  )
  copy(
    "copyFeatureTaskRuntimeSharedEvidenceProjectionSchema",
    "feature-task-runtime-shared-evidence-projection-schema.yaml",
    "SKILL-164: canonical shared-evidence projection schema",
  )
  copy(
    "copyFeatureTaskRuntimeBuildReceiptSchema",
    "feature-task-runtime-build-receipt.yaml",
    "SKILL-204: canonical build-receipt schema",
  )
  copy(
    "copyFeatureTaskRuntimeValidationEvidenceSchema",
    "feature-task-runtime-validation-evidence-schema.yaml",
    "SKILL-360: canonical validation-evidence schema",
  )
  copy(
    "copyFeatureTaskRuntimeReadinessEvidenceSchema",
    "feature-task-runtime-readiness-evidence-schema.yaml",
    "SKILL-364: canonical readiness-evidence schema",
  )
  copy(
    "copyGoalPlanningPreparationSchema",
    "goal-planning-preparation-schema.yaml",
    "SKILL-128: canonical goal planning preparation schema",
  )
  copy(
    "copyGoalPlanningPreparationHistoricalPhaseOutput06Schema",
    "goal-planning-preparation-schema-0.2-phase-output-0.6.yaml",
    "LOCAL-274870733146662: historical goal planning preparation schema for phase output 0.6",
  )
  copy(
    "copyFeatureTaskRuntimeImplementationAttemptSchema",
    "feature-task-runtime-implementation-attempt-schema.yaml",
    "SKILL-150: canonical implementation-attempt schema",
  )
  copy(
    "copyFeatureTaskRuntimeCheckpointIdentitySchema",
    "feature-task-runtime-checkpoint-identity-schema.yaml",
    "SKILL-150: canonical checkpoint-identity schema",
  )
  copy(
    "copyFeatureTaskRuntimeExecutionPlanSchema",
    "feature-task-runtime-execution-plan.yaml",
    "SKILL-384: canonical execution-plan schema",
  )
  copy(
    "copyFeatureTaskRuntimePhaseOutputSchema",
    "feature-task-runtime-phase-output-schema.yaml",
    "SKILL-380: canonical current phase-output schema",
  )
  copy(
    "copyFeatureTaskRuntimePhaseOutputSchemaV06",
    "feature-task-runtime-phase-output-schema-0.6.yaml",
    "LOCAL-274870733146662: historical phase-output migration source schema",
  )
  copy(
    "copyFeatureTaskRuntimeQuarantineSchema",
    "feature-task-runtime-quarantine-schema.yaml",
    "SKILL-140: canonical quarantine schema",
  )
  copy(
    "copyIssueKeySchema",
    "issue-key-schema.yaml",
    "canonical issue-key schema",
  )
}
