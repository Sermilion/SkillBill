package skillbill.ports.goalrunner.runner

import skillbill.ports.goalrunner.runner.model.PullRequestChecks
import java.nio.file.Path

/** Reports the CI checks registered for the pull request [prNumber] in the repository at [repoRoot]. */
fun interface PullRequestChecksLookup {
  fun lookup(
    repoRoot: Path,
    prNumber: Int,
  ): PullRequestChecks
}
