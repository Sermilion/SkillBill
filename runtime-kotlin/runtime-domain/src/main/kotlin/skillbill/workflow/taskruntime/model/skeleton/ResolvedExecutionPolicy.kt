package skillbill.workflow.taskruntime.model.skeleton

data class ResolvedExecutionPolicy(
  val id: String,
  val semanticRevision: Int,
  val semanticDigest: String,
) {
  init {
    val reason = violation(id, semanticRevision, semanticDigest)
    require(reason == null) { reason.orEmpty() }
  }

  companion object {
    fun violation(
      id: String,
      semanticRevision: Int,
      semanticDigest: String,
    ): String? =
      if (
        id.length in 1..MAX_POLICY_ID_LENGTH && id.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:/-]*")) &&
        semanticRevision > 0 && semanticDigest.matches(Regex("[0-9a-f]{64}"))
      ) null else "Failed requirement."
  }
}

private const val MAX_POLICY_ID_LENGTH = 128
