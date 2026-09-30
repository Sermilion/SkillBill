package skillbill.workflow.taskruntime.phase.task

import skillbill.workflow.engine.model.WorkflowDefinition
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffSourceRef
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun expectedPhaseWorkflowDependencies(): Map<String, List<String>> {
  val def = FeatureTaskRuntimePhaseWorkflowDefinition
  return mapOf(
    def.PHASE_PREPLAN to emptyList(),
    def.PHASE_PLAN to listOf(def.PHASE_PREPLAN),
    def.PHASE_IMPLEMENT to listOf(def.PHASE_PLAN),
    def.PHASE_SIMPLIFY to listOf(def.PHASE_IMPLEMENT),
    def.PHASE_AUDIT to listOf(def.PHASE_PLAN, def.PHASE_IMPLEMENT, def.PHASE_SIMPLIFY),
    def.PHASE_REVIEW to listOf(def.PHASE_AUDIT),
    def.PHASE_VERIFY_FINDINGS to listOf(def.PHASE_REVIEW),
    def.PHASE_IMPLEMENT_FIX to listOf(def.PHASE_VERIFY_FINDINGS),
    def.PHASE_BUILD to listOf(def.PHASE_PLAN, def.PHASE_AUDIT),
    def.PHASE_VALIDATE to listOf(def.PHASE_PLAN, def.PHASE_AUDIT),
    def.PHASE_WRITE_HISTORY to listOf(def.PHASE_IMPLEMENT, def.PHASE_VALIDATE),
    def.PHASE_COMMIT_PUSH to listOf(def.PHASE_IMPLEMENT, def.PHASE_VALIDATE, def.PHASE_WRITE_HISTORY),
    def.PHASE_PR to listOf(def.PHASE_IMPLEMENT, def.PHASE_COMMIT_PUSH),
  )
}

internal fun assertPerPhaseDependencySetsMatchDeclarations() {
  expectedPhaseWorkflowDependencies().forEach { (phaseId, expected) ->
    assertEquals(expected, phaseWorkflowDependenciesOf(phaseId), message = phaseId)
  }
}

private fun expectedConsumerProjectionMatrix(): Map<String, Set<Pair<String, String>>> {
  val def = FeatureTaskRuntimePhaseWorkflowDefinition
  return mapOf(
    def.PHASE_PLAN to setOf(def.PHASE_PREPLAN to "feature_task_runtime.phase_prose"),
    def.PHASE_IMPLEMENT to emptySet(),
    def.PHASE_SIMPLIFY to
      setOf(
        def.PHASE_IMPLEMENT to FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.CHANGE_RECEIPT,
      ),
    def.PHASE_AUDIT to
      setOf(
        def.PHASE_PLAN to "feature_task_runtime.phase_prose",
        def.PHASE_IMPLEMENT to "feature_task_runtime.phase_prose",
        def.PHASE_SIMPLIFY to "feature_task_runtime.phase_prose",
      ),
    def.PHASE_IMPLEMENT_FIX to
      setOf(
        def.PHASE_VERIFY_FINDINGS to
          FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.REVIEW_REPAIR_REQUEST,
      ),
    def.PHASE_VERIFY_FINDINGS to
      setOf(
        def.PHASE_REVIEW to
          FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.FINDINGS_VERIFICATION_INPUT,
      ),
    def.PHASE_REVIEW to emptySet(),
    def.PHASE_VALIDATE to
      setOf(
        def.PHASE_PLAN to FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.PHASE_PROSE,
        def.PHASE_PLAN to FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.VALIDATION_REQUEST,
      ),
    def.PHASE_BUILD to
      setOf(
        def.PHASE_PLAN to FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.PHASE_PROSE,
        def.PHASE_PLAN to FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.VALIDATION_REQUEST,
      ),
    def.PHASE_WRITE_HISTORY to
      setOf(
        def.PHASE_IMPLEMENT to FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.PHASE_PROSE,
        def.PHASE_IMPLEMENT to FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.BOUNDARY_CANDIDATES,
        def.PHASE_VALIDATE to FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.VALIDATION_RECEIPT,
        def.PHASE_BUILD to FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.BUILD_RECEIPT,
      ),
    def.PHASE_COMMIT_PUSH to
      setOf(
        def.PHASE_IMPLEMENT to FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.PHASE_PROSE,
        def.PHASE_IMPLEMENT to FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.COMMIT_REQUEST,
        def.PHASE_VALIDATE to FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.VALIDATION_RECEIPT,
        def.PHASE_BUILD to FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.BUILD_RECEIPT,
        def.PHASE_WRITE_HISTORY to FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.HISTORY_RECEIPT,
      ),
    def.PHASE_PR to
      setOf(
        def.PHASE_IMPLEMENT to FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.PHASE_PROSE,
        def.PHASE_IMPLEMENT to FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.PR_REQUEST,
        def.PHASE_COMMIT_PUSH to FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.COMMIT_RECEIPT,
      ),
  )
}

private fun assertConsumerProjectionEdges(
  consumer: String,
  expectedEdges: Set<Pair<String, String>>,
) {
  val def = FeatureTaskRuntimePhaseWorkflowDefinition
  val upstream =
    def.phaseDeclarations.getValue(consumer).projectionDeclarations.filter {
      it.sourceRef is FeatureTaskRuntimeHandoffSourceRef.UpstreamPhaseOutput
    }
  val actual =
    upstream.map { declaration ->
      val source = declaration.sourceRef as FeatureTaskRuntimeHandoffSourceRef.UpstreamPhaseOutput
      source.producingPhaseId to declaration.projectionContractId
    }.toSet()
  assertEquals(expectedEdges, actual, consumer)
  assertTrue(
    def.phaseDeclarations.getValue(consumer).projectionDeclarations.none {
      it.projectionContractId == def.UPSTREAM_PHASE_RECEIPT_CONTRACT_ID
    },
    "$consumer must not receive a complete upstream phase receipt",
  )
  upstream.forEach { declaration ->
    val isBuildReceipt =
      declaration.projectionContractId ==
        FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.BUILD_RECEIPT
    val producingBuild =
      (declaration.sourceRef as FeatureTaskRuntimeHandoffSourceRef.UpstreamPhaseOutput)
        .producingPhaseId == def.PHASE_BUILD
    val optionalBuildReceipt = isBuildReceipt && producingBuild
    assertEquals(
      !optionalBuildReceipt,
      declaration.required,
      "${declaration.projectionName} required flag",
    )
    assertTrue("phase_output_receipt" !in declaration.declaredFieldNames)
    assertTrue(
      declaration.declaredFieldNames.none {
        it in
          setOf(
            "summary", "raw_payload", "payload", "raw_prompt", "prompt", "transcript",
            "tool_output", "logs", "source_body", "diff_body", "telemetry", "prior_reports",
            "repair_history",
          )
      },
      "${declaration.projectionName} exposes forbidden context",
    )
  }
}

internal fun assertConsumerProjectionMatrixExact(definition: WorkflowDefinition) {
  val def = FeatureTaskRuntimePhaseWorkflowDefinition
  expectedConsumerProjectionMatrix().forEach { (consumer, expectedEdges) ->
    assertConsumerProjectionEdges(consumer, expectedEdges)
  }
  assertEquals(definition.stepIds.toSet(), def.phaseDeclarations.keys)
  assertTrue(def.phaseDeclarations.getValue(def.PHASE_PREPLAN).projectionDeclarations.isEmpty())
}
