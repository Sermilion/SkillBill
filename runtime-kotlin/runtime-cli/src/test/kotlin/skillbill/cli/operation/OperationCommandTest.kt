package skillbill.cli.operation

import com.github.ajalt.clikt.core.UsageError
import skillbill.cli.INSTALLED_BASE_TAG
import skillbill.cli.NEWER_RELEASE_TAG
import skillbill.cli.OLDER_RELEASE_TAG
import skillbill.cli.core.CliRuntime
import skillbill.cli.kernel.cli.CliRunState
import skillbill.cli.model.CliRuntimeContext
import skillbill.cli.updateCheckRequester
import skillbill.engine.operation.core.OperationArguments
import skillbill.engine.operation.core.OperationOutcome
import skillbill.engine.operation.core.OperationResult
import skillbill.ports.telemetry.model.RemoteTransportResponse
import skillbill.ports.telemetry.transport.RemoteTransportPort
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OperationCommandTest {
  private val tempDir: Path = Files.createTempDirectory("skillbill-cli-operation")
  private val dbPath: Path = tempDir.resolve("metrics.db")

  @AfterTest
  fun cleanUp() {
    tempDir.toFile().deleteRecursively()
  }

  @Test
  fun `operation update-check prints what update-check prints for every release catalog answer`() {
    val requesters =
      listOf(
        updateCheckRequester(mutableListOf(), latest = NEWER_RELEASE_TAG),
        updateCheckRequester(mutableListOf(), latest = OLDER_RELEASE_TAG),
        updateCheckRequester(mutableListOf(), latest = INSTALLED_BASE_TAG),
        RemoteTransportPort { _, _, _, _ -> RemoteTransportResponse(429, "") },
      )
    val statuses =
      requesters.map { requester ->
        val context = CliRuntimeContext(requester = requester, userHome = tempDir, repositoryRoot = tempDir)
        val direct = CliRuntime.run(listOf("--db", dbPath.toString(), "update-check"), context)
        val operation = CliRuntime.run(listOf("--db", dbPath.toString(), "operation", "update-check"), context)

        assertEquals(0, operation.exitCode, operation.stdout)
        val lines = operation.stdout.trimEnd().lines()
        assertTrue(lines.last().startsWith("Operation invocation ID: opr-"), operation.stdout)
        assertEquals(direct.stdout.trimEnd(), lines.dropLast(1).joinToString("\n"))
        lines.first()
      }

    assertContains(statuses, "status: update_available")
    assertContains(statuses, "status: unknown")
    assertEquals(0, rowCount("feature_task_workflows"))
  }

  @Test
  fun `release without a bump is a usage error naming every bump`() {
    listOf(emptyList(), listOf("bump:huge")).forEach { args ->
      val result =
        CliRuntime.run(
          listOf("--db", dbPath.toString(), "operation", "release") + args,
          CliRuntimeContext(userHome = tempDir, repositoryRoot = tempDir),
        )
      val output = result.stdout + result.stderr

      assertNotEquals(0, result.exitCode, output)
      listOf("bump:patch", "bump:minor", "bump:major").forEach { bump -> assertContains(output, bump) }
    }
  }

  @Test
  fun `an unknown operation is a usage error listing the registered ids`() {
    val result =
      CliRuntime.run(
        listOf("--db", dbPath.toString(), "operation", "deploy"),
        CliRuntimeContext(userHome = tempDir, repositoryRoot = tempDir),
      )
    val output = result.stdout + result.stderr

    assertNotEquals(0, result.exitCode, output)
    assertContains(
      output,
      "Unknown operation 'deploy'; expected one of update-check, release, unit-test-value-check, feature-guard, " +
        "feature-guard-cleanup.",
    )
  }

  @Test
  fun `awaiting confirmation exits with a code no other command uses and help documents it`() {
    assertEquals(4, OPERATION_EXIT_AWAITING_CONFIRMATION)
    val help = CliRuntime.run(listOf("operation", "--help"), CliRuntimeContext(userHome = tempDir))

    assertContains(help.stdout.replace(Regex("\\s+"), " "), "then exits 4")
  }

  @Test
  fun `a release proposal prints the version, changelog, and token, then exits 4 with the status line last`() {
    val summary = "Release v1.3.0 (bump: minor, previous: v1.2.3)\n\n## What's New in v1.3.0\n\n- Operations.\n"
    val state = CliRunState(stdinText = null)

    writeOperationResult(
      state,
      "release",
      OperationResult("opr-1", OperationOutcome.AwaitingConfirmation(token = "opt-7", proposalSummary = summary)),
    )
    val result = assertNotNull(state.result)
    val lines = result.stdout.trimEnd().lines()

    assertEquals(4, result.exitCode)
    assertTrue(result.stdout.startsWith(summary.trimEnd()), result.stdout)
    assertContains(lines, "Operation invocation ID: opr-1")
    assertEquals("status: awaiting_confirmation confirm:opt-7", lines.last())
  }

  @Test
  fun `a refused confirm prints the reason and exits 1 without a status line`() {
    val state = CliRunState(stdinText = null)

    writeOperationResult(
      state,
      "release",
      OperationResult("opr-2", OperationOutcome.Blocked("Operation proposal 'opt-7' was already confirmed.")),
    )
    val result = assertNotNull(state.result)

    assertEquals(1, result.exitCode)
    assertContains(result.stdout, "Operation 'release' blocked: Operation proposal 'opt-7' was already confirmed.")
    assertTrue("awaiting_confirmation" !in result.stdout, result.stdout)
  }

  @Test
  fun `parser maps known keys to arguments and keeps the rest as operator instructions`() {
    val invocation =
      OperationInvocationParser.parse(
        "release",
        listOf("bump:minor", "mention", "the", "CLI", "confirm:opt-1", "note:kept"),
      )

    assertEquals(OperationArguments(bump = "minor", confirm = "opt-1"), invocation.arguments)
    assertEquals("mention the CLI note:kept", invocation.instructions)
  }

  @Test
  fun `a blank confirm is a usage error instead of a new proposal`() {
    assertFailsWith<UsageError> { OperationInvocationParser.parse("release", listOf("bump:minor", "confirm:")) }
  }

  private fun rowCount(table: String): Int =
    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      val exists =
        connection.prepareStatement("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?").use { query ->
          query.setString(1, table)
          query.executeQuery().use { rows -> rows.next() }
        }
      if (!exists) return@use 0
      connection.createStatement().use { statement ->
        statement.executeQuery("SELECT COUNT(*) FROM $table").use { rows ->
          rows.next()
          rows.getInt(1)
        }
      }
    }
}
