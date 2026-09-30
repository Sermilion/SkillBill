package skillbill.cli.scaffold.commands

import com.github.ajalt.clikt.core.UsageError
import skillbill.cli.kernel.cli.CliRunState
import skillbill.cli.model.CliExecutionResult
import skillbill.cli.model.CliFormat
import skillbill.cli.model.CliRunInputs
import skillbill.cli.scaffold.payload.authoringResult
import skillbill.cli.scaffold.payload.errorResult
import skillbill.cli.scaffold.payload.readCliTextFile
import skillbill.cli.scaffold.payload.retiredEditorModeMessage
import skillbill.cli.scaffold.payload.retiredInteractiveModeMessage
import skillbill.cli.scaffold.payload.unsupportedNativeScaffoldResult
import skillbill.ports.scaffold.ScaffoldGateway
import java.nio.file.Path

internal data class EditSkillRunArgs(
  val state: CliRunState,
  val inputs: CliRunInputs,
  val scaffoldGateway: ScaffoldGateway,
  val skillName: String,
  val repoRoot: String,
  val bodyFile: String?,
  val editor: Boolean,
  val section: String?,
  val format: CliFormat,
)

internal data class FillSkillRunArgs(
  val state: CliRunState,
  val inputs: CliRunInputs,
  val scaffoldGateway: ScaffoldGateway,
  val skillName: String,
  val repoRoot: String,
  val body: String?,
  val bodyFile: String?,
  val section: String?,
  val format: CliFormat,
)

internal fun resolveRenderSkillName(
  positionalSkillName: String?,
  optionSkillName: String?,
): String =
  when {
    positionalSkillName != null && optionSkillName != null ->
      throw UsageError("Provide the skill name either as an argument or with --skill-name, not both.")
    positionalSkillName != null -> requireNotNull(positionalSkillName)
    optionSkillName != null -> requireNotNull(optionSkillName)
    else -> throw UsageError("Provide a skill name as an argument or with --skill-name.")
  }

internal fun editSkillResult(args: EditSkillRunArgs): CliExecutionResult =
  when {
    args.editor ->
      unsupportedNativeScaffoldResult(
        retiredEditorModeMessage(
          "edit --editor",
          "skill-bill fill ${args.skillName} --body-file <file>",
        ),
        args.format,
      )
    args.bodyFile != null ->
      authoringResult(args.format) {
        args.scaffoldGateway.editWithBodyFile(
          Path.of(args.repoRoot),
          args.skillName,
          readCliTextFile(args.bodyFile, args.state),
          args.section,
        ).toCliMap()
      }
    else ->
      unsupportedNativeScaffoldResult(
        retiredInteractiveModeMessage(
          "edit",
          "skill-bill fill ${args.skillName} --body-file <file>",
        ),
        args.format,
      )
  }

internal fun fillSkillResult(args: FillSkillRunArgs): CliExecutionResult =
  when {
    args.body != null && args.bodyFile != null ->
      errorResult("--body and --body-file are mutually exclusive.", args.format)
    args.body == null && args.bodyFile == null -> errorResult("Either --body or --body-file is required.", args.format)
    else ->
      authoringResult(args.format) {
        args.scaffoldGateway.fill(
          Path.of(args.repoRoot),
          args.skillName,
          args.body ?: readCliTextFile(args.bodyFile.orEmpty(), args.state),
          args.section,
        ).toCliMap()
      }
  }
