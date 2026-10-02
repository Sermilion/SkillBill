package skillbill.infrastructure.sqlite

import skillbill.error.core.DatabaseAccessOperation
import skillbill.error.core.DatabaseFailureCode
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.UnresolvedEnvironmentContextFieldError
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimeWorkerOwnershipSchemaError
import skillbill.infrastructure.sqlite.core.schema.DatabaseIdentity
import skillbill.infrastructure.sqlite.telemetry.lifecycle.parseDurationSeconds
import skillbill.infrastructure.sqlite.workflow.featuretask.parseWorkerLeaseInstant
import skillbill.model.EnvironmentContext
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.workflow.WorkflowSnapshotValidator
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import java.nio.file.Files
import java.time.Clock
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SqliteDegradationDiagnosticsTest {
  @Test
  fun `unparsable lifecycle duration records one degradation and returns zero`() {
    val diagnostics = recordingDiagnostics()
    val seconds = parseDurationSeconds("not-a-time", "also-bad", diagnostics)
    assertEquals(0, seconds)
    assertTrue(
      diagnostics.warnings.single().contains("telemetry.duration_seconds"),
      diagnostics.warnings.toString(),
    )
  }

  @Test
  fun `unparsable worker lease expiry records one degradation before failing loud`() {
    val diagnostics = recordingDiagnostics()
    assertFailsWith<InvalidFeatureTaskRuntimeWorkerOwnershipSchemaError> {
      parseWorkerLeaseInstant("wf-lease", "expires_at", "not-an-instant", diagnostics)
    }
    assertTrue(
      diagnostics.warnings.single().contains("worker_lease.expires_at"),
      diagnostics.warnings.toString(),
    )
  }

  @Test
  fun `readUserVersion raises read ACCESS database failure when pragma fails`() {
    val dbPath = Files.createTempFile("skillbill-user-version", ".db")
    Files.writeString(dbPath, "not-a-sqlite-database")

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        DatabaseIdentity.readUserVersion(dbPath)
      }
    assertEquals(DatabaseFailureCode.ACCESS, error.code)
    assertTrue(error.message.orEmpty().startsWith("Database ${DatabaseAccessOperation.READ.wireValue} failed for '"))
  }

  @Test
  fun `unresolved environment context field fails at factory construction`() {
    val error =
      assertFailsWith<UnresolvedEnvironmentContextFieldError> {
        SQLiteDatabaseSessionFactory(
          EnvironmentContext(),
          Clock.systemUTC(),
          recordingDiagnostics(),
          object : WorkflowSnapshotValidator {
            override fun validate(
              snapshot: WorkflowStateSnapshot,
              slug: String,
            ) = Unit
          },
          "test-runtime-version",
        )
      }
    assertEquals("userHome", error.fieldName)
    assertTrue(error.message.orEmpty().contains("EnvironmentContext.userHome"))
  }

  private fun recordingDiagnostics(): RecordingDiagnostics = RecordingDiagnostics()

  private class RecordingDiagnostics : RuntimeDiagnostics {
    val warnings = CopyOnWriteArrayList<String>()

    override fun warning(
      message: String,
      error: Throwable?,
    ) {
      warnings += message
    }

    override fun error(
      message: String,
      error: Throwable?,
    ) {
      warnings += message
    }
  }
}
