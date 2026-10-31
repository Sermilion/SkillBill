package skillbill.engine.diagnostics.block.report

import org.junit.jupiter.api.io.TempDir
import skillbill.engine.featuretask.phaserun.phaseRunDatabase
import skillbill.model.RuntimeVersion
import skillbill.ports.blockincident.BlockIncidentTelemetryPort
import skillbill.ports.blockincident.ProductIssueCredentialPort
import skillbill.ports.blockincident.ProductIssueTrackerPort
import skillbill.ports.blockincident.ProductReportDeliveryOwnershipPort
import skillbill.ports.blockincident.model.BlockIncidentTelemetryAvailability
import skillbill.ports.blockincident.model.BlockIncidentTelemetryState
import skillbill.ports.blockincident.model.ProductReportRecord
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.system.HostPlatformPort
import skillbill.ports.system.RuntimeBuildIdentityPort
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentDiagnosis
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentDiagnosisRecord
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentOccurrence
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryAttempt
import java.nio.file.Path
import java.time.Clock
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class ProductReportDeliveryTest {
  @TempDir lateinit var home: Path

  @Test
  fun `delivery scan failure is recorded and later delivery proceeds`() {
    val failure = IllegalStateException("database unavailable")
    val diagnostics = RecordingDiagnostics()
    val database = recoveringDatabase(failure)
    delivery(database, diagnostics).deliver()
    assertEquals(1, diagnostics.warnings.size)
    assertEquals("Product report delivery scan failed.", diagnostics.warnings.single().first)
    assertSame(failure, diagnostics.warnings.single().second)

    delivery(database, diagnostics).deliver()

    assertEquals(1, diagnostics.warnings.size)
  }

  @Test
  fun `delivery scan cancellation propagates without a warning`() {
    val cancellation = CancellationException("stopped")
    val diagnostics = RecordingDiagnostics()

    val thrown =
      assertFailsWith<CancellationException> {
        delivery(recoveringDatabase(cancellation), diagnostics).deliver()
      }
    assertSame(cancellation, thrown)
    assertEquals(emptyList(), diagnostics.warnings)
  }

  private fun recoveringDatabase(firstFailure: Throwable): DatabaseSessionFactory {
    val delegate = phaseRunDatabase(home, Clock.systemUTC())
    return object : DatabaseSessionFactory by delegate {
      private var firstRead = true

      override fun <T> read(block: (UnitOfWork) -> T): T {
        if (firstRead) {
          firstRead = false
          throw firstFailure
        }
        return delegate.read(block)
      }
    }
  }

  private fun delivery(
    database: DatabaseSessionFactory,
    diagnostics: RecordingDiagnostics,
  ) = ProductReportDelivery(
    database,
    Clock.systemUTC(),
    object : ProductIssueTrackerPort {
      override fun findByFingerprint(
        fingerprint: String,
        token: String,
      ) = error("unused")

      override fun create(
        title: String,
        body: String,
        labels: List<String>,
        token: String,
      ) = error("unused")

      override fun comment(
        issueNumber: Long,
        body: String,
        token: String,
      ) = error("unused")
    },
    ProductIssueCredentialPort { null },
    diagnostics,
    object : BlockIncidentTelemetryPort {
      override fun diagnosed(
        incident: BlockIncidentOccurrence,
        status: BlockIncidentDiagnosis,
        diagnosis: BlockIncidentDiagnosisRecord?,
      ) = Unit

      override fun recovery(
        incident: BlockIncidentOccurrence,
        attempt: BlockIncidentRecoveryAttempt,
      ) = Unit

      override fun reported(
        incident: BlockIncidentOccurrence,
        report: ProductReportRecord,
      ) = Unit

      override fun availability(incidentToken: String) =
        BlockIncidentTelemetryAvailability(
          BlockIncidentTelemetryState.DISABLED,
          null,
          incidentToken,
          null,
          null,
        )
    },
    ProductReportRuntimeFactsCollector(
      RuntimeVersion("test"),
      object : HostPlatformPort {
        override val osName = "test"
        override val jvmClassPath = ""
        override val javaCommand = null
        override val pathSeparator = ":"

        override fun resolveUserHome() = home

        override fun resolveEnvironment() = emptyMap<String, String>()

        override fun resolveJavaHome() = home

        override fun resolveWorkingDirectory() = home

        override fun resolveTemporaryDirectory() = home
      },
      object : RuntimeBuildIdentityPort {
        override fun readIdentity() = null
      },
    ),
    object : ProductReportDeliveryOwnershipPort {
      override fun acquire(
        databasePath: Path,
        reportId: String,
      ) = error("unused")
    },
  )

  private class RecordingDiagnostics : RuntimeDiagnostics {
    val warnings = mutableListOf<Pair<String, Throwable?>>()

    override fun info(message: String) = Unit

    override fun warning(
      message: String,
      error: Throwable?,
    ) {
      warnings += message to error
    }

    override fun error(
      message: String,
      error: Throwable?,
    ) = Unit
  }
}
