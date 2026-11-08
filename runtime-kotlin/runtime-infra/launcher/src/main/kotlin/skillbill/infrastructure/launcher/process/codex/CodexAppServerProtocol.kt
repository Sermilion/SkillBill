package skillbill.infrastructure.launcher.process.codex

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import skillbill.infrastructure.launcher.process.codex.CodexRpcKeys as K

data class CodexThreadSettings(
  val cwd: String,
  val model: String?,
  val effort: String?,
  val workspaceRoots: List<String> = emptyList(),
)

sealed interface CodexThreadOpening {
  data class Opened(val threadId: String) : CodexThreadOpening

  data class Refused(val reason: String) : CodexThreadOpening
}

object CodexAppServerProtocol {
  fun initializeParams(): JsonObject =
    buildJsonObject {
      put(K.CAPABILITIES, buildJsonObject { put(K.EXPERIMENTAL_API, true) })
      put(
        K.CLIENT_INFO,
        buildJsonObject {
          put(K.NAME, "skill-bill")
          put(K.TITLE, "Skill Bill")
          put(K.VERSION, "1.0.0")
        },
      )
    }

  fun initializedNotification(): JsonObject = buildJsonObject { put(K.METHOD, K.INITIALIZED) }

  fun threadMethod(savedThread: String?): String =
    if (savedThread == null) K.THREAD_START_METHOD else K.THREAD_RESUME_METHOD

  fun threadParams(
    settings: CodexThreadSettings,
    savedThread: String?,
  ): JsonObject =
    buildJsonObject {
      savedThread?.let { put(K.THREAD_ID, it) }
      put(K.CWD, settings.cwd)
      if (settings.workspaceRoots.isNotEmpty()) put(K.WORKSPACE_ROOTS, settings.roots())
      settings.model?.let { put(K.MODEL, it) }
      put(K.SANDBOX, K.BYPASS_SANDBOX)
      put(K.APPROVAL_POLICY, K.NEVER_POLICY)
    }

  fun threadOpening(
    result: JsonObject,
    settings: CodexThreadSettings,
    savedThread: String?,
  ): CodexThreadOpening {
    val returned = result.obj(K.THREAD)?.text(K.ID)
    val returnedModel = result.text(K.MODEL)
    val validIdentity = !returned.isNullOrBlank() && (savedThread == null || returned == savedThread)
    val validSettings =
      result.text(K.CWD) == settings.cwd && !returnedModel.isNullOrBlank() &&
        (settings.model == null || returnedModel == settings.model) &&
        result.obj(K.SANDBOX)?.text(K.TYPE) == K.BYPASS_POLICY
    return when {
      !validIdentity && savedThread != null ->
        CodexThreadOpening.Refused(
          "The saved Codex provider thread could not be resumed unchanged; start an explicit new session.",
        )
      returned.isNullOrBlank() || !validIdentity || !validSettings ->
        CodexThreadOpening.Refused(
          "Codex did not preserve the provider thread, selected model or checkout; the turn was not started.",
        )
      else -> CodexThreadOpening.Opened(returned)
    }
  }

  fun turnParams(
    settings: CodexThreadSettings,
    threadId: String,
    input: List<JsonObject>,
  ): JsonObject =
    buildJsonObject {
      put(K.THREAD_ID, threadId)
      put(K.CWD, settings.cwd)
      settings.model?.let { put(K.MODEL, it) }
      settings.effort?.let { put(K.EFFORT, it) }
      put(
        K.SANDBOX_POLICY,
        buildJsonObject {
          put(K.TYPE, K.BYPASS_POLICY)
        },
      )
      put(K.INPUT, JsonArray(input))
      put(K.APPROVAL_POLICY, K.NEVER_POLICY)
    }

  fun textInput(text: String): JsonObject =
    buildJsonObject {
      put(K.TYPE, "text")
      put(K.TEXT, text)
      put(K.TEXT_ELEMENTS, JsonArray(emptyList()))
    }

  fun localImageInput(path: String): JsonObject =
    buildJsonObject {
      put(K.TYPE, LOCAL_IMAGE_TYPE)
      put(K.PATH, path)
    }

  fun startedTurnId(result: JsonObject): String? {
    val turn = result.obj(K.TURN)
    val id = turn?.text(K.ID)
    return id?.takeIf { it.isNotBlank() && !turn.text(K.STATUS).isNullOrBlank() }
  }

  fun itemFailed(item: JsonObject): Boolean =
    item.text(K.STATUS) in setOf("failed", K.DECLINED) || item.obj(K.ERROR) != null ||
      ((item[K.EXIT_CODE] as? JsonPrimitive)?.intOrNull ?: 0) != 0

  fun providerError(
    error: JsonObject?,
    raw: JsonElement? = null,
  ): String =
    buildList {
      error?.text(K.MESSAGE)?.takeIf(String::isNotBlank)?.let(::add)
      listOf(K.CODE, K.ADDITIONAL_DETAILS, K.CODEX_ERROR_INFO, K.DATA).forEach { key ->
        error?.get(key)?.renderDiagnostic()?.takeIf(String::isNotBlank)?.let(::add)
      }
      raw?.takeIf { error == null }?.renderDiagnostic()?.takeIf(String::isNotBlank)?.let(::add)
    }.distinct().joinToString("\n")

  private fun CodexThreadSettings.roots(): JsonArray =
    JsonArray((listOf(cwd) + workspaceRoots).distinct().map(::JsonPrimitive))

  internal const val LOCAL_IMAGE_TYPE = "localImage"
}

internal fun JsonElement.renderDiagnostic(): String =
  (this as? JsonPrimitive)?.takeIf { it.isString }?.content ?: toString()
