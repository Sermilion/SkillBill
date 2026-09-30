package skillbill.cli.scaffold.payload

import kotlinx.serialization.json.JsonObject
import skillbill.application.scaffold.decodeScaffoldPayloadObject
import skillbill.application.scaffold.model.ScaffoldInvocationArgs
import skillbill.application.scaffold.runScaffoldInvocation
import skillbill.cli.kernel.cli.CliOutput
import skillbill.cli.kernel.cli.CliRunState
import skillbill.cli.model.CliExecutionResult
import skillbill.cli.model.CliFormat
import skillbill.cli.scaffold.commands.CreateAndFillArgs
import skillbill.cli.scaffold.commands.NativeScaffoldPayloadPathArgs
import skillbill.cli.scaffold.commands.NativeScaffoldRunArgs
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.error.core.SkillBillRuntimeException
import skillbill.ports.scaffold.ScaffoldGateway
import skillbill.ports.scaffold.model.ScaffoldRenderResult
import java.nio.file.Path

internal fun runNativeScaffoldPayload(args: NativeScaffoldPayloadPathArgs): CliExecutionResult {
  val payload =
    try {
      args.transform(readScaffoldPayload(args.payloadPath, args.run.state))
    } catch (error: SkillBillRuntimeException) {
      return errorResult(error.message.orEmpty(), args.run.format)
    } catch (error: IllegalArgumentException) {
      return errorResult(error.message.orEmpty(), args.run.format)
    }
  return runNativeScaffoldPayload(payload, args.run)
}

internal fun runNativeScaffoldPayload(
  payload: Map<String, *>,
  run: NativeScaffoldRunArgs,
): CliExecutionResult {
  val payloadText =
    try {
      JsonCodec.mapToJsonString(payload.mapValues { (_, value) -> value })
    } catch (error: SkillBillRuntimeException) {
      return errorResult(error.message.orEmpty(), run.format)
    }
  val payloadObject =
    try {
      decodeScaffoldPayloadObject(payloadText)
    } catch (error: IllegalArgumentException) {
      return errorResult(error.message.orEmpty(), run.format)
    }
  return runNativeScaffoldPayload(payloadObject, run)
}

internal fun runNativeScaffoldPayload(
  payload: JsonObject,
  run: NativeScaffoldRunArgs,
): CliExecutionResult {
  val dryRun = run.dryRun
  val format = run.format
  val inputs = run.inputs
  val scaffoldGateway = run.scaffoldGateway
  val outcome =
    try {
      runScaffoldInvocation(
        scaffoldGateway,
        ScaffoldInvocationArgs(
          payload = payload,
          invocationRepositoryRoot = inputs.repositoryRoot,
          dryRun = dryRun,
          registerExternalSources = true,
          externalAddonOverlayService = run.externalAddonOverlayService,
          userHome = inputs.userHome,
          environment = inputs.environment,
          clock = run.clock,
        ),
      )
    } catch (error: SkillBillRuntimeException) {
      return errorResult(error.message.orEmpty(), format)
    }
  val result = outcome.scaffoldResult
  val created = result.run { createdFiles }.map { path -> path.toString() }
  val presentation =
    buildMap {
      put(SharedPayloadKeys.STATUS, if (outcome.registrationFailure == null) "ok" else "partial")
      put("session_id", outcome.sessionId)
      put("skill_path", result.skillPath.toString())
      put("dry_run", dryRun)
      put("created_files", created)
      put("manifest_edits", result.manifestEdits.map { path -> path.toString() })
      put("manifest_edit_previews", result.manifestPreviews.mapKeys { (path, _) -> path.toString() })
      put("notes", result.notes)
      outcome.registrationFailure?.let { failure ->
        put("registration_error", failure)
      }
    }
  return CliExecutionResult(
    exitCode = if (outcome.registrationFailure == null) 0 else 1,
    stdout = CliOutput.emit(presentation, format),
    payload = presentation,
  )
}

