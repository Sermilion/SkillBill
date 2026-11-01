package skillbill.engine.featuretask.runner

fun disposition(
  findingId: String,
  disposition: String,
  reason: String? = null,
): String {
  val reasonField = reason?.let { ""","reason":"$it"""" }.orEmpty()
  return """{"finding_id":"$findingId","disposition":"$disposition","boundary_context_unavailable":true$reasonField}"""
}
