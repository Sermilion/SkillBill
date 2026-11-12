package skillbill.engine.apprun.model

public enum class AppRunRefusalReason(public val wireValue: String) {
  UNKNOWN_GOAL("unknown_goal"),
  CI_REPAIR_RUNNING("ci_repair_running"),
  NOT_RUNNABLE("not_runnable"),
  NO_RECIPE("no_recipe"),
  PACK_INCOMPATIBLE("pack_incompatible"),
  CHECKOUT_UNAVAILABLE("checkout_unavailable"),
  BRANCH_MISSING("branch_missing"),
  WORKTREE_FAILED("worktree_failed"),
  INVALID_CONFIG("invalid_config"),
}
