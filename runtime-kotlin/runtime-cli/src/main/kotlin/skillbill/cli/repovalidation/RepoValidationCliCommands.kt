package skillbill.cli.repovalidation

import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import me.tatarka.inject.annotations.Inject
import skillbill.cli.kernel.cli.CliRunState
import skillbill.cli.kernel.cli.DocumentedCliCommand
import skillbill.cli.kernel.cli.formatOption
import skillbill.cli.kernel.cli.resolveCliRepositoryRoot
import skillbill.cli.model.CliFormat
import skillbill.cli.model.CliRunInputs
import skillbill.contracts.SharedPayloadKeys
import skillbill.ports.validation.RepoValidationGateway
import skillbill.ports.validation.model.ReleaseRefValidation
import java.nio.file.Path

@Inject
class RepoValidationCliCommands(
  private val state: CliRunState,
  private val inputs: CliRunInputs,
  private val repoValidationGateway: RepoValidationGateway,
) {
  val commands =
    listOf(
      ValidateAgentConfigsCommand(state, inputs, repoValidationGateway),
      ValidateReleaseRefCommand(state, inputs, repoValidationGateway),
    )
}

class ValidateAgentConfigsCommand(
  private val state: CliRunState,
  private val inputs: CliRunInputs,
  private val repoValidationGateway: RepoValidationGateway,
) : DocumentedCliCommand(
    "validate-agent-configs",
    "Validate Skill Bill governed skills, platform packs, add-ons, docs catalog, and workflow contracts.",
  ) {
  private val repoRoot by option(
    "--repo-root",
    help = "Repository root to inspect. Defaults to the invocation repository root.",
  )
  private val format by formatOption()

  override fun run() {
    val report = repoValidationGateway.validateRepo(resolveCliRepositoryRoot(repoRoot, inputs))
    val payload = report.toContract().toPayload()
    if (format == CliFormat.JSON) {
      state.complete(payload, format, exitCode = if (report.passed) 0 else 1)
      return
    }

    val text =
      if (report.passed) {
        buildString {
          appendLine("Agent-config validation passed.")
          appendLine(
            "Validated ${report.skillCount} skills, ${report.addonCount} governed add-on files, " +
              "${report.platformPackCount} platform packs, ${report.nativeAgentCount} native agents, README catalog, " +
              "skill references, and workflow contracts.",
          )
        }
      } else {
        buildString {
          appendLine("Agent-config validation failed:")
          report.issues.forEach { issue -> appendLine("- $issue") }
        }
      }
    state.completeText(text, payload, exitCode = if (report.passed) 0 else 1)
  }
}

class ValidateReleaseRefCommand(
  private val state: CliRunState,
  private val inputs: CliRunInputs,
  private val repoValidationGateway: RepoValidationGateway,
) : DocumentedCliCommand(
    "validate-release-ref",
    "Validate a release tag and emit release metadata.",
  ) {
  private val ref by argument(help = "Tag or refs/tags/... reference to validate.").optional()
  private val githubOutput by option(
    "--github-output",
    help = "Optional file path where GitHub Actions step outputs should be appended.",
  )
  private val repoRoot by option(
    "--repo-root",
    help = "Repository root whose LICENSE is evaluated. Defaults to the invocation repository root.",
  )
  private val forcePrerelease by option(
    "--force-prerelease",
    help = "Require a manual staging version to carry a SemVer prerelease identifier.",
  ).flag()
  private val format by formatOption()

  override fun run() {
    val rawRef =
      ref
        ?: inputs.environment["GITHUB_REF_NAME"]
        ?: inputs.environment["GITHUB_REF"]
    if (rawRef == null) {
      state.completeText(
        "No release ref supplied. Pass a tag or set GITHUB_REF_NAME.\n",
        mapOf(
          SharedPayloadKeys.STATUS to "failed",
          "error" to "No release ref supplied. Pass a tag or set GITHUB_REF_NAME.",
        ),
        exitCode = 1,
      )
      return
    }

    val metadata =
      when (
        val validation =
          repoValidationGateway.validateReleaseRef(resolveCliRepositoryRoot(repoRoot, inputs), rawRef, forcePrerelease)
      ) {
        is ReleaseRefValidation.Valid -> validation.metadata
        is ReleaseRefValidation.Rejected -> {
          val payload = mapOf(SharedPayloadKeys.STATUS to "failed", "error" to validation.message)
          if (format == CliFormat.JSON) {
            state.complete(payload, format, exitCode = 1)
          } else {
            state.completeText("${validation.message}\n", payload, exitCode = 1)
          }
          return
        }
      }

    githubOutput?.let { outputPath ->
      repoValidationGateway.appendGithubOutput(Path.of(outputPath), metadata)
    }
    state.complete(metadata.toContract().toPayload(), format)
  }
}
