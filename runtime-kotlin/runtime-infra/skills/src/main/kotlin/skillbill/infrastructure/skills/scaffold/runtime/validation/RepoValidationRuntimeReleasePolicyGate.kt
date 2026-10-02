package skillbill.infrastructure.skills.scaffold.runtime.validation

import skillbill.infrastructure.contracts.sha256Hex
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile

internal fun RepoValidationRuntimeReleasePolicy.releaseLicensePolicyViolation(
  repoRoot: Path,
  metadata: ReleaseRefMetadata,
): String? {
  val licenseFile = repoRoot.resolve("LICENSE")
  if (!licenseFile.isRegularFile()) {
    return "Release ${metadata.tag} requires root LICENSE with the MIT license."
  }
  val normalized = Files.readString(licenseFile).replace("\r\n", "\n").trimEnd()
  return if (sha256Hex(normalized) != RepoValidationRuntimeReleasePolicy.NORMALIZED_MIT_LICENSE_SHA256) {
    "Release ${metadata.tag} requires the complete current MIT license."
  } else {
    null
  }
}
