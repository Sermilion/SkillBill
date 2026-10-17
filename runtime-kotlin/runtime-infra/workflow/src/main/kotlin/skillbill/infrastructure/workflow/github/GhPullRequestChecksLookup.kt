package skillbill.infrastructure.workflow.github

import me.tatarka.inject.annotations.Inject
import skillbill.ports.goalrunner.runner.PullRequestChecksLookup
import skillbill.ports.goalrunner.runner.model.PullRequestChecks
import java.nio.file.Path

class GhPullRequestChecksLookup internal constructor(
  private val gh: GhCommandRunner,
) : PullRequestChecksLookup {
  @Inject
  constructor() : this(ProcessGhCommandRunner())

  override fun lookup(
    repoRoot: Path,
    prNumber: Int,
  ): PullRequestChecks {
    val root = repoRoot.toAbsolutePath().normalize()
    val result = gh.run(root, listOf("pr", "checks", prNumber.toString(), "--json", "name,state,bucket,link"))
    return parsePullRequestChecks(result)
  }
}
