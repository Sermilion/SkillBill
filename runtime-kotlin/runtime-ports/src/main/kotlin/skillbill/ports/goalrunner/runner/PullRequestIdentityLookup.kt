package skillbill.ports.goalrunner.runner

import skillbill.ports.goalrunner.runner.model.PullRequestIdentity
import java.nio.file.Path

/** Reports the open pull request whose head is [branch] in the repository at [repoRoot], without creating one. */
fun interface PullRequestIdentityLookup {
  fun lookup(
    repoRoot: Path,
    branch: String,
  ): PullRequestIdentity
}
