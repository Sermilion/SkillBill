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
    "copyGoalChildHistoryEvidenceSchema",
    "goal-child-history-evidence-schema.yaml",
    "SKILL-446: canonical goal-child history evidence schema",
  )
  copy(
    "copyGoalChildHistoryDraftSchema",
    "goal-child-history-draft-schema.yaml",
    "SKILL-446: canonical goal-child history draft schema",
  )
  copy(
    "copyGoalFinalizationHistorySchema",
    "goal-finalization-history-schema.yaml",
    "SKILL-446: canonical goal finalization history schema",
  )
  copy(
    "copyGoalQualityExecutionSchema",
    "goal-quality-execution-schema.yaml",
    "SKILL-446: canonical goal quality execution schema",
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
  copy("copyWorkBoardSchema", "work-board-schema.yaml", "SKILL-418: canonical work-board schema")
  copy("copyWorkDetailSchema", "work-detail-schema.yaml", "SKILL-418: canonical work-detail schema")
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
    "copyBlockIncidentSchema",
    "block-incident-schema.yaml",
    "SKILL-435: canonical block-incident schema",
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
    "copyStandaloneMonitorOwnershipSchema",
    "standalone-monitor-ownership-schema.yaml",
    "SKILL-427: canonical standalone monitor ownership schema",
  )
  copy(
    "copyQaRepairStateSchema",
    "qa-repair-state-schema.yaml",
    "SKILL-419: canonical QA repair-state schema",
  )
  copy(
    "copyPreviousQaRepairStateSchema",
    "qa-repair-state-schema-0.1.yaml",
    "QA repair state migration from a single receipt reference",
  )
  copy(
    "copyQaDeviceLeaseSchema",
    "qa-device-lease-schema.yaml",
    "SKILL-419: canonical QA device lease schema",
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
    "copyAcceptanceCriterionRepairStateSchema",
    "acceptance-criterion-repair-state-schema.yaml",
    "SKILL-426: canonical acceptance-criterion repair-state schema",
  )
  copy(
    "copyAcceptanceCriterionAuditResultSchema",
    "acceptance-criterion-audit-result-schema.yaml",
    "SKILL-426: canonical acceptance-criterion audit-result schema",
  )
  copy(
    "copyAcceptanceCriterionRepairHandoffSchema",
    "acceptance-criterion-repair-handoff-schema.yaml",
    "SKILL-426: canonical acceptance-criterion repair-handoff schema",
  )
  copy(
    "copyAcceptanceCriterionRepairAdoptionSchema",
    "acceptance-criterion-repair-adoption-schema.yaml",
    "SKILL-426: canonical acceptance-criterion repair-adoption schema",
  )
  copy(
    "copyInteractionScenarioCatalogSchema",
    "interaction-scenario-catalog-schema.yaml",
    "SKILL-442: canonical interaction-scenario catalog schema",
  )
  copy(
    "copyGoalAssessmentRecordSchema",
    "goal-assessment-record-schema.yaml",
    "SKILL-442: canonical goal-assessment record schema",
  )
  copy(
    "copyFeatureTaskRuntimeValidationTestProbesSchema",
    "feature-task-runtime-validation-test-probes-schema.yaml",
    "SKILL-442: canonical validation test-probes schema",
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
    "copyFeatureTaskRuntimeImplementationProgressSchema",
    "feature-task-runtime-implementation-progress-schema.yaml",
    "SKILL-436: canonical implementation-progress schema",
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
    "copyFeatureTaskRuntimeExecutionPlanSchemaV01",
    "feature-task-runtime-execution-plan-0.1.yaml",
    "SKILL-403: historical execution-plan schema for admitted 0.1 plans",
  )
  copy(
    "copyFeatureTaskRuntimeExecutionPlanSchemaV02",
    "feature-task-runtime-execution-plan-0.2.yaml",
    "SKILL-419: historical execution-plan schema for admitted 0.2 plans",
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
