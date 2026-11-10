package skillbill.infrastructure.workflow.decomposition

import java.nio.file.Path

internal data class DecompositionManifestBundleEntry(
  val target: Path,
  val staged: Path,
  val sha256: String,
  val beforeExisted: Boolean? = null,
  val beforeSha256: String? = null,
)

internal data class DecompositionManifestBundleTransaction(
  val marker: Path,
  val stagingDirectory: Path,
  val entries: List<DecompositionManifestBundleEntry>,
  val contractVersion: String = DecompositionManifestBundleJournal.BUNDLE_CONTRACT_VERSION,
  val protectedFiles: List<DecompositionManifestBundleProtectedFile> = emptyList(),
)

internal data class DecompositionManifestBundleProtectedFile(val path: Path, val sha256: String?)

internal data class DecompositionManifestBundleSnapshot(val path: Path, val existed: Boolean, val content: String?)