internal fun createAndFillResult(args: CreateAndFillArgs): CliExecutionResult {
  val content = args.content
  val format = args.format
  return when {
    content.interactive || content.payload == null ->
      unsupportedNativeScaffoldResult(
        retiredInteractiveModeMessage(
          "create-and-fill",
          "skill-bill create-and-fill --payload <file> --body-file <file>",
        ),
        format,
      )
    content.editor ->
      unsupportedNativeScaffoldResult(
        "create-and-fill --payload --editor is not supported by the native Kotlin scaffold path yet.",
        format,
      )
    content.body != null && content.bodyFile != null ->
      errorResult("--body and --body-file are mutually exclusive.", format)
    else ->
      runNativeScaffoldPayload(
        NativeScaffoldPayloadPathArgs(
          payloadPath = content.payload,
          run =
            NativeScaffoldRunArgs(
              dryRun = args.dryRun,
              format = format,
              state = args.state,
              inputs = args.inputs,
              clock = args.clock,
              scaffoldGateway = args.scaffoldGateway,
            ),
          transform = { scaffoldPayload ->
            createAndFillScaffoldPayload(scaffoldPayload, content.body, content.bodyFile, args.state)
          },
        ),
      )
  }
}

internal fun errorResult(
  message: String,
  format: CliFormat,
): CliExecutionResult {
  val presentation =
    mapOf(
      SharedPayloadKeys.STATUS to "error",
      "error" to message,
    )
  return CliExecutionResult(
    exitCode = 1,
    stdout = CliOutput.emit(presentation, format),
    payload = presentation,
  )
}

internal fun authoringResult(
  format: CliFormat,
  successExitCode: (Map<String, Any?>) -> Int = { 0 },
  block: () -> Map<String, Any?>,
): CliExecutionResult =
  try {
    val payload = block()
    CliExecutionResult(
      exitCode = successExitCode(payload),
      stdout = CliOutput.emit(payload, format),
      payload = payload,
    )
  } catch (error: SkillBillRuntimeException) {
    errorResult(error.message.orEmpty(), format)
  } catch (error: IllegalArgumentException) {
    errorResult(error.message.orEmpty(), format)
  }

internal fun completeRenderText(
  state: CliRunState,
  repoRoot: Path,
  skillName: String,
  dryRun: Boolean,
  scaffoldGateway: ScaffoldGateway,
) = try {
  val rendered = scaffoldGateway.render(repoRoot, skillName)
  state.completeText(rendered.stdout, rendered.toCliPayload(dryRun))
} catch (error: SkillBillRuntimeException) {
  state.result = errorResult(error.message.orEmpty(), CliFormat.TEXT)
} catch (error: IllegalArgumentException) {
  state.result = errorResult(error.message.orEmpty(), CliFormat.TEXT)
}

internal fun ScaffoldRenderResult.toCliPayload(dryRun: Boolean): Map<String, Any?> =
  mapOf(
    "repo_root" to repoRoot.toString(),
    "skill_name" to skillName,
    "blocks" to
      blocks.map { block ->
        mapOf(
          "header" to block.header,
          "content" to block.content,
        )
      },
    "dry_run" to dryRun,
  )

internal fun retiredInteractiveModeMessage(
  command: String,
  replacement: String,
): String = "$command interactive mode was retired in SKILL-32; use `$replacement` instead."

internal fun retiredEditorModeMessage(
  command: String,
  replacement: String,
): String = "$command editor mode was retired in SKILL-32; use `$replacement` instead."

internal fun unsupportedNativeScaffoldResult(
  message: String,
  format: CliFormat,
): CliExecutionResult {
  val presentation =
    mapOf(
      SharedPayloadKeys.STATUS to "unsupported",
      "error" to message,
    )
  return CliExecutionResult(
    exitCode = 1,
    stdout = CliOutput.emit(presentation, format),
    payload = presentation,
  )
}
