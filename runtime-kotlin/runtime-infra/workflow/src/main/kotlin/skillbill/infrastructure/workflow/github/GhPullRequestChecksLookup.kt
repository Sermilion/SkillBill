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
    val result = gh.run(root, listOf("pr", "checks", prNumber.toString(), "--json", CHECK_FIELDS))
    return parsePullRequestChecks(result)
  }

  private companion object {
    const val CHECK_FIELDS: String = "name,state,bucket,link,startedAt,completedAt"
  }
}
