package skillbill.engine.featuretask.model.core

import skillbill.ports.goalrunner.verification.model.GoalVerificationBoundaryDiscovery

public data class FeatureTaskRuntimeFindingBoundaryMemoryRequest(
  public val findingId: String,
  public val findingPaths: List<String>,
)

public data class FeatureTaskRuntimeFindingBoundaryMemorySection(
  public val findingId: String,
  public val discovery: GoalVerificationBoundaryDiscovery,
)
