package skillbill.ports.qa

import skillbill.ports.qa.model.QaDeviceLease
import skillbill.ports.qa.model.QaLeaseReleaseOutcome
import skillbill.ports.qa.model.QaLeaseResult
import java.nio.file.Path

/**
 * Per-device QA leases under the Git common directory of [Path] repository roots. A lease names the QA invocation
 * that owns one device, identified only by its sha256 fingerprint, and stays live while that invocation's runtime
 * process is alive.
 */
interface QaDeviceLeasePort {
  /**
   * Takes the device for [invocationId]. A live or unverifiable foreign owner yields [QaLeaseResult.Contended] and
   * leaves its record untouched; an owner whose process is confirmed dead is reclaimed.
   */
  fun acquire(
    repoRoot: Path,
    deviceFingerprint: String,
    invocationId: String,
  ): QaLeaseResult

  /**
   * Releases [lease] when [QaDeviceLease.invocationId] still owns it. A missing or foreign-owned lease yields
   * [QaLeaseReleaseOutcome.NotHeld] and stays untouched.
   */
  fun release(
    repoRoot: Path,
    lease: QaDeviceLease,
  ): QaLeaseReleaseOutcome

  /** Releases every lease [invocationId] still holds, reporting each device it could not release. */
  fun releaseAllFor(
    repoRoot: Path,
    invocationId: String,
  ): List<QaLeaseReleaseOutcome>
}
