package skillbill.application.updatecheck

import skillbill.application.system.SystemService
import skillbill.application.updatecheck.model.RECOMMENDED_INSTALL_COMMAND
import skillbill.application.updatecheck.model.UpdateCheckStatus
import skillbill.model.RuntimeVersion
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.process.ReleaseCatalogPort
import skillbill.ports.process.model.ReleaseCatalogEntry
import skillbill.ports.process.model.ReleaseCatalogResult
import skillbill.ports.telemetry.model.TelemetrySettingsLoad
import skillbill.ports.telemetry.transport.TelemetrySettingsProvider
import skillbill.telemetry.model.TelemetrySettings
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UpdateCheckServiceTest {
  private val installedVersion = "0.3.0-SNAPSHOT"

  @Test
  fun `maps update available up to date and ahead of release`() {
    val update = service(releases("v0.4.0")).check(includePrereleases = false)
    assertEquals(UpdateCheckStatus.UPDATE_AVAILABLE, update.status)
    assertEquals(installedVersion, update.installedVersion)
    assertEquals("v0.4.0", update.latestVersion)
    assertEquals(RECOMMENDED_INSTALL_COMMAND, update.recommendedInstallCommand)

    val upToDate = service(releases("v0.3.0-SNAPSHOT")).check(includePrereleases = true)
    assertEquals(UpdateCheckStatus.UP_TO_DATE, upToDate.status)
    assertNull(upToDate.recommendedInstallCommand)

    val ahead = service(releases("v0.2.0")).check(includePrereleases = false)
    assertEquals(UpdateCheckStatus.AHEAD_OF_RELEASE, ahead.status)
  }

  @Test
  fun `same-base snapshot is behind the matching release`() {
    val update = service(releases("v0.3.0")).check(includePrereleases = false)
    assertEquals(UpdateCheckStatus.UPDATE_AVAILABLE, update.status)
    assertEquals("v0.3.0", update.latestVersion)
    assertEquals(RECOMMENDED_INSTALL_COMMAND, update.recommendedInstallCommand)
  }

  @Test
  fun `selects stable releases by default and prereleases when requested`() {
    val catalog = releases("v0.4.0-rc.1", "v0.2.0")

    val stable = service(catalog).check(includePrereleases = false)
    assertEquals(UpdateCheckStatus.AHEAD_OF_RELEASE, stable.status)
    assertEquals("v0.2.0", stable.latestVersion)

    val prerelease = service(catalog).check(includePrereleases = true)
    assertEquals(UpdateCheckStatus.UPDATE_AVAILABLE, prerelease.status)
    assertEquals("v0.4.0-rc.1", prerelease.latestVersion)
  }

  @Test
  fun `ignores a newer plugin release and selects the newest runtime semver`() {
    val catalog =
      ReleaseCatalogResult.Releases(
        listOf(releaseEntry("plugin-v9.9.9", prerelease = false), releaseEntry("v0.4.0")),
      )

    val result = service(catalog).check(includePrereleases = false)

    assertEquals("v0.4.0", result.latestVersion)
    assertEquals(UpdateCheckStatus.UPDATE_AVAILABLE, result.status)
    assertNull(result.reason)
  }

  @Test
  fun `an unversioned build is never told to update`() {
    val result =
      UpdateCheckService(
        systemService = systemService("0.0.0-SNAPSHOT"),
        releaseCatalog = FakeReleaseCatalog { error("release list must not be consulted") },
      ).check(includePrereleases = false)

    assertEquals(UpdateCheckStatus.UNKNOWN, result.status)
    assertEquals("0.0.0-SNAPSHOT", result.installedVersion)
    assertNull(result.recommendedInstallCommand)
  }

  @Test
  fun `missing and malformed installed versions stay unknown without release lookup`() {
    val missing = service(releases("v9.9.9"), versionValue = "").check(false)
    assertEquals(UpdateCheckStatus.UNKNOWN, missing.status)
    assertEquals("missing local version metadata", missing.reason)

    val malformed = service(releases("v9.9.9"), versionValue = "not-semver").check(false)
    assertEquals(UpdateCheckStatus.UNKNOWN, malformed.status)
    assertEquals("local version is not semver", malformed.reason)
  }

  @Test
  fun `an empty release catalog stays unknown`() {
    val result = service(ReleaseCatalogResult.Releases(emptyList())).check(includePrereleases = false)
    assertEquals(UpdateCheckStatus.UNKNOWN, result.status)
    assertEquals("no GitHub releases returned", result.reason)
  }

  @Test
  fun `maps soft failures to unknown`() {
    val catalogFailure =
      service(ReleaseCatalogResult.Failure("GitHub API rate limit or access limit")).check(false)
    assertEquals(UpdateCheckStatus.UNKNOWN, catalogFailure.status)
    assertEquals("GitHub API rate limit or access limit", catalogFailure.reason)

    val noSemver = service(releases("nonsense")).check(false)
    assertEquals(UpdateCheckStatus.UNKNOWN, noSemver.status)
    assertEquals("no usable semver GitHub release found", noSemver.reason)
  }

  @Test
  fun `a malformed entry among valid releases stays unknown`() {
    val catalog =
      ReleaseCatalogResult.Releases(
        listOf(
          releaseEntry("v0.4.0"),
          ReleaseCatalogEntry.Malformed(prerelease = false, draft = false),
          releaseEntry("v0.2.0"),
        ),
      )

    val result = service(catalog).check(includePrereleases = false)

    assertEquals(UpdateCheckStatus.UNKNOWN, result.status)
    assertEquals("malformed release entry", result.reason)
  }

  @Test
  fun `overlapping checks keep independent failure reasons and valid results`() {
    val validCatalog = releases("v0.4.0")
    val malformedCatalog =
      ReleaseCatalogResult.Releases(listOf(ReleaseCatalogEntry.Malformed(prerelease = false, draft = false)))
    val callCount = AtomicInteger(0)
    val firstEntered = CountDownLatch(1)
    val releaseFirst = CountDownLatch(1)
    val shared =
      UpdateCheckService(
        systemService = systemService(installedVersion),
        releaseCatalog =
          FakeReleaseCatalog {
            when (callCount.incrementAndGet()) {
              1 -> {
                firstEntered.countDown()
                releaseFirst.await()
                malformedCatalog
              }
              else -> {
                releaseFirst.countDown()
                validCatalog
              }
            }
          },
      )
    var malformedReason: String? = null
    var validStatus: UpdateCheckStatus? = null
    val malformedThread =
      Thread {
        malformedReason = shared.check(includePrereleases = false).reason
      }
    val validThread =
      Thread {
        firstEntered.await()
        validStatus = shared.check(includePrereleases = false).status
      }
    malformedThread.start()
    validThread.start()
    malformedThread.join()
    validThread.join()
    assertEquals("malformed release entry", malformedReason)
    assertEquals(UpdateCheckStatus.UPDATE_AVAILABLE, validStatus)
  }

  private fun service(
    catalog: ReleaseCatalogResult,
    versionValue: String = installedVersion,
  ): UpdateCheckService =
    UpdateCheckService(
      systemService = systemService(versionValue),
      releaseCatalog = FakeReleaseCatalog { catalog },
    )
}

