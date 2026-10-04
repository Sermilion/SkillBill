package skillbill.ports.validation

import skillbill.ports.validation.model.ReleaseRefMetadata
import skillbill.ports.validation.model.ReleaseRefValidation
import skillbill.ports.validation.model.RepoValidationReport
import java.nio.file.Path

interface RepoValidationGateway {
  fun validateRepo(repoRoot: Path): RepoValidationReport

  fun validateReleaseRef(
    repoRoot: Path,
    rawRef: String,
    forcePrerelease: Boolean,
  ): ReleaseRefValidation

  fun appendGithubOutput(
    outputPath: Path,
    metadata: ReleaseRefMetadata,
  )
}
