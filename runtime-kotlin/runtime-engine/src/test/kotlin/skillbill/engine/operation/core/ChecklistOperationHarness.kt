package skillbill.engine.operation.core

import skillbill.engine.featuretask.phaserun.PhaseRunRequest
import skillbill.engine.featuretask.phaserun.PhaseRunResult
import skillbill.engine.featuretask.slot.PhaseRunner
import skillbill.engine.featuretask.slot.PhaseStepFileManifest
import skillbill.engine.featuretask.slot.PhaseStepInput
import skillbill.engine.featuretask.slot.PhaseStepOutput
import skillbill.engine.featuretask.slot.reviewStepOutput
import skillbill.engine.featuretask.slot.state.PhaseLaunchState
import skillbill.engine.operation.featureguard.FeatureGuardOperation
import skillbill.engine.operation.featureguardcleanup.FeatureGuardCleanupOperation
import skillbill.engine.operation.unittestvalue.UnitTestValueCheckOperation
import skillbill.infrastructure.sqlite.operation.SqliteOperationProposalRepository
import skillbill.infrastructure.sqlite.sqliteSessionFactoryForTests
import skillbill.infrastructure.workflow.git.GitWorkflowGitOperations
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Clock
import kotlin.io.path.createParentDirectories
import kotlin.io.path.writeText

internal class ChecklistOperationHarness : AutoCloseable {
  private val root: Path = Files.createTempDirectory("checklist-operation")
  val repo: Path = root.resolve("repo")
  val runner = ScriptedStepRunner(repo)
  val validations = mutableListOf<PhaseRunRequest>()
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
      OperationRegistry(
        listOf(
          UnitTestValueCheckOperation(git),
          FeatureGuardOperation(),
          FeatureGuardCleanupOperation { request ->
            validations += request
            PhaseRunResult.Completed("phr-validation", listOf("validation"), null, VALIDATION_VALUE)
          },
        ),
      ),
      OperationConfirmationGate(proposals, git, Clock.systemUTC()),
      OperationStepRunner(runner, git),
    )

  init {
    Files.createDirectories(repo)
    git("init", "--quiet", "--initial-branch=main")
    git("config", "user.email", "checklist@example.com")
    git("config", "user.name", "Checklist Test")
    git("config", "commit.gpgsign", "false")
    write("README.md", "checklist fixture\n")
    commitAll("initial")
  }

  fun invoke(
    operationId: String,
    instructions: String? = null,
    arguments: OperationArguments = OperationArguments(),
  ): OperationOutcome = executor.execute(OperationRequest(operationId, repo, "codex", arguments, instructions)).outcome

  fun write(
    path: String,
    text: String,
  ) {
    repo.resolve(path).createParentDirectories().writeText(text)
  }

  fun commitAll(message: String) {
    git("add", "--all")
    git("commit", "--quiet", "-m", message)
  }

  fun status(): String = git("status", "--porcelain")

  fun proposalRowCount(): Int =
    DriverManager.getConnection("jdbc:sqlite:${root.resolve("metrics.db")}").use { connection ->
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

  override fun close() {
    root.toFile().deleteRecursively()
  }

  private fun git(vararg arguments: String): String {
    val process = ProcessBuilder("git", *arguments).directory(repo.toFile()).redirectErrorStream(true).start()
    val output = process.inputStream.readBytes().decodeToString()
    check(process.waitFor() == 0) { "git ${arguments.joinToString(" ")} failed: $output" }
    return output.trim()
  }

  companion object {
    const val VALIDATION_VALUE = "Validation verdict: pass"
  }
}

internal class ScriptedStepRunner(
  private val repo: Path,
) : PhaseRunner {
  val inputs = mutableListOf<PhaseStepInput>()
  var proposalValue: String = "Proposed plan.\n"
  var editDuringProposal: Boolean = false

  override fun run(
    input: PhaseStepInput,
    state: PhaseLaunchState,
  ): PhaseStepOutput {
    inputs += input
    val applying = input.stepName.endsWith(APPLY_SUFFIX)
    if (applying || editDuringProposal) repo.resolve("edited-by-agent.txt").writeText(input.stepName)
    val value = if (applying) "Applied the confirmed proposal.\n" else proposalValue
    return reviewStepOutput(value).copy(fileManifest = PhaseStepFileManifest(emptyList(), emptyList()))
  }

  private companion object {
    const val APPLY_SUFFIX = ".apply"
  }
}
