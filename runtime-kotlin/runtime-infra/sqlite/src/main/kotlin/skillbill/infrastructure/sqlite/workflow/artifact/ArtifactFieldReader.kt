package skillbill.infrastructure.sqlite.workflow.artifact

import skillbill.error.shellcontent.invalidWorkflowStateSchemaError
import skillbill.workflow.model.persistence.artifact.asExactIntOrNull

internal class ArtifactFieldReader(
  private val map: Map<String, Any?>,
) {
  fun requiredString(key: String): String {
    val value = map[key] ?: throw invalidWorkflowStateSchemaError("Artifact map is missing required field '$key'.")
    return (value as? String)?.takeIf(String::isNotBlank)
      ?: throw invalidWorkflowStateSchemaError("Artifact field '$key' must decode to a non-blank string.")
  }

  fun optionalString(key: String): String? {
    val value = map[key] ?: return null
    return (value as? String)?.takeIf(String::isNotBlank)
      ?: throw invalidWorkflowStateSchemaError("Artifact field '$key' must decode to a non-blank string when present.")
  }

  fun requiredInt(key: String): Int {
    val value = map[key] ?: throw invalidWorkflowStateSchemaError("Artifact map is missing required integer '$key'.")
    return value.asExactIntOrNull()
      ?: throw invalidWorkflowStateSchemaError("Artifact field '$key' must decode to an integer.")
  }

  fun requiredBoolean(key: String): Boolean {
    val value = map[key] ?: throw invalidWorkflowStateSchemaError("Artifact map is missing required boolean '$key'.")
    return value as? Boolean
      ?: throw invalidWorkflowStateSchemaError("Artifact field '$key' must decode to a boolean.")
  }

  fun rejectUnknownKeys(
    allowed: Set<String>,
    artifactName: String,
  ) {
    map.keys.firstOrNull { it !in allowed }?.let { key ->
      throw invalidWorkflowStateSchemaError("$artifactName field '$key' is not supported.")
    }
  }
}
