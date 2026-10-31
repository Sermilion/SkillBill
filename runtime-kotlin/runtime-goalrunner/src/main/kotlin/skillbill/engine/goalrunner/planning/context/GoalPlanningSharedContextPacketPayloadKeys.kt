package skillbill.engine.goalrunner.planning.context

import skillbill.contracts.decomposition.DecompositionPlanningPayloadKeys

public object GoalPlanningSharedContextPacketPayloadKeys {
  public const val PACKET_VERSION: String = "packet_version"
  public const val REPOSITORY_IDENTITY: String = "repository_identity"
  public const val NORMALIZED_ISSUE_KEY: String = "normalized_issue_key"
  public val PARENT_SPEC_PATH: String = DecompositionPlanningPayloadKeys.PARENT_SPEC_PATH
  public const val PARENT_SPEC: String = "parent_spec"
  internal const val DECOMPOSITION_MANIFEST: String = "decomposition_manifest"
  internal const val BOUNDARY_MEMORY: String = "boundary_memory"
  public const val CATALOG: String = "catalog"
  public const val TRUNCATED: String = "truncated"
  internal const val HEADING_ID: String = "heading_id"
  public const val SOURCE_PATH: String = "source_path"
  public const val KIND: String = "kind"
  public const val HEADING: String = "heading"
  internal const val VALIDATION_GUIDANCE: String = "validation_guidance"
  internal const val ORDERED_SUBTASKS: String = "ordered_subtasks"
  internal const val PLANNING_DISPOSITION: String = "planning_disposition"
  internal const val INTEGRITY_SHA256: String = "integrity_sha256"
  public const val PLATFORM_PACKS: String = "platform_packs"
}
