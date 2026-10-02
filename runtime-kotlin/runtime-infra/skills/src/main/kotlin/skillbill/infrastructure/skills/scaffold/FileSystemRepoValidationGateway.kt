package skillbill.infrastructure.skills.scaffold

import me.tatarka.inject.annotations.Inject
import skillbill.infrastructure.skills.nativeagent.FileSystemNativeAgentPlatformPackLoader
import skillbill.infrastructure.skills.nativeagent.composition.NativeAgentCompositionContext
import skillbill.infrastructure.skills.nativeagent.validatePlannedNativeAgentWorkers
import skillbill.infrastructure.skills.scaffold.authoring.renderAuthoredContentBody
import skillbill.infrastructure.skills.scaffold.runtime.validation.ReleaseRefMetadata
import skillbill.infrastructure.skills.scaffold.runtime.validation.ReleaseRefValidationResult
import skillbill.infrastructure.skills.scaffold.runtime.validation.RepoValidationIssue
import skillbill.infrastructure.skills.scaffold.runtime.validation.RepoValidationIssueSeverity
import skillbill.infrastructure.skills.scaffold.runtime.validation.RepoValidationReport
import skillbill.infrastructure.skills.scaffold.runtime.validation.RepoValidationRuntime
import skillbill.ports.config.RepoLocalConfigPort
import skillbill.ports.config.model.ReadRepoLocalConfigRequest
import skillbill.ports.validation.RepoValidationGateway
import skillbill.ports.validation.model.ReleaseRefValidation
import java.nio.file.Path
import skillbill.ports.validation.model.ReleaseRefMetadata as PortReleaseRefMetadata
import skillbill.ports.validation.model.RepoValidationIssue as PortRepoValidationIssue
import skillbill.ports.validation.model.RepoValidationIssueSeverity as PortRepoValidationIssueSeverity
import skillbill.ports.validation.model.RepoValidationReport as PortRepoValidationReport

@Inject
class FileSystemRepoValidationGateway(
  private val repoLocalConfig: RepoLocalConfigPort,
) : RepoValidationGateway {
  override fun validateRepo(repoRoot: Path): PortRepoValidationReport {
    val normalizedRoot = repoRoot.toAbsolutePath().normalize()
    val compositionContext =
      NativeAgentCompositionContext(
        reviewContextBudgetBytes =
          repoLocalConfig
            .readRepoLocalConfig(ReadRepoLocalConfigRequest(normalizedRoot))
            .config
            .reviewContextBudget
            .maxLaneLaunchBytes,
        renderGovernedBody = ::renderAuthoredContentBody,
        packLoader = FileSystemNativeAgentPlatformPackLoader,
      )
    return RepoValidationRuntime.validateRepo(
      normalizedRoot,
      compositionContext,
      ::validatePlannedNativeAgentWorkers,
    ).toPortReport()
  }

  override fun validateReleaseRef(
    repoRoot: Path,
    rawRef: String,
    forcePrerelease: Boolean,
  ): ReleaseRefValidation =
    when (val result = RepoValidationRuntime.validateReleaseRef(repoRoot, rawRef, forcePrerelease)) {
      is ReleaseRefValidationResult.Valid -> ReleaseRefValidation.Valid(result.metadata.toPortMetadata())
      is ReleaseRefValidationResult.Rejected -> ReleaseRefValidation.Rejected(result.message)
    }

  override fun appendGithubOutput(
    outputPath: Path,
    metadata: PortReleaseRefMetadata,
  ) = RepoValidationRuntime.appendGithubOutput(
    outputPath,
    ReleaseRefMetadata(
      tag = metadata.tag,
      version = metadata.version,
      major = metadata.major,
      minor = metadata.minor,
      patch = metadata.patch,
      prerelease = metadata.prerelease,
      prereleaseIdentifier = metadata.prereleaseIdentifier,
      buildMetadata = metadata.buildMetadata,
    ),
  )

  private fun RepoValidationReport.toPortReport(): PortRepoValidationReport =
    PortRepoValidationReport(
      issues = issues,
      skillCount = skillCount,
      addonCount = addonCount,
      platformPackCount = platformPackCount,
      nativeAgentCount = nativeAgentCount,
      structuredIssues = structuredIssues.map { issue -> issue.toPortIssue() },
    )

  private fun RepoValidationIssue.toPortIssue(): PortRepoValidationIssue =
    PortRepoValidationIssue(
      severity = severity.toPortSeverity(),
      message = message,
      sourcePath = sourcePath,
      code = code,
      name = name,
      exceptionName = exceptionName,
    )

  private fun RepoValidationIssueSeverity.toPortSeverity(): PortRepoValidationIssueSeverity =
    when (this) {
      RepoValidationIssueSeverity.ERROR -> PortRepoValidationIssueSeverity.ERROR
      RepoValidationIssueSeverity.WARNING -> PortRepoValidationIssueSeverity.WARNING
      RepoValidationIssueSeverity.INFO -> PortRepoValidationIssueSeverity.INFO
    }

  private fun ReleaseRefMetadata.toPortMetadata(): PortReleaseRefMetadata =
    PortReleaseRefMetadata(
      tag = tag,
      version = version,
      major = major,
      minor = minor,
      patch = patch,
      prerelease = prerelease,
      prereleaseIdentifier = prereleaseIdentifier,
      buildMetadata = buildMetadata,
    )
}
