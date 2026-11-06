package skillbill.ports.diagnostics

import java.nio.file.Path

fun interface ChildFailureOutputStore {
  fun retain(
    evidenceRoot: Path,
    workflowId: String,
    output: String,
  ): String
}
