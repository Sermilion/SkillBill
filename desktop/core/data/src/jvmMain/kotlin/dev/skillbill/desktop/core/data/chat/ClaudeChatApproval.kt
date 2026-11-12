package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.data.approval.ApprovalGeneration
import dev.skillbill.desktop.core.data.approval.SessionApprovalGenerations
import dev.skillbill.desktop.core.data.approval.toCard
import dev.skillbill.desktop.core.data.session.DatabaseSessionManager
import dev.skillbill.desktop.core.data.session.DatabaseSessionComponent
import dev.skillbill.desktop.core.domain.model.ApprovalCard
import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import kotlinx.serialization.json.JsonObject
import me.tatarka.inject.annotations.Inject
import skillbill.approval.model.ApprovalExecutionMode
import skillbill.contracts.approval.ApprovalRequestRecord
import skillbill.infrastructure.launcher.process.codex.CodexApprovalRequests
import skillbill.infrastructure.launcher.process.cursor.CursorAcpSession
import skillbill.infrastructure.launcher.process.interaction.CLAUDE_APPROVAL_PROVIDER
import skillbill.infrastructure.launcher.process.interaction.ClaudeApprovalRequests
import skillbill.infrastructure.launcher.process.interaction.ClaudeInitializeOutcome
import skillbill.ports.approval.ApprovalOwnerEffects
import skillbill.ports.approval.model.ApprovalDeliveryState
import skillbill.ports.approval.model.ApprovalOwnerOpenRequest
import skillbill.ports.approval.model.ApprovalOwnerOpenResult
import skillbill.ports.approval.model.ApprovalOwnerSession
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.time.Clock
import java.util.concurrent.atomic.AtomicReference

internal sealed interface ChatApprovalOpening {
  data class Opened(val approval: ClaudeChatApproval) : ChatApprovalOpening

  data class Refused(val reason: String) : ChatApprovalOpening
}

/** Opens the broker owner generation a live chat process needs before it may start. */
internal fun interface ChatApprovalOpener {
  fun open(
    sessionId: String,
    repositoryRoot: String,
    mode: ChatPermissionMode,
    profileIdentity: String,
    writeLine: (String) -> Boolean,
    stopProcess: () -> Unit,
    onCard: (ApprovalCard) -> Unit,
  ): ChatApprovalOpening
}

/**
 * Opens one broker owner generation per live chat process. With no open database session there is no broker and no
 * operator surface, so the chat is refused rather than started without a way to answer its permission requests.
 */
