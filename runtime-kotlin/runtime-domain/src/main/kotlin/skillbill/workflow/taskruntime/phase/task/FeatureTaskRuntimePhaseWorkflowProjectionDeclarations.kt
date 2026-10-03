package skillbill.workflow.taskruntime.phase.task

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.decomposition.DecompositionPlanningPayloadKeys
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeMeasuredFactKeys
import skillbill.workflow.engine.model.WorkflowDefinition
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeRepositoryCheckpointPolicy
import skillbill.workflow.taskruntime.model.handoff.PhaseHandoffProjectionDeclaration
import skillbill.workflow.taskruntime.model.handoff.PhaseHandoffProjectionDelivery
import skillbill.workflow.taskruntime.model.handoff.PhaseHandoffProjectionShape
import skillbill.workflow.taskruntime.model.handoff.PhaseHandoffProjectionTemplate
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseDeclaration
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffProjectionBudget
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffPromptVisibility
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffSourceRef
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePlanningProjectionContract
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeSharedReviewEvidenceReference

internal object FeatureTaskRuntimePhaseWorkflowProjectionDeclarations {
  private fun upstreamPlanningProjection(spec: UpstreamPlanningProjectionSpec): PhaseHandoffProjectionDeclaration =
    PhaseHandoffProjectionDeclaration(
      consumerPhaseId = spec.consumerPhaseId,
      sourceRef = spec.sourceRef,
      shape =
        PhaseHandoffProjectionShape(
          projectionName = spec.projectionName,
          projectionContractId = spec.projectionContractId,
          projectionContractVersion = spec.projectionContractVersion,
          promptVisibility = FeatureTaskRuntimeHandoffPromptVisibility.PROMPT_VISIBLE,
          budget = FeatureTaskRuntimeHandoffProjectionBudget.PLANNING_PROJECTION,
          declaredFieldNames = spec.declaredFieldNames,
        ),
      delivery = spec.delivery,
    )

  fun phaseProjection(template: PhaseHandoffProjectionTemplate): PhaseHandoffProjectionDeclaration =
    upstreamPlanningProjection(
      UpstreamPlanningProjectionSpec(
        consumerPhaseId = template.consumerPhaseId,
        sourceRef = FeatureTaskRuntimeHandoffSourceRef.UpstreamPhaseOutput(template.producingPhaseId),
        projectionName = template.name,
        projectionContractId = template.contractId,
        declaredFieldNames = template.fields,
        delivery =
          PhaseHandoffProjectionDelivery(
            checkpointPolicy = template.checkpointPolicy,
            required = template.required,
          ),
        projectionContractVersion =
          template.contractVersion ?: FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.VERSION,
      ),
    )

  fun phaseProseDeclaration(
    consumerPhaseId: String,
    producingPhaseId: String = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN,
    checkpointPolicy: FeatureTaskRuntimeRepositoryCheckpointPolicy =
      FeatureTaskRuntimeRepositoryCheckpointPolicy.NOT_REQUIRED,
  ): PhaseHandoffProjectionDeclaration =
    upstreamPlanningProjection(
      UpstreamPlanningProjectionSpec(
        consumerPhaseId = consumerPhaseId,
        sourceRef = FeatureTaskRuntimeHandoffSourceRef.UpstreamPhaseOutput(producingPhaseId),
        projectionName = "${producingPhaseId}_prose",
        projectionContractId = FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.PHASE_PROSE,
        declaredFieldNames = listOf(SharedPayloadKeys.VALUE, "directive"),
        delivery =
          PhaseHandoffProjectionDelivery(
            checkpointPolicy = checkpointPolicy,
            required = true,
          ),
      ),
    )

