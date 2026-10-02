package skillbill.application

import skillbill.application.idestatus.AgentActivityStampWriter
import skillbill.application.review.spec.SpecIntentProjectionExtractor
import skillbill.application.review.spec.SpecIntentSourceRead
import skillbill.application.runtimepersistence.RuntimeOwnedPersistenceBoundary
import skillbill.application.system.SystemService
import skillbill.application.telemetry.settings.telemetrySettingsOrNull
import skillbill.application.updatecheck.UpdateCheckService
import skillbill.application.updatecheck.model.UpdateCheckStatus
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.featuretask.RuntimeOwnedPersistenceFailureCode
import skillbill.idestatus.model.AgentActivityLabel
import skillbill.model.RuntimeVersion
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.process.ReleaseCatalogPort
import skillbill.ports.process.model.ReleaseCatalogResult
import skillbill.ports.review.ReviewContextEnvelopeValidator
import skillbill.ports.telemetry.transport.TelemetrySettingsProvider
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.review.context.ReviewContextWireMap
import skillbill.review.context.model.accounting.ReviewContextBudgetPolicy
import skillbill.telemetry.model.TelemetrySettings
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class ApplicationCooperativeFailureBoundaryTest {
  @Test
  fun `spec read cancellation propagates instead of becoming unavailable`() {
    val fileStore =
      object : DecompositionManifestStore by TestDecompositionManifestStore {
        override fun isRegularFile(path: Path) = true

        override fun readText(path: Path): String = throw CancellationException("cancelled")
      }
    val validator = noopReviewContextEnvelopeValidator()
    assertFailsWith<CancellationException> {
      SpecIntentProjectionExtractor(validator, fileStore).extract(
        Path.of("/tmp/audit-repo"),
        Path.of("spec.md"),
        ReviewContextBudgetPolicy.DEFAULT,
      )
    }
  }

  @Test
  fun `spec read interruption propagates instead of becoming unavailable`() {
    val fileStore =
      object : DecompositionManifestStore by TestDecompositionManifestStore {
        override fun isRegularFile(path: Path) = true

        override fun readText(path: Path): String = throw InterruptedException("interrupted")
      }
    assertFailsWith<InterruptedException> {
      SpecIntentProjectionExtractor(
        noopReviewContextEnvelopeValidator(),
        fileStore,
      ).extract(
        Path.of("/tmp/audit-repo"),
        Path.of("spec.md"),
        ReviewContextBudgetPolicy.DEFAULT,
      )
    }
  }

  @Test
  fun `spec read io failure remains unavailable`() {
    val fileStore =
      object : DecompositionManifestStore by TestDecompositionManifestStore {
        override fun isRegularFile(path: Path) = true

        override fun readText(path: Path): String = throw IOException("unreadable")
      }
    val read =
      SpecIntentProjectionExtractor(noopReviewContextEnvelopeValidator(), fileStore).extract(
        Path.of("/tmp/audit-repo"),
        Path.of("spec.md"),
        ReviewContextBudgetPolicy.DEFAULT,
      )
    val unavailable = assertIs<SpecIntentSourceRead.Unavailable>(read)
    assertEquals("unreadable", unavailable.reason)
  }

  @Test
  fun `activity write cancellation propagates`() {
    val database = FailingDatabase(CancellationException("cancelled"))
    assertFailsWith<CancellationException> {
      AgentActivityStampWriter(
        database,
        Clock.systemUTC(),
        NoopRuntimeDiagnostics,
        TimeSource.Monotonic,
      ).recordEvidenceRead(UUID.randomUUID().toString(), null)
    }
    assertEquals(1, database.calls)
  }

  @Test
  fun `activity write interruption propagates`() {
    val database = FailingDatabase(InterruptedException("interrupted"))
    assertFailsWith<InterruptedException> {
      AgentActivityStampWriter(
        database,
        Clock.systemUTC(),
        NoopRuntimeDiagnostics,
        TimeSource.Monotonic,
      ).recordEvidenceRead(UUID.randomUUID().toString(), null)
    }
    assertEquals(1, database.calls)
  }

  @Test
  fun `lazy activity workflow resolution cancellation propagates`() {
    val writer =
      AgentActivityStampWriter(
        FailingDatabase(CancellationException("cancelled")),
        Clock.systemUTC(),
        NoopRuntimeDiagnostics,
        TimeSource.Monotonic,
      )
    assertFailsWith<CancellationException> {
      writer.lazySink({ throw CancellationException("cancelled") }, null).stamp(
        AgentActivityLabel.EVIDENCE_READ,
      )
    }
  }

  @Test
  fun `optional persistence interruption propagates instead of returning fallback`() {
    val boundary =
      RuntimeOwnedPersistenceBoundary(
        FailingDatabase(InterruptedException("cancelled")),
        NoopRuntimeDiagnostics,
      )
    assertFailsWith<InterruptedException> {
      boundary.optionalRead("probe", "fact", "fallback") { "unreachable" }
    }
  }

  @Test
  fun `optional persistence cancellation propagates instead of returning fallback`() {
    val boundary =
      RuntimeOwnedPersistenceBoundary(
        FailingDatabase(CancellationException("cancelled")),
        NoopRuntimeDiagnostics,
      )
    assertFailsWith<CancellationException> {
      boundary.optionalRead("probe", "fact", "fallback") { "unreachable" }
    }
  }

  @Test
  fun `optional persistence retains a diagnostic when it returns its fallback`() {
    RecordingDiagnostics.lastWarning = null
    val result =
      RuntimeOwnedPersistenceBoundary(
        FailingDatabase(IOException("optional-store-down")),
        RecordingDiagnostics(),
      ).optionalRead("probe", "fact", "fallback") { "unreachable" }

    assertEquals("fallback", result)
    assertTrue(RecordingDiagnostics.lastWarning.orEmpty().contains("optional-store-down"))
  }

  @Test
  fun `required persistence retains the original store cause`() {
    RecordingDiagnostics.lastWarning = null
    val root = IOException("store-down")
    val boundary = RuntimeOwnedPersistenceBoundary(FailingDatabase(root), RecordingDiagnostics())
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        boundary.requiredRead("probe", "fact") { "unreachable" }
      }
    assertEquals(RuntimeOwnedPersistenceFailureCode.REVIEW_FACT_UNAVAILABLE, error.code)
    assertEquals(root, error.cause)
    assertTrue(RecordingDiagnostics.lastWarning.orEmpty().contains("store-down"))
  }

  @Test
  fun `required persistence retains the store cause when diagnostics also fail`() {
    val root = IOException("store-down")
    val diagnostics =
      object : RuntimeDiagnostics {
        override fun warning(
          message: String,
          error: Throwable?,
        ): Unit = check(false) { "diagnostics-down" }

        override fun error(
          message: String,
          error: Throwable?,
        ) = Unit
      }
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        RuntimeOwnedPersistenceBoundary(FailingDatabase(root), diagnostics)
          .requiredRead("probe", "fact") { "unreachable" }
      }
    assertEquals(RuntimeOwnedPersistenceFailureCode.REVIEW_FACT_UNAVAILABLE, error.code)
    assertSame(root, error.cause)
  }

  @Test
  fun `update check interruption propagates`() {
    val service =
      UpdateCheckService(
        systemService = versionedSystemService("1.0.0"),
        releaseCatalog =
          object : ReleaseCatalogPort {
            override fun listReleases(): ReleaseCatalogResult = throw InterruptedException("cancelled")
          },
      )
    assertFailsWith<InterruptedException> { service.check(includePrereleases = false) }
  }

  @Test
  fun `update check cancellation propagates`() {
    val service =
      UpdateCheckService(
        systemService = versionedSystemService("1.0.0"),
        releaseCatalog =
          object : ReleaseCatalogPort {
            override fun listReleases(): ReleaseCatalogResult = throw CancellationException("cancelled")
          },
      )
    assertFailsWith<CancellationException> { service.check(includePrereleases = false) }
  }

  @Test
  fun `update check malformed payload keeps unknown status`() {
    val service =
      UpdateCheckService(
        systemService = versionedSystemService("1.0.0"),
        releaseCatalog =
          object : ReleaseCatalogPort {
            override fun listReleases(): ReleaseCatalogResult =
              ReleaseCatalogResult.Failure("malformed GitHub Releases payload")
          },
      )
    val result = service.check(includePrereleases = false)
    assertEquals(UpdateCheckStatus.UNKNOWN, result.status)
  }

  @Test
  fun `optional telemetry settings loading preserves cooperative failures`() {
    val cancelled =
      object : TelemetrySettingsProvider {
        override fun load(materialize: Boolean) = throw CancellationException("cancelled")
      }
    val interrupted =
      object : TelemetrySettingsProvider {
        override fun load(materialize: Boolean) = throw InterruptedException("interrupted")
      }

    val diagnostics = NoopRuntimeDiagnostics
    assertFailsWith<CancellationException> { telemetrySettingsOrNull(cancelled, diagnostics) }
    assertFailsWith<InterruptedException> { telemetrySettingsOrNull(interrupted, diagnostics) }
  }
}

