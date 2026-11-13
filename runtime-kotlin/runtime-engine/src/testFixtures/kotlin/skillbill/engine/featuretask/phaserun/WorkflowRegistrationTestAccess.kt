package skillbill.engine.featuretask.phaserun

import java.nio.file.Path

fun StandalonePhaseRegistrationFactory.workflowRegistrationForTest(
  repoRoot: Path,
  issueKey: String?,
  workflowId: String,
  workBranch: String? = null,
) = workflowRegistration(repoRoot, issueKey, workflowId, WorkflowStatusLaunch(workBranch = workBranch))
