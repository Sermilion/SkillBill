package skillbill.cli.scaffold.payload

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import skillbill.application.scaffold.SCAFFOLD_PAYLOAD_NOT_OBJECT_MESSAGE
import skillbill.application.scaffold.decodeScaffoldPayloadObject
import skillbill.cli.kernel.cli.CliRunState
import skillbill.cli.model.CliFormat
import skillbill.error.shellcontent.InvalidScaffoldPayloadError
import skillbill.scaffold.model.SkillKind
import java.nio.file.Path

internal data class NativeScaffoldRunOptions(
  val dryRun: Boolean,
  val format: CliFormat,
  val withExternalAddonOverlay: Boolean,
)

internal data class CreateAndFillContentArgs(
  val payload: String?,
  val interactive: Boolean,
  val body: String?,
  val bodyFile: String?,
  val editor: Boolean,
)

internal data class NewAddonPayloadArgs(
  val platform: String?,
  val name: String?,
  val body: String?,
  val bodyFile: String?,
  val addonLocationPath: String?,
  val consumerSkillDirs: List<String>,
)

internal fun createAndFillContentPayload(
  body: String?,
  bodyFile: String?,
  state: CliRunState,
): JsonObject {
  val contentBody =
    body ?: bodyFile?.let { path ->
      readCliTextFile(path, state)
    }
  return contentBody?.let { buildJsonObject { put("content_body", it) } } ?: JsonObject(emptyMap())
}

internal fun createAndFillScaffoldPayload(
  scaffoldPayload: JsonObject,
  body: String?,
  bodyFile: String?,
  state: CliRunState,
): JsonObject {
  val kind = scaffoldPayload["kind"]?.jsonPrimitive?.contentOrNull.orEmpty()
  require(kind !in setOf(SkillKind.PLATFORM_PACK.wireValue, SkillKind.ADD_ON.wireValue)) {
    "create-and-fill can only scaffold one content-managed skill; kind '$kind' is not supported."
  }
  return JsonObject(scaffoldPayload + createAndFillContentPayload(body, bodyFile, state))
}

internal fun newAddonPayload(
  args: NewAddonPayloadArgs,
  state: CliRunState,
): Map<String, Any> =
  buildMap {
    put("scaffold_payload_version", "1.0")
    put("kind", SkillKind.ADD_ON.wireValue)
    put("platform", args.platform.orEmpty())
    put("name", args.name.orEmpty())
    (args.body ?: args.bodyFile?.let { path -> readCliTextFile(path, state) })
      ?.let { addonBody -> put("body", addonBody) }
    args.addonLocationPath?.takeIf { it.isNotBlank() }?.let { path -> put("addon_location_path", path) }
    if (args.consumerSkillDirs.isNotEmpty()) {
      put("consumer_skill_dirs", args.consumerSkillDirs)
    }
  }

internal fun readCliTextFile(
  path: String,
  state: CliRunState,
): String = if (path == "-") state.wholeStdinText() else Path.of(path).toFile().readText()

internal fun readScaffoldPayload(
  payloadPath: String?,
  state: CliRunState,
): JsonObject =
  decodeScaffoldPayloadObject(readScaffoldPayloadText(payloadPath, state))
    ?: throw InvalidScaffoldPayloadError(SCAFFOLD_PAYLOAD_NOT_OBJECT_MESSAGE)

internal fun readScaffoldPayloadText(
  payloadPath: String?,
  state: CliRunState,
): String =
  when {
    payloadPath == null -> throw IllegalArgumentException("--payload is required for this command.")
    payloadPath == "-" -> state.wholeStdinText()
    else -> Path.of(payloadPath).toFile().readText()
  }

internal fun Any?.orEmpty(): String = this as? String ?: ""
