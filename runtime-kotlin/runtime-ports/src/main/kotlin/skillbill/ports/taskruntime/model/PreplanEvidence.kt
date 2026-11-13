package skillbill.ports.taskruntime.model

import java.nio.file.Path

data class PreplanEvidenceReference(
  val path: String,
  val startLine: Int? = null,
  val endLine: Int? = null,
) {
  init {
    require(path.isNotBlank())
    require(startLine == null || startLine >= 1)
    require(endLine == null || (startLine != null && endLine >= startLine))
  }

  val label: String
    get() =
      when {
        startLine == null -> path
        endLine == null || endLine == startLine -> "$path:$startLine"
        else -> "$path:$startLine-$endLine"
      }
}

data class PreplanEvidenceWriteRequest(
  val repoRoot: Path,
  val workflowId: String,
  val checkoutHead: String?,
  val digest: String,
  val references: List<PreplanEvidenceReference>,
) {
  init {
    require(workflowId.isNotBlank())
  }
}

sealed interface PreplanEvidenceWriteResult {
  data class Written(
    val path: Path,
    val excerpts: List<PreplanEvidenceReference>,
    val skipped: List<PreplanEvidenceReference>,
  ) : PreplanEvidenceWriteResult

  data class Degraded(
    val reason: String,
  ) : PreplanEvidenceWriteResult
}
