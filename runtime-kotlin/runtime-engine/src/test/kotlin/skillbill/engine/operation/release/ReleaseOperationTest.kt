package skillbill.engine.operation.release

import skillbill.engine.featuretask.slot.PhaseRunner
import skillbill.engine.featuretask.slot.PhaseStepFileManifest
import skillbill.engine.featuretask.slot.PhaseStepInput
import skillbill.engine.featuretask.slot.PhaseStepOutput
import skillbill.engine.featuretask.slot.reviewStepOutput
import skillbill.engine.featuretask.slot.state.PhaseLaunchState
import skillbill.engine.operation.core.ConfirmableOperation
import skillbill.engine.operation.core.ConfirmedOperationProposal
import skillbill.engine.operation.core.CurrentOperationAnchors
import skillbill.engine.operation.core.OperationArguments
import skillbill.engine.operation.core.OperationConfirmationGate
import skillbill.engine.operation.core.OperationContext
import skillbill.engine.operation.core.OperationExecutor
import skillbill.engine.operation.core.OperationOutcome
import skillbill.engine.operation.core.OperationRegistry
import skillbill.engine.operation.core.OperationRequest
import skillbill.engine.operation.core.OperationRunResult
import skillbill.engine.operation.core.OperationStepRunner
import skillbill.engine.operation.core.anchorsMoved
import skillbill.engine.operation.core.consumedToken
import skillbill.engine.operation.core.foreignToken
import skillbill.engine.operation.core.releaseBranchBehind
import skillbill.engine.operation.core.releaseWorktreeDirty
import skillbill.engine.operation.core.supersededToken
import skillbill.engine.operation.core.unknownToken
import skillbill.infrastructure.sqlite.operation.SqliteOperationProposalRepository
import skillbill.infrastructure.sqlite.sqliteSessionFactoryForTests
import skillbill.infrastructure.workflow.git.GitWorkflowGitOperations
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Clock
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ReleaseOperationTest {
  private val root: Path = Files.createTempDirectory("release-operation")
  private val origin: Path = root.resolve("origin.git")
  private val repo: Path = root.resolve("repo")
  private val runner = ChangelogRunner(CHANGELOG)
  private val git = GitWorkflowGitOperations()
  private val proposals =
    SqliteOperationProposalRepository(
      sqliteSessionFactoryForTests(
        userHome = root,
        dbPathOverride = root.resolve("metrics.db").toString(),
        environment = emptyMap(),
      ),
    )
  private val executor =
    OperationExecutor(
      OperationRegistry(listOf(ReleaseOperation(git))),
      OperationConfirmationGate(proposals, git, Clock.systemUTC()),
      OperationStepRunner(runner, git),
    )
  private val executorWithOtherOperation =
    OperationExecutor(
      OperationRegistry(listOf(ReleaseOperation(git), OtherConfirmableOperation)),
      OperationConfirmationGate(proposals, git, Clock.systemUTC()),
      OperationStepRunner(runner, git),
    )

  init {
    run(root, "git", "init", "--bare", "--initial-branch=main", origin.toString())
    run(root, "git", "clone", origin.toString(), repo.toString())
    configure(repo)
    commit(repo, "a.txt", "feat: first release")
    run(repo, "git", "tag", "v1.2.3")
    commit(repo, "b.txt", "feat: add runtime operations")
    run(repo, "git", "push", "--quiet", "origin", "main", "refs/tags/v1.2.3")
  }

  @AfterTest
  fun cleanUp() {
    root.toFile().deleteRecursively()
  }

  @Test
  fun `the first invocation proposes without tagging and confirm tags and pushes the stored changelog once`() {
    val proposal = assertIs<OperationOutcome.AwaitingConfirmation>(release(bump = "minor").outcome)

    assertContains(proposal.proposalSummary, "Release v1.3.0")
    assertContains(proposal.proposalSummary, CHANGELOG)
    assertContains(runner.directives.single(), "feat: add runtime operations")
    assertContains(runner.directives.single(), "## What's New in v1.3.0")
    assertContains(runner.directives.single(), "### 2. Find the previous release tag")
    assertEquals("", run(repo, "git", "tag", "--list", "v1.3.0"))

    runner.changelog = "## What's New in v1.3.0\n\n### Other\n- A recomputed changelog.\n"
    val confirmed = release(confirm = proposal.token).outcome

    assertIs<OperationOutcome.Completed>(confirmed)
    assertEquals(1, runner.directives.size, "confirm must not run the changelog step again")
    assertEquals(CHANGELOG, tagMessage("v1.3.0"))
    assertContains(run(repo, "git", "ls-remote", "--tags", "origin", "v1.3.0"), "refs/tags/v1.3.0")
    assertEquals(consumedToken(proposal.token), release(confirm = proposal.token).outcome)
  }

  @Test
  fun `a rejected tag push removes the local tag so the next proposal offers the same version`() {
    val hook = origin.resolve("hooks").resolve("pre-receive")
    hook.writeText("#!/bin/sh\nexit 1\n")
    hook.toFile().setExecutable(true)
    val proposal = assertIs<OperationOutcome.AwaitingConfirmation>(release(bump = "minor").outcome)

    assertIs<OperationOutcome.Failed>(release(confirm = proposal.token).outcome)
    assertEquals("", run(repo, "git", "tag", "--list", "v1.3.0"))
    val retry = assertIs<OperationOutcome.AwaitingConfirmation>(release(bump = "minor").outcome)
    assertContains(retry.proposalSummary, "Release v1.3.0")
  }

  @Test
  fun `a dirty worktree stops in pre with no proposal and no tag`() {
    repo.resolve("a.txt").writeText("edited")

    assertEquals(releaseWorktreeDirty(repo.toString()), release(bump = "patch").outcome)
    assertTrue(runner.directives.isEmpty())
    assertEquals(0, proposalRowCount())
    assertEquals("", run(repo, "git", "tag", "--list", "v1.2.4"))
  }

  @Test
  fun `a branch behind its remote stops in pre with no proposal`() {
    val other = root.resolve("other")
    run(root, "git", "clone", origin.toString(), other.toString())
    configure(other)
    commit(other, "c.txt", "fix: remote only")
    run(other, "git", "push", "--quiet", "origin", "main")

    assertEquals(releaseBranchBehind("main"), release(bump = "patch").outcome)
    assertTrue(runner.directives.isEmpty())
    assertEquals(0, proposalRowCount())
    assertEquals("", run(repo, "git", "tag", "--list", "v1.2.4"))
  }

  @Test
  fun `a stale, superseded, unknown, other-operation, or other-repo token is refused and creates no tag`() {
    val superseded = assertIs<OperationOutcome.AwaitingConfirmation>(release(bump = "patch").outcome)
    val current = assertIs<OperationOutcome.AwaitingConfirmation>(release(bump = "patch").outcome)

    assertEquals(supersededToken(superseded.token), release(confirm = superseded.token).outcome)
    assertEquals(unknownToken("opt-missing"), release(confirm = "opt-missing").outcome)

    assertEquals(
      foreignToken(current.token, OtherConfirmableOperation.id, repo.toString()),
      executorWithOtherOperation.execute(
        OperationRequest(
          operationId = OtherConfirmableOperation.id,
          repoRoot = repo,
          invokedAgentId = "codex",
          arguments = OperationArguments(confirm = current.token),
          instructions = null,
        ),
      ).outcome,
    )

    val otherRepo = root.resolve("other")
    run(root, "git", "clone", origin.toString(), otherRepo.toString())
    configure(otherRepo)
    assertEquals(
      foreignToken(current.token, "release", otherRepo.toString()),
      release(confirm = current.token, repoRoot = otherRepo).outcome,
    )

    commit(repo, "c.txt", "feat: after the proposal")
    run(repo, "git", "push", "--quiet", "origin", "main")
    assertEquals(
      anchorsMoved(current.token, listOf("HEAD", "remote_head")),
      release(confirm = current.token).outcome,
    )
    assertEquals("", run(repo, "git", "tag", "--list", "v1.2.4"))
  }

  private fun release(
    bump: String? = null,
    confirm: String? = null,
    repoRoot: Path = repo,
  ) = executor.execute(
    OperationRequest(
      operationId = "release",
      repoRoot = repoRoot,
      invokedAgentId = "codex",
      arguments = OperationArguments(bump = bump, confirm = confirm),
      instructions = null,
    ),
  )

  private fun tagMessage(tag: String): String =
    run(repo, "git", "cat-file", "tag", tag, trim = false).substringAfter("\n\n")

  private fun configure(dir: Path) {
    run(dir, "git", "config", "user.email", "release@example.com")
    run(dir, "git", "config", "user.name", "Release Test")
    run(dir, "git", "config", "commit.gpgsign", "false")
    run(dir, "git", "config", "tag.gpgsign", "false")
  }

  private fun commit(
    dir: Path,
    file: String,
    subject: String,
  ) {
    dir.resolve(file).writeText(subject)
    run(dir, "git", "add", file)
    run(dir, "git", "commit", "--quiet", "-m", subject)
  }

  private fun run(
    dir: Path,
    vararg command: String,
    trim: Boolean = true,
  ): String {
    val process = ProcessBuilder(*command).directory(dir.toFile()).redirectErrorStream(true).start()
    val output = process.inputStream.readBytes().decodeToString()
    check(process.waitFor() == 0) { "${command.joinToString(" ")} failed: $output" }
    return if (trim) output.trim() else output
  }

  private fun proposalRowCount(): Int {
    val dbPath = root.resolve("metrics.db")
    if (!Files.exists(dbPath)) return 0
    return DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      val exists =
        connection.prepareStatement("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?").use { query ->
          query.setString(1, "operation_proposals")
          query.executeQuery().use { rows -> rows.next() }
        }
      if (!exists) return@use 0
      connection.createStatement().use { statement ->
        statement.executeQuery("SELECT COUNT(*) FROM operation_proposals").use { rows ->
          rows.next()
          rows.getInt(1)
        }
      }
    }
  }

  private object OtherConfirmableOperation : ConfirmableOperation {
    override val id: String = "checklist"

    override fun run(context: OperationContext): OperationRunResult =
      OperationRunResult.Finished(OperationOutcome.Completed(id))

    override fun currentAnchors(context: OperationContext): CurrentOperationAnchors =
      CurrentOperationAnchors.Read(emptyMap())

    override fun execute(
      context: OperationContext,
      proposal: ConfirmedOperationProposal,
    ): OperationOutcome = error("a foreign token must be refused before execute")
  }

  private class ChangelogRunner(
    var changelog: String,
  ) : PhaseRunner {
    val directives = mutableListOf<String>()

    override fun run(
      input: PhaseStepInput,
      state: PhaseLaunchState,
    ): PhaseStepOutput {
      directives += input.directive
      return reviewStepOutput(changelog).copy(fileManifest = PhaseStepFileManifest(emptyList(), emptyList()))
    }
  }

  private companion object {
    const val CHANGELOG =
      "## What's New in v1.3.0\n\n### New Features\n- Runtime operations: run `skill-bill operation release`.\n"
  }
}
