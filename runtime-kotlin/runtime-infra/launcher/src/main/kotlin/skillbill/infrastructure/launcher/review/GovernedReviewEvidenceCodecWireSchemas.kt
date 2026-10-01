package skillbill.infrastructure.launcher.review

import skillbill.contracts.review.GovernedReviewEvidenceContracts
import skillbill.review.context.model.hunk.ReviewEvidenceLimits

internal object GovernedReviewEvidenceCodecWireSchemas {
  fun toolSpecs(): List<Map<String, Any?>> =
    listOf(
      toolSpec(
        GovernedReviewEvidenceContracts.READ_EVIDENCE,
        "Read assigned review evidence selectors in batch.",
        linkedMapOf(
          "requests" to
            linkedMapOf(
              "type" to "array",
              "minItems" to 1,
              "maxItems" to REVIEW_EVIDENCE_BATCH_SIZE,
              "items" to
                linkedMapOf(
                  "type" to "object",
                  "properties" to
                    linkedMapOf(
                      "selector" to stringProperty("Exact selector for the assigned evidence surface."),
                      "path" to stringProperty("Repository-relative path inside the assignment surface."),
                      "reachability_reason" to stringProperty("Why the path is reachable from the assignment."),
                      "expansion_id" to
                        stringProperty(
                          "Identifier returned by ${GovernedReviewEvidenceContracts.REQUEST_EXPANSION}.",
                        ),
                    ),
                  "required" to listOf("path"),
                  "additionalProperties" to false,
                ),
            ),
        ),
        listOf("requests"),
      ),
      toolSpec(
        GovernedReviewEvidenceContracts.REQUEST_EXPANSION,
        "Request authorization to read a path beyond the assigned hunks.",
        linkedMapOf(
          "lane" to stringProperty("Original source lane from assignment ownership; defaults to the bound lane."),
          "path" to stringProperty("Repository-relative path to expand to."),
          "reachability_reason" to stringProperty("Why the assignment reaches this path."),
        ),
        listOf("path", "reachability_reason"),
      ),
    )

  private fun toolSpec(
    name: String,
    description: String,
    properties: Map<String, Any?>,
    required: List<String>,
  ): Map<String, Any?> =
    linkedMapOf(
      "name" to name,
      "description" to description,
      GovernedReviewEvidencePayloadKeys.ANNOTATIONS to
        linkedMapOf(
          GovernedReviewEvidencePayloadKeys.READ_ONLY_HINT to true,
          GovernedReviewEvidencePayloadKeys.DESTRUCTIVE_HINT to false,
          GovernedReviewEvidencePayloadKeys.OPEN_WORLD_HINT to false,
        ),
      "inputSchema" to
        linkedMapOf(
          "type" to "object",
          "properties" to properties,
          "required" to required,
          "additionalProperties" to false,
        ),
    )

  private fun stringProperty(description: String): Map<String, Any?> =
    linkedMapOf(
      "type" to "string",
      "minLength" to 1,
      "maxLength" to ReviewEvidenceLimits.FIELD_CHARACTERS,
      "description" to description,
    )
}
