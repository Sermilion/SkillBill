package skillbill.application.updatecheck

import me.tatarka.inject.annotations.Inject
import skillbill.application.system.SystemService
import skillbill.application.updatecheck.model.RECOMMENDED_INSTALL_COMMAND
import skillbill.application.updatecheck.model.Semver
import skillbill.application.updatecheck.model.UpdateCheckResult
import skillbill.application.updatecheck.model.UpdateCheckStatus
import skillbill.ports.process.ReleaseCatalogPort
import skillbill.ports.process.model.ReleaseCatalogEntry
import skillbill.ports.process.model.ReleaseCatalogResult

@Inject
class UpdateCheckService(
  private val systemService: SystemService,
  private val releaseCatalog: ReleaseCatalogPort,
) {
  fun check(includePrereleases: Boolean): UpdateCheckResult {
    val installedVersion = systemService.version().version
    val installed = installedVersion.takeIf(String::isNotBlank)?.let(Semver::parse)
    return when {
      installedVersion.isBlank() -> unknown("missing local version metadata")
      installed == null -> unknown("local version is not semver", installedVersion = installedVersion)
      installed.isUnversionedBuild ->
        unknown(
          "local build carries no version provenance; reinstall with `sona update --release <tag>`",
          installedVersion = installedVersion,
        )
      else -> checkReleases(installedVersion, installed, includePrereleases)
    }
  }

  private fun checkReleases(
    installedVersion: String,
    installed: Semver,
    includePrereleases: Boolean,
  ): UpdateCheckResult =
    when (val catalog = releaseCatalog.listReleases()) {
      is ReleaseCatalogResult.Failure -> unknown(catalog.reason)
      is ReleaseCatalogResult.Releases -> {
        val selection = selectLatestRelease(catalog.entries, includePrereleases)
        selection.failure ?: selection.candidate?.let { latest ->
          updateResult(installedVersion, latest, updateStatus(installed, latest.version))
        } ?: unknown("release check did not complete")
      }
    }

  private fun updateResult(
    installedVersion: String,
    latest: ReleaseCandidate,
    status: UpdateCheckStatus,
  ): UpdateCheckResult =
    UpdateCheckResult(
      status = status,
      installedVersion = installedVersion,
      latestVersion = latest.tagName,
      releaseUrl = latest.url,
      releaseNotes = latest.body,
      recommendedInstallCommand =
        if (status == UpdateCheckStatus.UPDATE_AVAILABLE) {
          RECOMMENDED_INSTALL_COMMAND
        } else {
          null
        },
    )

  private fun updateStatus(
    installed: Semver,
    latest: Semver,
  ): UpdateCheckStatus =
    when {
      installed < latest -> UpdateCheckStatus.UPDATE_AVAILABLE
      installed > latest -> UpdateCheckStatus.AHEAD_OF_RELEASE
      else -> UpdateCheckStatus.UP_TO_DATE
    }

  private fun selectLatestRelease(
    releases: List<ReleaseCatalogEntry>,
    includePrereleases: Boolean,
  ): ReleaseSelection {
    val parsedCandidates = releases.map { release -> releaseCandidate(release, includePrereleases) }
    val candidates = parsedCandidates.mapNotNull { it.candidate }
    val reason =
      when {
        releases.isEmpty() -> "no GitHub releases returned"
        parsedCandidates.any { it.malformedEntry } -> "malformed release entry"
        candidates.isEmpty() -> "no usable semver GitHub release found"
        else -> null
      }
    if (reason != null) {
      return ReleaseSelection(failure = unknown(reason))
    }
    return ReleaseSelection(candidate = candidates.maxByOrNull { candidate -> candidate.version })
  }

  private fun releaseCandidate(
    release: ReleaseCatalogEntry,
    includePrereleases: Boolean,
  ): ReleaseCandidateParseResult {
    if (release.draft) return ReleaseCandidateParseResult()
    if (!includePrereleases && release.prerelease) {
      return ReleaseCandidateParseResult()
    }
    return when (release) {
      is ReleaseCatalogEntry.Malformed -> ReleaseCandidateParseResult(malformedEntry = true)
      is ReleaseCatalogEntry.Release -> candidateFromRelease(release, includePrereleases)
    }
  }

  private fun candidateFromRelease(
    release: ReleaseCatalogEntry.Release,
    includePrereleases: Boolean,
  ): ReleaseCandidateParseResult {
    val candidate =
      Semver.parse(release.tagName)
        ?.takeUnless { !includePrereleases && it.isPrerelease }
        ?.let { version ->
          ReleaseCandidate(tagName = release.tagName, version = version, url = release.url, body = release.notes)
        }
    return ReleaseCandidateParseResult(candidate = candidate)
  }

  private data class ReleaseCandidateParseResult(
    val candidate: ReleaseCandidate? = null,
    val malformedEntry: Boolean = false,
  )

  private data class ReleaseSelection(
    val candidate: ReleaseCandidate? = null,
    val failure: UpdateCheckResult? = null,
  )

  private data class ReleaseCandidate(
    val tagName: String,
    val version: Semver,
    val url: String,
    val body: String? = null,
  )
}

internal fun unknown(
  reason: String,
  installedVersion: String? = null,
): UpdateCheckResult =
  UpdateCheckResult(
    status = UpdateCheckStatus.UNKNOWN,
    installedVersion = installedVersion,
    reason = reason,
  )
