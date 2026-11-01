package skillbill.application.workflow.persist

import skillbill.application.workflow.model.BuildFeatureTaskExecutionIdentityArgs
import skillbill.application.workflow.model.WorkflowFamilyKind
import skillbill.workflow.model.FeatureTaskExecutionIdentity
import skillbill.workflow.model.FeatureTaskExecutionIdentityPolicy
import skillbill.workflow.model.FeatureTaskWorkflowMode

internal const val WORKFLOW_ID_SUFFIX_LENGTH: Int = 4
internal val FEATURE_TASK_FAMILY_KINDS = setOf(WorkflowFamilyKind.TASK_RUNTIME)
internal const val INCOMPLETE_FEATURE_TASK_IDENTITY_ERROR =
  "Feature-task workflows must be opened through openFeatureTask with complete immutable execution identity."
internal const val SUFFIX_CHARS: String = "abcdefghijklmnopqrstuvwxyz0123456789"

internal fun hasIncompleteFeatureTaskIdentity(
  kind: WorkflowFamilyKind,
  hasIdentityCoordinates: Boolean,
  issueKey: String?,
  repositoryIdentity: String?,
  governedSpecPath: String?,
): Boolean =
  kind in FEATURE_TASK_FAMILY_KINDS &&
    hasIdentityCoordinates &&
    listOf(issueKey, repositoryIdentity, governedSpecPath).any { it == null }

internal fun buildFeatureTaskExecutionIdentity(
  args: BuildFeatureTaskExecutionIdentityArgs,
): FeatureTaskExecutionIdentity? {
  val kind = args.kind
  val hasIdentityCoordinates = args.hasIdentityCoordinates
  if (kind !in FEATURE_TASK_FAMILY_KINDS || !hasIdentityCoordinates) return null
  val requiredRepositoryIdentity = requireNotNull(args.repositoryIdentity)
  val normalizedIssueKey =
    FeatureTaskExecutionIdentityPolicy.validateLookupRequest(
      requireNotNull(args.issueKey),
      requiredRepositoryIdentity,
    )
  return FeatureTaskExecutionIdentity(
    workflowId = args.workflowId,
    normalizedIssueKey = normalizedIssueKey,
    repositoryIdentity = requiredRepositoryIdentity,
    governedSpecPath = requireNotNull(args.governedSpecPath),
    mode = FeatureTaskWorkflowMode.RUNTIME,
    routeScope = args.routeScope,
  ).also(FeatureTaskExecutionIdentityPolicy::validate)
}
