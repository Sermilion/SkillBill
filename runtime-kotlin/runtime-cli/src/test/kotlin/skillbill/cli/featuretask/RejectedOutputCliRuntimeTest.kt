package skillbill.cli.featuretask

import skillbill.application.diagnostics.RejectedOutputDiagnosticService
import skillbill.application.diagnostics.model.RejectedOutputDiagnosticRecording
import skillbill.application.diagnostics.model.RejectedOutputDiagnosticRequest
import skillbill.cli.core.CliRuntime
import skillbill.cli.model.CliRuntimeContext
import skillbill.infrastructure.sqlite.sqliteDatabaseSessionFactory
import skillbill.ports.diagnostics.model.RejectedOutputDiagnostic
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class RejectedOutputCliRuntimeTest {
  @Test
  fun `rejected-output-cleanup completes through CliRunState without usage help`() {
    val home = Files.createTempDirectory("skillbill-rejected-cleanup")
    val db = home.resolve("metrics.db")
    val result =
      CliRuntime.run(
        listOf(
          "--db",
          db.toString(),
          "feature-task",
          "rejected-output-cleanup",
          "--workflow",
          "wf-missing",
        ),
        CliRuntimeContext(userHome = home, environment = emptyMap()),
      )
    assertEquals(0, result.exitCode)
    assertEquals("deleted=0\n", result.stdout)
    assertFalse(result.stdout.contains("Usage"))
  }

  @Test
  fun `rejected-output raw retrieval keeps stored bytes through the cli result`() {
    val home = Files.createTempDirectory("skillbill-rejected-raw")
    val db = home.resolve("metrics.db")
    val raw = byteArrayOf(0xff.toByte(), 0, 13, 10, 42)
    val database =
      sqliteDatabaseSessionFactory(userHome = home, dbPathOverride = db.toString(), environment = emptyMap())
    database.transaction { unitOfWork ->
      RejectedOutputDiagnosticService(
        repository = unitOfWork.rejectedOutputDiagnostics,
        permissions = unitOfWork.rejectedOutputDiagnosticPermissions,
        metadataValidator = { },
        clock = Clock.systemUTC(),
      ).record(
        RejectedOutputDiagnosticRequest(
          workflowId = "wf-raw",
          phaseId = "implement",
          attempt = 1,
          rule = "schema",
          path = "$.status",
          reason = "invalid",
          agentId = "codex",
          model = "gpt",
          rawResponse = raw,
        ),
      )
    }

    val result =
      CliRuntime.run(
        listOf(
          "--db",
          db.toString(),
          "feature-task",
          "rejected-output",
          "--workflow",
          "wf-raw",
          "--phase",
          "implement",
          "--attempt",
          "1",
          "--raw-output",
        ),
        CliRuntimeContext(userHome = home, environment = emptyMap()),
      )

    assertEquals(0, result.exitCode)
    assertContentEquals(raw, requireNotNull(result.rawStdout))
    assertEquals("", result.stdout)
    assertFalse(result.stdout.contains("Usage"))
  }

  @Test
  fun `rejected-output metadata listing prints one escaped safe line per diagnostic`() {
    val home = Files.createTempDirectory("skillbill-rejected-metadata")
    val db = home.resolve("metrics.db")
    val recorded =
      recordDiagnostics(home, db, "wf-meta", reason = "bad \"status\"\nnext", repairTurns = listOf(0)).single()

    val result =
      CliRuntime.run(
        listOf("--db", db.toString(), "feature-task", "rejected-output", "--workflow", "wf-meta"),
        CliRuntimeContext(userHome = home, environment = emptyMap()),
      )

    assertEquals(0, result.exitCode)
    assertEquals(
      "identity=\"${recorded.identity}\" workflow=\"wf-meta\" phase=\"implement\" attempt=1 repair_turn=0 " +
        "rule=\"schema\" path=\"\$.status\" reason=\"bad \\\"status\\\"\\nnext\" agent=\"codex\" model=\"gpt\" " +
        "recorded_at=${recorded.recordedAt} byte_size=5 sha256=${recorded.sha256} lifecycle=stored\n",
      result.stdout,
    )
  }

  @Test
  fun `rejected-output raw retrieval across repair turns names the repair-turn selector`() {
    val home = Files.createTempDirectory("skillbill-rejected-ambiguous")
    val db = home.resolve("metrics.db")
    recordDiagnostics(home, db, "wf-turns", reason = "invalid", repairTurns = listOf(1, 2))

    val result =
      CliRuntime.run(
        listOf(
          "--db",
          db.toString(),
          "feature-task",
          "rejected-output",
          "--workflow",
          "wf-turns",
          "--phase",
          "implement",
          "--attempt",
          "1",
          "--raw-output",
        ),
        CliRuntimeContext(userHome = home, environment = emptyMap()),
      )

    assertEquals(
      "Rejected output diagnostic retrieval failed: raw output requires a selector resolving to exactly one " +
        "diagnostic; an attempt that ran a validation-gate repair cycle holds one per repair turn, " +
        "so add --repair-turn (the metadata listing prints each turn)",
      result.stderr,
    )
  }

  @Test
  fun `rejected-output raw retrieval of an expired diagnostic reports it as expired`() {
    val home = Files.createTempDirectory("skillbill-rejected-expired")
    val db = home.resolve("metrics.db")
    val recorded = recordDiagnostics(home, db, "wf-expired", reason = "invalid", repairTurns = listOf(0)).single()
    sqliteDatabaseSessionFactory(userHome = home, dbPathOverride = db.toString(), environment = emptyMap())
      .transaction { unitOfWork ->
        unitOfWork.rejectedOutputDiagnostics.markExpired(Instant.now().plus(Duration.ofDays(1)))
      }

    val result =
      CliRuntime.run(
        listOf(
          "--db",
          db.toString(),
          "feature-task",
          "rejected-output",
          "--workflow",
          "wf-expired",
          "--raw-output",
        ),
        CliRuntimeContext(userHome = home, environment = emptyMap()),
      )

    assertEquals(1, result.exitCode)
    assertEquals("Rejected output diagnostic '${recorded.identity}' has expired.", result.stderr)
  }

  @Test
  fun `rejected-output with no matching diagnostic reports the workflow as absent`() {
    val home = Files.createTempDirectory("skillbill-rejected-absent")
    val db = home.resolve("metrics.db")

    val result =
      CliRuntime.run(
        listOf("--db", db.toString(), "feature-task", "rejected-output", "--workflow", "wf-none"),
        CliRuntimeContext(userHome = home, environment = emptyMap()),
      )

    assertEquals(1, result.exitCode)
    assertEquals("Rejected output diagnostic 'wf-none' is absent.", result.stderr)
  }

  private fun recordDiagnostics(
    home: Path,
    db: Path,
    workflowId: String,
    reason: String,
    repairTurns: List<Int>,
  ): List<RejectedOutputDiagnostic> {
    val database =
      sqliteDatabaseSessionFactory(userHome = home, dbPathOverride = db.toString(), environment = emptyMap())
    return database.transaction { unitOfWork ->
      val service =
        RejectedOutputDiagnosticService(
          repository = unitOfWork.rejectedOutputDiagnostics,
          permissions = unitOfWork.rejectedOutputDiagnosticPermissions,
          metadataValidator = { },
          clock = Clock.tick(Clock.systemUTC(), Duration.ofSeconds(1)),
        )
      repairTurns.map { repairTurn ->
        val recording =
          service.record(
            RejectedOutputDiagnosticRequest(
              workflowId = workflowId,
              phaseId = "implement",
              attempt = 1,
              rule = "schema",
              path = "$.status",
              reason = reason,
              agentId = "codex",
              model = "gpt",
              rawResponse = byteArrayOf(1, 2, 3, 4, 5),
              repairTurn = repairTurn,
            ),
          )
        assertIs<RejectedOutputDiagnosticRecording.Recorded>(recording).metadata
      }
    }
  }
}