@Inject
@SingleIn(AppScope::class)
class ChatApprovalAuthority(
  private val sessions: DatabaseSessionManager,
  private val diagnostics: Diagnostics,
) {
  internal fun open(
    sessionId: String,
    repositoryRoot: String,
    mode: ChatPermissionMode,
    profileIdentity: String,
    writeLine: (String) -> Boolean,
    stopProcess: () -> Unit,
    onCard: (ApprovalCard) -> Unit,
  ): ChatApprovalOpening {
    // The generation belongs to the session component that was current when it opened, and ends with that session.
    val component = sessions.sessions.value ?: return ChatApprovalOpening.Refused(NO_SESSION_REASON)
    val approval = ClaudeChatApproval(repositoryRoot, writeLine, stopProcess, diagnostics, onCard)
    val request = chatOpenRequest(sessionId, repositoryRoot, CLAUDE_APPROVAL_PROVIDER, mode, profileIdentity)
    val refusal = openOwnerGeneration(component, request, approval, { owner, generations ->
      approval.bind(owner)
      approval.admitTo(generations)
    }, approval::release)
    return refusal?.let(ChatApprovalOpening::Refused) ?: ChatApprovalOpening.Opened(approval)
  }

  internal fun openCodex(
    sessionId: String,
    repositoryRoot: String,
    mode: ChatPermissionMode,
    profileIdentity: String,
    writeLine: (String) -> Boolean,
    stopProcess: () -> Unit,
    onCard: (ApprovalCard) -> Unit,
  ): CodexChatApprovalOpening {
    if (profileIdentity.isBlank()) {
      return CodexChatApprovalOpening.Refused(
        "Codex approval scope is unavailable because its profile identity is unknown.",
      )
    }
    val component = sessions.sessions.value ?: return CodexChatApprovalOpening.Refused(NO_SESSION_REASON)
    val approval = CodexChatApproval(writeLine, stopProcess, diagnostics, onCard, repositoryRoot)
    val request = chatOpenRequest(sessionId, repositoryRoot, CODEX_CHAT_APPROVAL_PROVIDER, mode, profileIdentity)
    val refusal = openOwnerGeneration(component, request, approval, { owner, generations ->
      approval.bind(owner)
      approval.admitTo(generations)
    }, approval::release)
    return refusal?.let(CodexChatApprovalOpening::Refused) ?: CodexChatApprovalOpening.Opened(approval)
  }

  internal fun openCursor(
    request: ApprovalOwnerOpenRequest,
    protocol: CursorAcpSession,
    stopProcess: () -> Unit,
    expectedComponent: DatabaseSessionComponent? = null,
  ): CursorChatApprovalOpening {
    if (request.profileIdentity.isBlank()) return CursorChatApprovalOpening.Refused(
      "Cursor approval scope is unavailable because its profile identity is unknown.",
    )
    val component = sessions.sessions.value ?: return CursorChatApprovalOpening.Refused(NO_SESSION_REASON)
    if ((expectedComponent != null && component !== expectedComponent) || !sessions.isCurrent(component)) {
      return CursorChatApprovalOpening.Refused(SESSION_CHANGED_REASON)
    }
    val generation = CursorChatGeneration(protocol, stopProcess, diagnostics)
    val refusal = openOwnerGeneration(component, request, generation, { owner, generations ->
      protocol.bind(owner)
      generation.admit(generations)
    }, generation::end)
    return if (refusal == null) CursorChatApprovalOpening.Opened(generation)
    else CursorChatApprovalOpening.Refused(refusal)
  }

  private fun openOwnerGeneration(
    component: DatabaseSessionComponent,
    request: ApprovalOwnerOpenRequest,
    effects: ApprovalOwnerEffects,
    bindAndAdmit: (ApprovalOwnerSession, SessionApprovalGenerations) -> Boolean,
    release: () -> Unit,
  ): String? =
    when (val opened = component.runtime.approvalOwners.open(request, effects)) {
      is ApprovalOwnerOpenResult.Opened -> if (bindAndAdmit(opened.session, component.approvalGenerations)) {
        null
      } else {
        release()
        SESSION_CHANGED_REASON
      }
      is ApprovalOwnerOpenResult.Refused -> opened.reason
    }

  private companion object {
    const val NO_SESSION_REASON =
      "Operator approval is unavailable: no database session is open, so no approval card can be shown."
  }
}

/** The owner generation a chat process opens: one per chat session, repository and provider. */
internal fun chatOpenRequest(
  sessionId: String,
  repositoryRoot: String,
  provider: String,
  mode: ChatPermissionMode,
  profileIdentity: String,
) = ApprovalOwnerOpenRequest(
  checkoutRoot = Path.of(repositoryRoot),
  runId = sessionId,
  provider = provider,
  executionMode = if (mode == ChatPermissionMode.ReadOnly) ApprovalExecutionMode.READ_ONLY else ApprovalExecutionMode.ACT,
  profileIdentity = profileIdentity,
  enclosingDeadline = null,
)

internal const val SESSION_CHANGED_REASON = "The database session changed while the chat was starting; send the message again."

internal const val CODEX_CHAT_APPROVAL_PROVIDER = "codex"

internal sealed interface CodexChatApprovalOpening {
  data class Opened(val approval: CodexChatApproval) : CodexChatApprovalOpening
  data class Refused(val reason: String) : CodexChatApprovalOpening
}

