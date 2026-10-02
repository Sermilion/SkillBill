package skillbill.workflow.engine

import skillbill.contracts.JsonCodec
import skillbill.contracts.review.ReviewVerificationSignalKeys
import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import skillbill.workflow.engine.model.RUNTIME_REPOSITORY_EVIDENCE_ARTIFACT_KEY
import skillbill.workflow.engine.model.WorkflowDefinition
import skillbill.workflow.engine.model.WorkflowInputProjection
import skillbill.workflow.engine.model.WorkflowInputProjectionDeclaration
import skillbill.workflow.engine.model.WorkflowLaunchProjectionArtifacts
import skillbill.workflow.engine.model.WorkflowSnapshotView

private fun collectionItemCount(value: Any?): Int =
  when (value) {
    is Map<*, *> -> value.size + value.values.sumOf(::collectionItemCount)
    is Iterable<*> -> value.sumOf(::collectionItemCount)
    is Array<*> -> value.sumOf(::collectionItemCount)
    else -> 1
  }

internal object WorkflowInputProjectionSelector {
  fun select(
    definition: WorkflowDefinition,
    snapshot: WorkflowSnapshotView,
    stepId: String,
    producerIteration: Int,
    resolvedRepositoryCheckpointIdentity: String,
  ): WorkflowInputProjection {
    val declaration = declaration(definition, stepId, resolvedRepositoryCheckpointIdentity)
    val selected =
      selectArtifacts(
        definition,
        snapshot,
        stepId,
        declaration,
        resolvedRepositoryCheckpointIdentity,
      )
    validateForbiddenArtifacts(definition, stepId, declaration, selected)
    val bytes = validateBudgets(definition, stepId, declaration, selected)
    val repositoryCheckpoint =
      validateRepositoryCheckpoint(
        definition,
        stepId,
        declaration,
        selected,
        resolvedRepositoryCheckpointIdentity,
      )
    return WorkflowInputProjection(
      stepId = stepId,
      producerIteration = producerIteration,
      repositoryCheckpoint = repositoryCheckpoint,
      artifacts = WorkflowLaunchProjectionArtifacts.from(selected),
      utf8Bytes = bytes,
    )
  }

  private fun declaration(
    definition: WorkflowDefinition,
    stepId: String,
    resolvedRepositoryCheckpointIdentity: String,
  ): WorkflowInputProjectionDeclaration {
    if (resolvedRepositoryCheckpointIdentity.isBlank()) {
      reject(definition, "projection for step '$stepId' has no runtime-resolved repository checkpoint")
    }
    return definition.inputProjectionsByStep[stepId]
      ?: reject(definition, "missing input projection for step '$stepId'")
  }

  private fun selectArtifacts(
    definition: WorkflowDefinition,
    snapshot: WorkflowSnapshotView,
    stepId: String,
    declaration: WorkflowInputProjectionDeclaration,
    resolvedRepositoryCheckpointIdentity: String,
  ): Map<String, Any?> {
    val missing =
      declaration.requiredArtifactKeys.filterNot { artifactKey ->
        artifactKey == RUNTIME_REPOSITORY_EVIDENCE_ARTIFACT_KEY || snapshot.artifacts.containsKey(artifactKey)
      }
    if (missing.isNotEmpty()) {
      reject(
        definition,
        "projection for step '$stepId' is missing required artifact keys: ${missing.joinToString()}",
      )
    }
    return declaration.requiredArtifactKeys.associateWith { artifactKey ->
      if (artifactKey == RUNTIME_REPOSITORY_EVIDENCE_ARTIFACT_KEY) {
        mapOf(ReviewVerificationSignalKeys.REPOSITORY_CHECKPOINT_FINGERPRINT to resolvedRepositoryCheckpointIdentity)
      } else {
        projectArtifact(
          definition = definition,
          stepId = stepId,
          artifactKey = artifactKey,
          value = snapshot.artifacts[artifactKey],
          projectedFields = declaration.projectedFieldsByArtifactKey[artifactKey],
        )
      }
    }
  }

  private fun validateForbiddenArtifacts(
    definition: WorkflowDefinition,
    stepId: String,
    declaration: WorkflowInputProjectionDeclaration,
    selected: Map<String, Any?>,
  ) {
    val forbidden =
      selected.keys.intersect(declaration.forbiddenArtifactKeys) +
        selected.values.flatMapTo(mutableSetOf()) { nestedKeys(it) }.intersect(declaration.forbiddenArtifactKeys)
    if (forbidden.isNotEmpty()) {
      reject(
        definition,
        "projection for step '$stepId' contains forbidden artifact keys: ${forbidden.sorted().joinToString()}",
      )
    }
  }

