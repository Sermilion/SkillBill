package skillbill.ports.operation

import skillbill.ports.operation.model.ReleaseBuildReadiness
import java.nio.file.Path

/** Reads build results for the exact release commit on the repository's default branch. */
fun interface ReleaseBuildGate {
  fun inspect(
    repoRoot: Path,
    commitSha: String,
  ): ReleaseBuildReadiness
}
