package skillbill.engine.featuretask.lifecycle.branch

import skillbill.application.decomposition.baseBranch

fun branchSetupBlockedReason(error: String): String =
  "Feature-task-runtime could not establish a feature branch: reading the current branch failed" +
    error.detailSuffix() + " Refusing to run file-mutating phases on the default branch."

fun branchSetupCreateBlockedReason(
  branch: String,
  baseBranch: String,
  error: String,
): String =
  "Feature-task-runtime could not create/switch to feature branch '$branch' from '$baseBranch'" +
    error.detailSuffix() + " Refusing to run file-mutating phases on the default branch."

fun branchSetupReattachBlockedReason(
  branch: String,
  currentBranch: String,
  error: String,
): String =
  "Feature-task-runtime could not re-attach to the persisted feature branch '$branch' from " +
    "'$currentBranch'" + error.detailSuffix() +
    " Refusing to run file-mutating phases on the default branch."

fun branchSetupReattachMissingReason(
  branch: String,
  currentBranch: String,
): String =
  "Feature-task-runtime could not re-attach to the persisted feature branch '$branch': it no " +
    "longer exists in the repository (HEAD is on '$currentBranch'). Refusing to create a new, " +
    "divergent branch in its place or run file-mutating phases on the default branch; restore " +
    "or recreate '$branch' before resuming."

fun branchSetupReattachExistenceUnreadableReason(
  branch: String,
  currentBranch: String,
  error: String,
): String =
  "Feature-task-runtime could not verify whether the persisted feature branch '$branch' still " +
    "exists (HEAD is on '$currentBranch')" + error.detailSuffix() +
    " Refusing to re-attach or run file-mutating phases until existence can be confirmed."

fun branchSetupReattachProtectedReason(branch: String): String =
  "Feature-task-runtime resolved persisted branch '$branch' is a protected branch. Refusing to " +
    "run file-mutating phases on a protected branch."

fun branchSetupNotPersistedBlockedReason(branch: String): String =
  "Feature-task-runtime could not durably record the resolved feature branch '$branch' " +
    "(the workflow row is absent), so a resume could not re-attach to it. Refusing to run " +
    "file-mutating phases on the default branch."

fun branchSetupDeriveBlockedReason(reason: String): String =
  "Feature-task-runtime could not establish a feature branch: $reason Refusing to run " +
    "file-mutating phases on the default branch."

private fun String.detailSuffix(): String = if (isBlank()) "." else " ($this)."