  private fun validateBudgets(
    definition: WorkflowDefinition,
    stepId: String,
    declaration: WorkflowInputProjectionDeclaration,
    selected: Map<String, Any?>,
  ): Int {
    val itemCount = selected.values.sumOf(::collectionItemCount)
    if (itemCount > declaration.maxCollectionItems) {
      reject(definition, "projection for step '$stepId' exceeds its collection-item budget")
    }
    val bytes = JsonCodec.mapToJsonString(selected).toByteArray(Charsets.UTF_8).size
    if (bytes > declaration.maxUtf8Bytes) {
      reject(definition, "projection for step '$stepId' exceeds its UTF-8 byte budget")
    }
    return bytes
  }

  private fun validateRepositoryCheckpoint(
    definition: WorkflowDefinition,
    stepId: String,
    declaration: WorkflowInputProjectionDeclaration,
    selected: Map<String, Any?>,
    resolvedRepositoryCheckpointIdentity: String,
  ): Any {
    val repositoryCheckpoint =
      selected[declaration.repositoryCheckpointArtifactKey]
        ?: reject(definition, "projection for step '$stepId' has null repository checkpoint evidence")
    val checkpoint =
      repositoryCheckpoint as? Map<*, *>
        ?: reject(definition, "projection for step '$stepId' repository checkpoint evidence is not typed")
    val checkpointIdentity =
      checkpoint[ReviewVerificationSignalKeys.REPOSITORY_CHECKPOINT_FINGERPRINT] ?: checkpoint["checkpoint"]
        ?: reject(definition, "projection for step '$stepId' repository checkpoint evidence has no identity")
    if (checkpointIdentity != resolvedRepositoryCheckpointIdentity) {
      reject(
        definition,
        "projection for step '$stepId' repository checkpoint evidence does not match the runtime-resolved checkpoint",
      )
    }
    val claimedIdentity =
      checkpoint[ReviewVerificationSignalKeys.REPOSITORY_CHECKPOINT] ?: checkpoint["checkpoint"]
        ?: checkpoint[ReviewVerificationSignalKeys.REPOSITORY_CHECKPOINT_FINGERPRINT]
    if (claimedIdentity != checkpointIdentity) {
      reject(definition, "projection for step '$stepId' repository checkpoint evidence is stale or mismatched")
    }
    val staleArtifactCheckpoint =
      selected
        .filterKeys { it != declaration.repositoryCheckpointArtifactKey }
        .values
        .mapNotNull(::nestedRepositoryCheckpointIdentity)
        .firstOrNull { it != checkpointIdentity }
    if (staleArtifactCheckpoint != null) {
      reject(
        definition,
        "projection for step '$stepId' artifact checkpoint does not match authoritative repository evidence",
      )
    }
    return repositoryCheckpoint
  }

  private fun projectArtifact(
    definition: WorkflowDefinition,
    stepId: String,
    artifactKey: String,
    value: Any?,
    projectedFields: Set<String>?,
  ): Any? {
    if (projectedFields == null) return value
    val typed =
      value as? Map<*, *>
        ?: reject(definition, "projection for step '$stepId' artifact '$artifactKey' is not typed")
    val missingFields = projectedFields.filterNot(typed::containsKey)
    if (missingFields.isNotEmpty()) {
      reject(
        definition,
        "projection for step '$stepId' artifact '$artifactKey' is missing required fields: " +
          missingFields.sorted().joinToString(),
      )
    }
    return projectedFields.associateWith(typed::get)
  }

  private fun nestedKeys(value: Any?): Set<String> =
    when (value) {
      is Map<*, *> ->
        value.entries.flatMapTo(mutableSetOf()) { (key, nested) ->
          buildSet {
            if (key is String) add(key)
            addAll(nestedKeys(nested))
          }
        }
      is Iterable<*> -> value.flatMapTo(mutableSetOf(), ::nestedKeys)
      is Array<*> -> value.flatMapTo(mutableSetOf(), ::nestedKeys)
      else -> emptySet()
    }

  private fun nestedRepositoryCheckpointIdentity(value: Any?): Any? {
    val typed = value as? Map<*, *> ?: return null
    val nested = typed[ReviewVerificationSignalKeys.REPOSITORY_CHECKPOINT] as? Map<*, *> ?: return null
    return nested[ReviewVerificationSignalKeys.REPOSITORY_CHECKPOINT_FINGERPRINT] ?: nested["checkpoint"]
  }

  private fun reject(
    definition: WorkflowDefinition,
    detail: String,
  ): Nothing = throw InvalidWorkflowStateSchemaError("${definition.workflowName}: $detail")
}