private fun noopReviewContextEnvelopeValidator(): ReviewContextEnvelopeValidator =
  object : ReviewContextEnvelopeValidator {
    override fun validate(
      envelope: ReviewContextWireMap,
      sourceLabel: String,
    ) = Unit

    override fun validateSpecIntentProjection(
      envelope: ReviewContextWireMap,
      sourceLabel: String,
    ) = Unit
  }

private class FailingDatabase(private val failure: Throwable) : DatabaseSessionFactory {
  var calls = 0

  override fun resolveDbPath(): Path = Path.of("/tmp/failing-database.db")

  override fun databaseExists(): Boolean = true

  override fun <T> read(block: (UnitOfWork) -> T): T {
    calls++
    throw failure
  }

  override fun <T> transaction(block: (UnitOfWork) -> T): T = read(block)

  override fun <T> selfManagedWrite(block: (UnitOfWork) -> T): T = read(block)
}

private object NoopRuntimeDiagnostics : RuntimeDiagnostics {
  override fun warning(
    message: String,
    error: Throwable?,
  ) = Unit

  override fun error(
    message: String,
    error: Throwable?,
  ) = Unit
}

private class RecordingDiagnostics : RuntimeDiagnostics {
  override fun warning(
    message: String,
    error: Throwable?,
  ) {
    lastWarning = message
  }

  override fun error(
    message: String,
    error: Throwable?,
  ) = Unit

  companion object {
    var lastWarning: String? = null
  }
}

private fun versionedSystemService(version: String): SystemService =
  SystemService(
    UpdateCheckTestDatabaseSessionFactory(),
    UpdateCheckTestTelemetrySettingsProvider,
    NoopRuntimeDiagnostics,
    RuntimeVersion(version),
  )

private class UpdateCheckTestDatabaseSessionFactory : DatabaseSessionFactory {
  private val dbPath = Files.createTempDirectory("cooperative-failure-db").resolve("metrics.db")

  override fun resolveDbPath(): Path = dbPath

  override fun databaseExists(): Boolean = Files.exists(resolveDbPath())

  override fun <T> read(block: (UnitOfWork) -> T): T = error("unused")

  override fun <T> selfManagedWrite(block: (UnitOfWork) -> T): T = transaction(block)

  override fun <T> transaction(block: (UnitOfWork) -> T): T = error("unused")
}

private object UpdateCheckTestTelemetrySettingsProvider : TelemetrySettingsProvider {
  override fun load(materialize: Boolean): TelemetrySettings = error("unused")
}