  fun sharedReviewEvidenceDeclaration(consumerPhaseId: String): PhaseHandoffProjectionDeclaration =
    upstreamPlanningProjection(
      UpstreamPlanningProjectionSpec(
        consumerPhaseId = consumerPhaseId,
        sourceRef = FeatureTaskRuntimeHandoffSourceRef.SharedReviewEvidence,
        projectionName = FeatureTaskRuntimePhaseWorkflowDefinition.SHARED_REVIEW_EVIDENCE_PROJECTION_NAME,
        projectionContractId = FeatureTaskRuntimePlanningProjectionContract.SHARED_REVIEW_EVIDENCE_ID,
        declaredFieldNames = FeatureTaskRuntimeSharedReviewEvidenceReference.DECLARED_FIELD_NAMES,
        delivery =
          PhaseHandoffProjectionDelivery(
            checkpointPolicy = FeatureTaskRuntimeRepositoryCheckpointPolicy.REFRESH_FROM_REPOSITORY,
            required = false,
          ),
      ),
    )

  fun repairLedgerDeclaration(consumerPhaseId: String): PhaseHandoffProjectionDeclaration =
    upstreamPlanningProjection(
      UpstreamPlanningProjectionSpec(
        consumerPhaseId = consumerPhaseId,
        sourceRef = FeatureTaskRuntimeHandoffSourceRef.RepairLedger,
        projectionName = FeatureTaskRuntimePhaseWorkflowDefinition.REPAIR_LEDGER_PROJECTION_NAME,
        projectionContractId = FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.REPAIR_LEDGER,
        declaredFieldNames = listOf(FeatureTaskRuntimePhaseWorkflowDefinition.REPAIR_LEDGER_PROJECTION_NAME),
        delivery = PhaseHandoffProjectionDelivery(required = false),
      ),
    )

