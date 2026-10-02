package skillbill.cli.scaffold.payload

import kotlinx.serialization.json.JsonObject
import me.tatarka.inject.annotations.Inject
import skillbill.application.install.ExternalAddonOverlayService
import skillbill.application.scaffold.SCAFFOLD_PAYLOAD_NOT_OBJECT_MESSAGE
import skillbill.application.scaffold.decodeScaffoldPayloadObject
import skillbill.application.scaffold.model.ScaffoldInvocationArgs
import skillbill.application.scaffold.runScaffoldInvocation
import skillbill.cli.kernel.cli.CliRunState
import skillbill.cli.kernel.payload.CliPayloadStatus
import skillbill.cli.model.CliFormat
import skillbill.cli.model.CliRunInputs
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rethrowIfDatabaseFailure
import skillbill.ports.scaffold.ScaffoldGateway
import skillbill.ports.scaffold.model.ScaffoldRenderResult
import java.nio.file.Path
import java.time.Clock

@Inject
class NativeScaffoldPayloadRun(
  private val state: CliRunState,
  private val inputs: CliRunInputs,
  private val clock: Clock,
  private val scaffoldGateway: ScaffoldGateway,
  private val externalAddonOverlayService: ExternalAddonOverlayService,
) {
  internal fun runPayloadFile(
    payloadPath: String?,
    options: NativeScaffoldRunOptions,
    transform: (JsonObject) -> JsonObject = { it },
  ) {
    val payload =
      try {
        transform(readScaffoldPayload(payloadPath, state))
      } catch (error: SkillBillRuntimeException) {
        return state.completeScaffoldError(error.message.orEmpty(), options.format)
      } catch (error: IllegalArgumentException) {
        return state.completeScaffoldError(error.message.orEmpty(), options.format)
      }
    runPayload(payload, options)
  }

  internal fun runPayload(
    payload: Map<String, *>,
    options: NativeScaffoldRunOptions,
  ) {
    val payloadText =
      try {
        JsonCodec.mapToJsonString(payload.mapValues { (_, value) -> value })
      } catch (error: SkillBillRuntimeException) {
        return state.completeScaffoldError(error.message.orEmpty(), options.format)
      }
    val payloadObject = decodeScaffoldPayloadObject(payloadText)
    if (payloadObject == null) {
      state.completeScaffoldError(SCAFFOLD_PAYLOAD_NOT_OBJECT_MESSAGE, options.format)
    } else {
      runPayload(payloadObject, options)
    }
  }

  internal fun createAndFill(
    content: CreateAndFillContentArgs,
    options: NativeScaffoldRunOptions,
  ) {
    val format = options.format
    when {
      content.interactive || content.payload == null ->
        state.completeUnsupportedScaffold(
          retiredInteractiveModeMessage(
            "create-and-fill",
            "skill-bill create-and-fill --payload <file> --body-file <file>",
          ),
          format,
        )
      content.editor ->
        state.completeUnsupportedScaffold(
          "create-and-fill --payload --editor is not supported by the native Kotlin scaffold path yet.",
          format,
        )
      content.body != null && content.bodyFile != null ->
        state.completeScaffoldError("--body and --body-file are mutually exclusive.", format)
      else ->
        runPayloadFile(content.payload, options) { scaffoldPayload ->
          createAndFillScaffoldPayload(scaffoldPayload, content.body, content.bodyFile, state)
        }
    }
  }

  private fun runPayload(
    payload: JsonObject,
    options: NativeScaffoldRunOptions,
  ) {
    val outcome =
      try {
        runScaffoldInvocation(
          scaffoldGateway,
          ScaffoldInvocationArgs(
            payload = payload,
            invocationRepositoryRoot = inputs.repositoryRoot,
            dryRun = options.dryRun,
            registerExternalSources = true,
            externalAddonOverlayService = externalAddonOverlayService.takeIf { options.withExternalAddonOverlay },
            userHome = inputs.userHome,
            environment = inputs.environment,
            clock = clock,
          ),
        )
      } catch (error: SkillBillRuntimeException) {
        error.rethrowIfDatabaseFailure()
        return state.completeScaffoldError(error.message.orEmpty(), options.format)
      }
    val result = outcome.scaffoldResult
    val created = result.run { createdFiles }.map { path -> path.toString() }
    val presentation =
      buildMap {
        put(SharedPayloadKeys.STATUS, if (outcome.registrationFailure == null) CliPayloadStatus.OK else "partial")
        put("session_id", outcome.sessionId)
        put("skill_path", result.skillPath.toString())
        put("dry_run", options.dryRun)
        put("created_files", created)
        put("manifest_edits", result.manifestEdits.map { path -> path.toString() })
        put("manifest_edit_previews", result.manifestPreviews.mapKeys { (path, _) -> path.toString() })
        put("notes", result.notes)
        outcome.registrationFailure?.let { failure ->
          put("registration_error", failure)
        }
      }
    state.complete(presentation, options.format, if (outcome.registrationFailure == null) 0 else 1)
  }
}

internal fun CliRunState.completeScaffoldError(
  message: String,
  format: CliFormat,
) {
  complete(
    mapOf(
      SharedPayloadKeys.STATUS to "error",
      "error" to message,
    ),
    format,
    exitCode = 1,
  )
}

internal fun CliRunState.completeAuthoring(
  format: CliFormat,
  successExitCode: (Map<String, Any?>) -> Int = { 0 },
  block: () -> Map<String, Any?>,
) {
  try {
    val payload = block()
    complete(payload, format, successExitCode(payload))
  } catch (error: SkillBillRuntimeException) {
    error.rethrowIfDatabaseFailure()
    completeScaffoldError(error.message.orEmpty(), format)
  } catch (error: IllegalArgumentException) {
    completeScaffoldError(error.message.orEmpty(), format)
  }
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
  state.completeScaffoldError(error.message.orEmpty(), CliFormat.TEXT)
} catch (error: IllegalArgumentException) {
  state.completeScaffoldError(error.message.orEmpty(), CliFormat.TEXT)
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

internal fun CliRunState.completeUnsupportedScaffold(
  message: String,
  format: CliFormat,
) {
  complete(
    mapOf(
      SharedPayloadKeys.STATUS to "unsupported",
      "error" to message,
    ),
    format,
    exitCode = 1,
  )
}