internal class CodexChatApproval(
  writeLine: (String) -> Boolean,
  private val stopProcess: () -> Unit,
  diagnostics: Diagnostics,
  onCard: (ApprovalCard) -> Unit,
  repositoryRoot: String,
) : ApprovalOwnerEffects, ApprovalGeneration {
  private val generations = AtomicReference<SessionApprovalGenerations?>()
  private val ended = AtomicBoolean(false)
  private val stopping = AtomicBoolean(false)
  private val requests = CodexApprovalRequests(repositoryRoot, { writeLine(it.toString()) },
    { onCard(it.toCard()) }, ::stop, { diagnostics.record("chat.codex_approval_diagnostic") })

  fun bind(session: ApprovalOwnerSession) = requests.bind(session)
  fun admitTo(value: SessionApprovalGenerations): Boolean { generations.set(value); return value.admit(this) }
  fun bindTurn(thread: String, turn: String) = requests.bindTurn(thread, turn)
  fun handleServerRequest(message: JsonObject, itemFacts: Map<CodexChatItemIdentity, JsonObject>) {
    val params = message.obj("params")
    val key = params?.let { codexItemKey(it.string("threadId").orEmpty(), it.string("turnId").orEmpty(), it.string("itemId").orEmpty()) }
    itemFacts[key]?.let { requests.itemStarted(requireNotNull(params), it) }
    requests.request(message)
  }
  fun providerResolved(params: JsonObject) = requests.providerResolved(params)
  fun itemCompleted(params: JsonObject, item: JsonObject): Boolean? = requests.itemCompleted(params, item)
  fun turnEnded(thread: String, turn: String) = requests.turnEnded(thread, turn)
  fun release() {
    if (!ended.compareAndSet(false, true)) return
    generations.getAndSet(null)?.remove(this)
    requests.release()
  }
  override fun deliver(settled: ApprovalRequestRecord): ApprovalDeliveryState = requests.deliver(settled)
  override fun stop(reason: String) {
    if (!stopping.compareAndSet(false, true)) return
    stopProcess()
    release()
  }
  override fun end() { release(); stopProcess() }
}

/** Hosts the Claude permission protocol for a chat process; the protocol itself is [ClaudeApprovalRequests]. */
internal class ClaudeChatApproval(
  repositoryRoot: String,
  writeLine: (String) -> Boolean,
  private val stopProcess: () -> Unit,
  private val diagnostics: Diagnostics,
  onCard: (ApprovalCard) -> Unit,
) : ApprovalOwnerEffects, ApprovalGeneration {
  private val admittedTo = AtomicReference<SessionApprovalGenerations?>()
  private val ended = AtomicBoolean(false)
  private val requests = ClaudeApprovalRequests(
    checkout = repositoryRoot,
    write = { writeLine(it.toString()) },
    onCard = { onCard(it.toCard()) },
    stop = ::stop,
    record = { diagnostics.record("chat.claude_approval_diagnostic") },
    clock = Clock.systemUTC(),
  )

  /** True once the generation was released, by the process ending or by its database session closing. */
  val isReleased: Boolean get() = ended.get()

  fun bind(session: ApprovalOwnerSession) = requests.bind(session)

  /** Registers this generation with the database session it opened in; false when that session is already closing. */
  fun admitTo(generations: SessionApprovalGenerations): Boolean {
    admittedTo.set(generations)
    return generations.admit(this)
  }

  /** Database-session close: classify and stop the process before releasing the owner session and its requests. */
  override fun end() {
    try {
      stopProcess()
    } finally {
      release()
    }
  }

  fun initializeFrame(): String = requests.initializeFrame().toString()

  fun handleControlResponse(root: JsonObject): ChatInitializeOutcome = when (val outcome = requests.initializeOutcome(root)) {
    ClaudeInitializeOutcome.Initialized -> ChatInitializeOutcome.Initialized
    ClaudeInitializeOutcome.Unrelated -> ChatInitializeOutcome.Unrelated
    is ClaudeInitializeOutcome.Refused -> ChatInitializeOutcome.Refused(
      "The installed Claude CLI refused host permission control during initialize: ${outcome.detail} " +
        "Update Claude Code; the chat was not started.",
    )
  }

  fun handle(root: JsonObject, line: String) = requests.handle(root, line)

  /** Normal closure: cancels this generation's outstanding requests; the reader calls it when the process ends. */
  fun release() {
    ended.set(true)
    admittedTo.get()?.remove(this)
    requests.release()
  }

  override fun deliver(settled: ApprovalRequestRecord): ApprovalDeliveryState = requests.deliver(settled)

  override fun stop(reason: String) {
    diagnostics.record("chat.approval_channel_stopped")
    stopProcess()
  }
}

internal fun codexItemKey(threadId: String, turnId: String, itemId: String): CodexChatItemIdentity =
  CodexChatItemIdentity(threadId, turnId, itemId)

internal data class CodexChatItemIdentity(val threadId: String, val turnId: String, val itemId: String)

internal sealed interface ChatInitializeOutcome {
  data object Initialized : ChatInitializeOutcome

  data class Refused(val message: String) : ChatInitializeOutcome

  data object Unrelated : ChatInitializeOutcome
}