  val phaseProjectionMatrix: Map<String, List<PhaseHandoffProjectionDeclaration>> =
    mapOf(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN to emptyList(),
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN to
        listOf(
          phaseProseDeclaration(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN),
        ),
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT to emptyList(),
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_SIMPLIFY to
        listOf(
          phaseProjection(
            PhaseHandoffProjectionTemplate(
              consumerPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_SIMPLIFY,
              producingPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
              name = "subtask_scope",
              contractId = FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.CHANGE_RECEIPT,
              fields =
                listOf(
                  "changed_paths",
                  "repository_checkpoint",
                ),
              checkpointPolicy = FeatureTaskRuntimeRepositoryCheckpointPolicy.REFRESH_FROM_REPOSITORY,
              required = true,
            ),
          ),
        ),
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT_PLAN_FIX to
        listOf(
          phaseProseDeclaration(
            FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT_PLAN_FIX,
            FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
          ),
          phaseProseDeclaration(
            FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT_PLAN_FIX,
            FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
          ),
        ),
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT_IMPLEMENT_FIX to
        listOf(
          phaseProseDeclaration(
            FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT_IMPLEMENT_FIX,
            FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT_PLAN_FIX,
          ),
          phaseProseDeclaration(
            FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT_IMPLEMENT_FIX,
            FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
          ),
          phaseProseDeclaration(
            FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT_IMPLEMENT_FIX,
            FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
          ),
        ),
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT to
        listOf(
          phaseProseDeclaration(
            FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
            FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
          ),
          phaseProseDeclaration(
            FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
            FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
          ),
          phaseProseDeclaration(
            FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
            FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_SIMPLIFY,
          ),
          sharedReviewEvidenceDeclaration(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT),
        ),
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX to
        listOf(
          phaseProjection(
            PhaseHandoffProjectionTemplate(
              consumerPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX,
              producingPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS,
              name = "review_repair_request",
              contractId = FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.REVIEW_REPAIR_REQUEST,
              fields = listOf(SharedPayloadKeys.VALUE, "repository_checkpoint"),
              checkpointPolicy = FeatureTaskRuntimeRepositoryCheckpointPolicy.MUST_MATCH,
              required = true,
            ),
          ),
        ),
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS to
        listOf(
          phaseProjection(
            PhaseHandoffProjectionTemplate(
              consumerPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS,
              producingPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
              name = "review_findings_for_verification",
              contractId =
                FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.FINDINGS_VERIFICATION_INPUT,
              fields = listOf(SharedPayloadKeys.VALUE, "repository_checkpoint"),
              checkpointPolicy = FeatureTaskRuntimeRepositoryCheckpointPolicy.REFRESH_FROM_REPOSITORY,
              required = true,
            ),
          ),
        ),
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW to
        listOf(
          sharedReviewEvidenceDeclaration(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW),
        ),
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE to
        listOf(
          phaseProseDeclaration(
            FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
            FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
          ),
          phaseProjection(
            PhaseHandoffProjectionTemplate(
              consumerPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
              producingPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
              name = "validation_request",
              contractId = FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.VALIDATION_REQUEST,
              fields =
                listOf(
                  "changed_paths",
                  "repository_checkpoint",
                ),
              checkpointPolicy = FeatureTaskRuntimeRepositoryCheckpointPolicy.REFRESH_FROM_REPOSITORY,
              required = true,
            ),
          ),
        ),
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD to
        listOf(
          phaseProseDeclaration(
            FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD,
            FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
          ),
          phaseProjection(
            PhaseHandoffProjectionTemplate(
              consumerPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD,
              producingPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
              name = "validation_request",
              contractId = FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.VALIDATION_REQUEST,
              fields =
                listOf(
                  "changed_paths",
                  "repository_checkpoint",
                ),
              checkpointPolicy = FeatureTaskRuntimeRepositoryCheckpointPolicy.REFRESH_FROM_REPOSITORY,
              required = true,
            ),
          ),
        ),
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY to
        listOf(
          phaseProseDeclaration(
            FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY,
            FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
          ),
          phaseProjection(
            PhaseHandoffProjectionTemplate(
              consumerPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY,
              producingPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
              name = "boundary_candidates",
              contractId = FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.BOUNDARY_CANDIDATES,
              fields = listOf("changed_paths", "boundary_candidates"),
              checkpointPolicy = FeatureTaskRuntimeRepositoryCheckpointPolicy.REFRESH_FROM_REPOSITORY,
              required = true,
            ),
          ),
          phaseProjection(
            PhaseHandoffProjectionTemplate(
              consumerPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY,
              producingPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
              name = "validation_receipt",
              contractId = FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.VALIDATION_RECEIPT,
              fields = listOf("value"),
              checkpointPolicy = FeatureTaskRuntimeRepositoryCheckpointPolicy.REFRESH_FROM_REPOSITORY,
              required = true,
            ),
          ),
          phaseProjection(
            PhaseHandoffProjectionTemplate(
              consumerPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY,
              producingPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD,
              name = "build_receipt",
              contractId = FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.BUILD_RECEIPT,
              fields = listOf(SharedPayloadKeys.VALUE),
              checkpointPolicy = FeatureTaskRuntimeRepositoryCheckpointPolicy.REFRESH_FROM_REPOSITORY,
              required = false,
            ),
          ),
        ),
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH to
        listOf(
          phaseProseDeclaration(
            FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH,
            FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
          ),
          phaseProjection(
            PhaseHandoffProjectionTemplate(
              consumerPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH,
              producingPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
              name = "commit_request",
              contractId = FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.COMMIT_REQUEST,
              fields =
                listOf(
                  "path_inventory",
                  "required_inclusions",
                  "branch_identity",
                  "gate_attestations",
                  "repository_checkpoint",
                ),
              checkpointPolicy = FeatureTaskRuntimeRepositoryCheckpointPolicy.REFRESH_FROM_REPOSITORY,
              required = true,
            ),
          ),
          phaseProjection(
            PhaseHandoffProjectionTemplate(
              consumerPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH,
              producingPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
              name = "validation_receipt",
              contractId = FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.VALIDATION_RECEIPT,
              fields = listOf("value"),
              checkpointPolicy = FeatureTaskRuntimeRepositoryCheckpointPolicy.REFRESH_FROM_REPOSITORY,
              required = true,
            ),
          ),
          phaseProjection(
            PhaseHandoffProjectionTemplate(
              consumerPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH,
              producingPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD,
              name = "build_receipt",
              contractId = FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.BUILD_RECEIPT,
              fields = listOf(SharedPayloadKeys.VALUE),
              checkpointPolicy = FeatureTaskRuntimeRepositoryCheckpointPolicy.REFRESH_FROM_REPOSITORY,
              required = false,
            ),
          ),
          phaseProjection(
            PhaseHandoffProjectionTemplate(
              consumerPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH,
              producingPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY,
              name = "history_receipt",
              contractId = FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.HISTORY_RECEIPT,
              fields =
                listOf(
                  FeatureTaskRuntimeMeasuredFactKeys.CHANGED_PATHS,
                  FeatureTaskRuntimeMeasuredFactKeys.HISTORY_WRITTEN,
                  FeatureTaskRuntimeMeasuredFactKeys.DECISIONS_RECORDED,
                ),
              checkpointPolicy = FeatureTaskRuntimeRepositoryCheckpointPolicy.NOT_REQUIRED,
              required = true,
              contractVersion =
                FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.HISTORY_RECEIPT_VERSION,
            ),
          ),
        ),
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR to
        listOf(
          phaseProseDeclaration(
            FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR,
            FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
          ),
          phaseProjection(
            PhaseHandoffProjectionTemplate(
              consumerPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR,
              producingPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
              name = "pr_request",
              contractId = FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.PR_REQUEST,
              fields =
                listOf(
                  "changed_paths",
                  "validation_summary",
                  DecompositionPlanningPayloadKeys.BASE_BRANCH,
                  "diff_reference",
                ),
              checkpointPolicy = FeatureTaskRuntimeRepositoryCheckpointPolicy.REFRESH_FROM_REPOSITORY,
              required = true,
            ),
          ),
          phaseProjection(
            PhaseHandoffProjectionTemplate(
              consumerPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR,
              producingPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH,
              name = "commit_receipt",
              contractId = FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.COMMIT_RECEIPT,
              fields = listOf(SharedPayloadKeys.VALUE),
              checkpointPolicy = FeatureTaskRuntimeRepositoryCheckpointPolicy.NOT_REQUIRED,
              required = true,
            ),
          ),
        ),
    )

