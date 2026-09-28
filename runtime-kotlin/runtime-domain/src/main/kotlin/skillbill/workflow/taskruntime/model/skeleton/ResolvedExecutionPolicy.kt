package skillbill.workflow.taskruntime.model.skeleton

data class ResolvedExecutionPolicy(
  val id: String,
  val semanticRevision: Int,
  val semanticDigest: String,
) {
  init {
    require(id.length in 1..128 && id.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:/-]*")))
    require(semanticRevision > 0)
    require(semanticDigest.matches(Regex("[0-9a-f]{64}")))
  }
}
