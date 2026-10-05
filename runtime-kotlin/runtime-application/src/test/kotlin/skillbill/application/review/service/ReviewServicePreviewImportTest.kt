package skillbill.application.review.service

import skillbill.application.review.snapshot.HARNESS_ORIGIN_UNAVAILABLE
import skillbill.model.EnvironmentContext
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.review.preparation.ReviewAttributionPort
import skillbill.ports.review.preparation.ReviewInputSource
import skillbill.ports.telemetry.model.TelemetrySettingsLoad
import skillbill.ports.telemetry.transport.TelemetrySettingsProvider
import skillbill.review.plan.model.ReviewLaunchPlan
import skillbill.telemetry.model.TelemetrySettings
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class ReviewServicePreviewImportTest {
  @Test
  fun `previewImport reads stdinText argument instead of environment stdin`() {
    val contextStdin =
      """
      Review run ID: rvw-context-stdin
      Review session ID: rvs-context
      ### 2. Risk Register
      No findings.
      """.trimIndent()
    val argumentStdin =
      """
      Review run ID: rvw-argument-stdin
      Review session ID: rvs-argument
      ### 2. Risk Register
      No findings.
      """.trimIndent()
    var capturedStdin: String? = null
    val inputSource =
      object : ReviewInputSource {
        override fun readInput(
          inputPath: String,
          stdinText: String?,
        ): Pair<String, String?> {
          capturedStdin = stdinText
          return stdinText.orEmpty() to null
        }
      }
    val service =
      ReviewService(
        context = EnvironmentContext(stdinText = contextStdin),
        database = PreviewImportBlockingDatabase,
        settingsProvider = PreviewImportTelemetrySettings,
        reviewInputSource = inputSource,
        reviewAttributionPort = PreviewImportReviewAttribution,
        diagnostics = PreviewImportDiagnostics,
        originScopeKeyPort = HARNESS_ORIGIN_UNAVAILABLE,
      )

    val preview = service.previewImport("-", stdinText = argumentStdin)

    assertEquals(argumentStdin, capturedStdin)
    assertEquals("rvw-argument-stdin", preview.reviewRunId)
  }
}

private object PreviewImportBlockingDatabase : DatabaseSessionFactory {
  override fun resolveDbPath(): Path = error("previewImport must not touch the database")

  override fun databaseExists(): Boolean = error("previewImport must not touch the database")

  override fun <T> read(block: (UnitOfWork) -> T): T = error("previewImport must not touch the database")

  override fun <T> transaction(block: (UnitOfWork) -> T): T = error("previewImport must not touch the database")

  override fun <T> selfManagedWrite(block: (UnitOfWork) -> T): T = error("previewImport must not touch the database")
}

private object PreviewImportTelemetrySettings : TelemetrySettingsProvider {
  override fun load(materialize: Boolean): TelemetrySettings = error("previewImport must not load telemetry settings")

  override fun loadOrUnavailable(materialize: Boolean): TelemetrySettingsLoad =
    TelemetrySettingsLoad.Loaded(load(materialize))
}

private object PreviewImportReviewAttribution : ReviewAttributionPort {
  override fun routedSkillPlatformSlugs(): Map<String, String> = emptyMap()

  override fun composedLaunchPlan(routedPackSlug: String): ReviewLaunchPlan =
    ReviewLaunchPlan(
      routedPackSlug,
      emptyList(),
    )
}

private object PreviewImportDiagnostics : RuntimeDiagnostics {
  override fun warning(
    message: String,
    error: Throwable?,
  ) = Unit

  override fun error(
    message: String,
    error: Throwable?,
  ) = Unit
}
