package skillbill.engine.featuretask.slot.monitor

import skillbill.contracts.config.projectchecks.ProjectCheckCategoryConfig
import skillbill.contracts.config.projectchecks.ProjectCheckCommand
import skillbill.contracts.config.projectchecks.ProjectCheckCommandSnapshot
import skillbill.engine.featuretask.validation.ScriptedGateRunner
import skillbill.engine.featuretask.validation.failedEmptyFindings
import skillbill.engine.featuretask.validation.neverRunsGate
import skillbill.engine.featuretask.validation.passed
import skillbill.infrastructure.workflow.git.GitWorkflowGitOperations
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.validation.ValidationGateRunner
import skillbill.ports.validation.model.ValidationGateFinding
import skillbill.ports.validation.model.ValidationGateFindingParseMode
import skillbill.ports.validation.model.ValidationGateRunRequest
import skillbill.ports.validation.model.ValidationGateRunResult
import skillbill.workflow.taskruntime.model.validation.ValidationGateCacheMode
import skillbill.workflow.taskruntime.model.validation.ValidationGateRunOutcome
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RuntimeMonitorRepairPublicationGateTest {
  private val repoRoot: Path = Files.createTempDirectory("skillbill-monitor-publication-gate")
  private val warnings = mutableListOf<String>()
  private val diagnostics =
    object : RuntimeDiagnostics {
      override fun warning(
        message: String,
        error: Throwable?,
      ) {
        warnings += message
      }

      override fun error(
        message: String,
        error: Throwable?,
      ) = Unit

      override fun info(message: String) = Unit
    }

  @AfterTest
  fun cleanUp() {
    repoRoot.toFile().deleteRecursively()
  }

  @Test
  fun `markers committed by an earlier repair send the repair back without running the gate`() {
    initRepository()
    write(SOURCE, conflicted("fun main() {}"))
    commit("Prepare changes for pull request")

    val verdict = gate(runner = neverRunsGate()).verify(request(repoRoot, snapshot = null))

    val again = assertIs<MonitorRepairPublicationVerdict.RepairAgain>(verdict)
    assertTrue(SOURCE in again.correction, again.correction)
    assertFalse(FIXTURE in again.correction, again.correction)
  }

  @Test
  fun `uncommitted markers in the working tree send the repair back`() {
    initRepository()
    write(SOURCE, conflicted("fun main() {}"))
    write("runtime-kotlin/New.kt", conflicted("class New"))

    val verdict = gate(runner = neverRunsGate()).verify(request(repoRoot, snapshot = null, baseBranch = null))

    val again = assertIs<MonitorRepairPublicationVerdict.RepairAgain>(verdict)
    assertTrue(SOURCE in again.correction && "runtime-kotlin/New.kt" in again.correction, again.correction)
  }

  @Test
  fun `a marker fixture already on the base branch and a lone separator are not conflicts`() {
    initRepository()
    write(FIXTURE, Files.readString(repoRoot.resolve(FIXTURE)) + "more\n")
    write(SOURCE, "fun main() {}\n=======\n")
    commit("Edit fixture")
    val runner = ScriptedGateRunner(listOf(passed(forced = true)))

    val verdict = gate(runner = runner).verify(request(repoRoot, publicationSnapshot()))

    assertEquals(MonitorRepairPublicationVerdict.Publishable, verdict)
    val runRequest = runner.requests.single()
    assertEquals(listOf("echo", "collect-all-full"), runRequest.argv)
    assertEquals(ValidationGateCacheMode.FORCED_FULL, runRequest.cacheMode)
    assertEquals(ValidationGateFindingParseMode.COLLECT_ALL, runRequest.findingParseMode)
    assertEquals(repoRoot, runRequest.repoRoot)
  }

  @Test
  fun `a failing full gate sends every finding and the output tail back to the repair`() {
    initRepository()
    val finding = ValidationGateFinding("runtime-sqlite", "DatabaseMigrationsTest", "expected 42 tables", "Db.kt:7")
    val failed = failedEmptyFindings(stdout = "BUILD FAILED in 3s").copy(findings = listOf(finding))

    val verdict = gate(runner = ScriptedGateRunner(listOf(failed))).verify(request(repoRoot, publicationSnapshot()))

    val again = assertIs<MonitorRepairPublicationVerdict.RepairAgain>(verdict)
    assertTrue("echo collect-all-full" in again.correction, again.correction)
    assertTrue("DatabaseMigrationsTest: expected 42 tables (Db.kt:7)" in again.correction, again.correction)
    assertTrue("BUILD FAILED in 3s" in again.correction, again.correction)
  }

  @Test
  fun `a gate runner failure refuses publication instead of publishing`() {
    initRepository()
    val throwing =
      object : ValidationGateRunner {
        override fun run(request: ValidationGateRunRequest): ValidationGateRunResult = error("gradle daemon died")
      }

    val verdict = gate(runner = throwing).verify(request(repoRoot, publicationSnapshot()))

    val refused = assertIs<MonitorRepairPublicationVerdict.Refused>(verdict)
    assertTrue("gradle daemon died" in refused.reason, refused.reason)
  }

  @Test
  fun `missing repair validation snapshot refuses publication instead of pack fallback`() {
    initRepository()

    val verdict = gate(runner = neverRunsGate()).verify(request(repoRoot, snapshot = null))

    val refused = assertIs<MonitorRepairPublicationVerdict.Refused>(verdict)
    assertTrue("accepted validation command snapshot" in refused.reason, refused.reason)
  }

  @Test
  fun `a later saved command success does not clear an earlier failure`() {
    initRepository()
    val finding = ValidationGateFinding("runtime-sqlite", "DatabaseMigrationsTest", "expected 42 tables", "Db.kt:7")
    val failed = failedEmptyFindings(stdout = "FIRST FAILED").copy(findings = listOf(finding))
    val runner = ScriptedGateRunner(listOf(failed, passed(forced = true)))
    val snapshot =
      publicationSnapshot(
        listOf("echo", "first"),
        listOf("echo", "second"),
      )

    val verdict = gate(runner = runner).verify(request(repoRoot, snapshot))

    val again = assertIs<MonitorRepairPublicationVerdict.RepairAgain>(verdict)
    assertEquals(2, runner.requests.size)
    assertEquals(listOf("echo", "first"), runner.requests[0].argv)
    assertEquals(listOf("echo", "second"), runner.requests[1].argv)
    assertTrue("DatabaseMigrationsTest: expected 42 tables (Db.kt:7)" in again.correction, again.correction)
    assertTrue("FIRST FAILED" in again.correction, again.correction)
  }

  @Test
  fun `a runner refusal after the first saved command does not dispatch remaining commands`() {
    initRepository()
    val runner =
      object : ValidationGateRunner {
        val requests = mutableListOf<ValidationGateRunRequest>()

        override fun run(request: ValidationGateRunRequest): ValidationGateRunResult {
          requests += request
          error("gradle daemon died")
        }
      }
    val snapshot =
      publicationSnapshot(
        listOf("echo", "first"),
        listOf("echo", "second"),
      )

    val verdict = gate(runner = runner).verify(request(repoRoot, snapshot))

    val refused = assertIs<MonitorRepairPublicationVerdict.Refused>(verdict)
    assertEquals(1, runner.requests.size)
    assertTrue("gradle daemon died" in refused.reason, refused.reason)
  }

  @Test
  fun `rejected zero-work on the first saved command stops remaining dispatch`() {
    initRepository()
    val zeroWork =
      ValidationGateRunResult(
        exitCode = 0,
        durationMs = 1,
        outcome = ValidationGateRunOutcome.REJECTED_ZERO_WORK,
        cacheMode = ValidationGateCacheMode.FORCED_FULL,
        executedWorkUnits = 0,
        executedCheckIdentities = emptyList(),
        findings = emptyList(),
        command = "echo first",
      )
    val runner = ScriptedGateRunner(listOf(zeroWork, passed(forced = true)))
    val snapshot =
      publicationSnapshot(
        listOf("echo", "first"),
        listOf("echo", "second"),
      )

    val verdict = gate(runner = runner).verify(request(repoRoot, snapshot))

    val refused = assertIs<MonitorRepairPublicationVerdict.Refused>(verdict)
    assertEquals(1, runner.requests.size)
    assertTrue("ran no work" in refused.reason, refused.reason)
  }

  @Test
  fun `explicit known-empty saved validation publishes with a diagnostic and runs no command`() {
    initRepository()
    val snapshot =
      ProjectCheckCommandSnapshot(
        projectId = "monitor-gate-fixture",
        buildCommands = null,
        validationCommands = ProjectCheckCategoryConfig(emptyList()),
        parserMetadata = null,
        gradleWrapper = null,
      ).frozen()

    val verdict = gate(runner = neverRunsGate()).verify(request(repoRoot, snapshot))

    assertEquals(MonitorRepairPublicationVerdict.Publishable, verdict)
    assertTrue(warnings.any { "known_empty" in it && "seam=monitor_repair_full_gate" in it }, "$warnings")
  }

  @Test
  fun `conflict blocks need all three markers`() {
    assertTrue(ConflictMarkerBlocks.present(conflicted("x")))
    assertTrue(ConflictMarkerBlocks.present("<<<<<<< ours\na\n||||||| base\nb\n=======\nc\n>>>>>>> theirs\n"))
    assertTrue(ConflictMarkerBlocks.present("<<<<<<<\r\na\r\n=======\r\nb\r\n>>>>>>>\r\n"))
    assertFalse(ConflictMarkerBlocks.present("<<<<<<< HEAD\na\n>>>>>>> theirs\n"))
    assertFalse(ConflictMarkerBlocks.present("=======\n>>>>>>> theirs\n"))
    assertFalse(ConflictMarkerBlocks.present("<<<<<<<< long\na\n=======\nb\n>>>>>>>> long\n"))
    assertFalse(ConflictMarkerBlocks.present(null))
  }

  private fun gate(runner: ValidationGateRunner): RuntimeMonitorRepairPublicationGate =
    RuntimeMonitorRepairPublicationGate(GitWorkflowGitOperations(), runner, diagnostics)

  private fun request(
    repoRoot: Path,
    snapshot: ProjectCheckCommandSnapshot? = publicationSnapshot(),
    baseBranch: String? = "main",
  ): MonitorRepairPublicationGateRequest = MonitorRepairPublicationGateRequest(repoRoot, baseBranch, snapshot)

  private fun publicationSnapshot(vararg argv: List<String>): ProjectCheckCommandSnapshot {
    val commands =
      argv.toList().ifEmpty { listOf(listOf("echo", "collect-all-full")) }
        .map { ProjectCheckCommand(it, ".") }
    return ProjectCheckCommandSnapshot(
      projectId = "monitor-gate-fixture",
      buildCommands = null,
      validationCommands = ProjectCheckCategoryConfig(commands),
      parserMetadata = null,
      gradleWrapper = null,
    ).frozen()
  }

  private fun initRepository() {
    git("init", "-b", "main")
    git("config", "user.email", "monitor-gate@example.test")
    git("config", "user.name", "Monitor Gate")
    git("config", "commit.gpgsign", "false")
    write(SOURCE, "fun main() {}\n")
    write(FIXTURE, conflicted("fixture"))
    commit("Initial commit")
    git("checkout", "-b", "feat/repair")
  }

  private fun conflicted(body: String): String = "<<<<<<< HEAD\n$body\n=======\n$body // main\n>>>>>>> main\n"

  private fun write(
    path: String,
    content: String,
  ) {
    val file = repoRoot.resolve(path)
    Files.createDirectories(file.parent)
    Files.writeString(file, content)
  }

  private fun commit(message: String) {
    git("add", "-A")
    git("commit", "-m", message)
  }

  private fun git(vararg args: String) {
    val process =
      ProcessBuilder(listOf("git", "-C", repoRoot.toString()) + args.toList()).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $output" }
  }

  private companion object {
    const val SOURCE = "runtime-kotlin/Main.kt"
    const val FIXTURE = "runtime-kotlin/conflict-fixture.txt"
  }
}