internal fun systemService(versionValue: String): SystemService =
  SystemService(
    TestDatabaseSessionFactory(),
    TestTelemetrySettingsProvider,
    NoopRuntimeDiagnostics,
    RuntimeVersion(versionValue),
  )

private class FakeReleaseCatalog(
  private val response: () -> ReleaseCatalogResult,
) : ReleaseCatalogPort {
  override fun listReleases(): ReleaseCatalogResult = response()
}

private fun releases(vararg tags: String): ReleaseCatalogResult =
  ReleaseCatalogResult.Releases(tags.map { tag -> releaseEntry(tag) })

private fun releaseEntry(
  tag: String,
  prerelease: Boolean = tag.contains("-"),
): ReleaseCatalogEntry =
  ReleaseCatalogEntry.Release(
    tagName = tag,
    url = "https://github.com/Sermilion/skill-bill/releases/tag/$tag",
    notes = null,
    prerelease = prerelease,
    draft = false,
  )

private class TestDatabaseSessionFactory : DatabaseSessionFactory {
  private val dbPath = Files.createTempDirectory("skillbill-update-check-db").resolve("metrics.db")

  override fun resolveDbPath(): Path = dbPath

  override fun databaseExists(): Boolean = Files.exists(resolveDbPath())

  override fun <T> read(block: (UnitOfWork) -> T): T = error("unused")

  override fun <T> selfManagedWrite(block: (UnitOfWork) -> T): T = transaction(block)

  override fun <T> transaction(block: (UnitOfWork) -> T): T = error("unused")
}

private object TestTelemetrySettingsProvider : TelemetrySettingsProvider {
  override fun load(materialize: Boolean): TelemetrySettings = error("unused")

  override fun loadOrUnavailable(materialize: Boolean): TelemetrySettingsLoad =
    TelemetrySettingsLoad.Loaded(load(materialize))
}