  fun runtimeProjectorProducerPhaseIds(consumerPhaseId: String): Set<String> =
    when (consumerPhaseId) {
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX ->
        setOf(
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
        )
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE ->
        setOf(
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
        )
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD ->
        setOf(
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
        )
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY ->
        setOf(
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD,
        )
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH ->
        setOf(
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD,
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY,
        )
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR ->
        setOf(
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH,
        )
      else -> emptySet()
    }

  fun phaseDeclarations(definition: WorkflowDefinition): Map<String, FeatureTaskRuntimePhaseDeclaration> =
    definition.stepIds.associateWith { phaseId ->
      FeatureTaskRuntimePhaseDeclaration(
        phaseId = phaseId,
        projectionDeclarations =
          requireNotNull(phaseProjectionMatrix[phaseId]) {
            "No closed-world projection declaration for runtime phase '$phaseId'."
          },
        derivedContextKeys =
          when (phaseId) {
            FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW ->
              listOf(
                FeatureTaskRuntimePhaseWorkflowDefinition.DERIVED_CONTEXT_DIFF,
              )
            FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR ->
              listOf(
                FeatureTaskRuntimePhaseWorkflowDefinition.DERIVED_CONTEXT_PR_BRANCH_DIFF,
              )
            FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT ->
              listOf(
                FeatureTaskRuntimePhaseWorkflowDefinition.DERIVED_CONTEXT_SCOPED_REPOSITORY_STATE,
              )
            FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_SIMPLIFY ->
              listOf(
                FeatureTaskRuntimePhaseWorkflowDefinition.DERIVED_CONTEXT_DIFF,
              )
            else -> emptyList()
          },
      )
    }
}
