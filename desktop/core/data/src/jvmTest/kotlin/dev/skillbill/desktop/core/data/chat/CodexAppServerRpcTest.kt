package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatSteeringResult
import dev.skillbill.desktop.core.testing.RecordingDiagnostics
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import skillbill.approval.model.ApprovalProviderDisposition
import skillbill.contracts.approval.ApprovalProviderRequestId
import skillbill.contracts.approval.ApprovalRequestRecord
import skillbill.contracts.approval.ApprovalState
import skillbill.ports.approval.model.ApprovalDecisionResult
import skillbill.ports.approval.model.ApprovalDeliveryState
import skillbill.ports.approval.model.ApprovalOwnerKey
import skillbill.ports.approval.model.ApprovalOwnerSession
import skillbill.ports.approval.model.ApprovalPendingWait
import skillbill.ports.approval.model.ApprovalProviderClearance
import skillbill.ports.approval.model.ApprovalRegistrationCommand
import skillbill.ports.approval.model.ApprovalRegistrationResult
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CodexAppServerRpcTest {
  // A merge conflict, red build or failing test is something the agent repairs, so the turn must not end on it.
  @Test
  fun `an approved command that exits non-zero is a tool result and the turn still completes`() {
    val events = approvedCommandTurn(
      ApprovalState.ALLOWED,
      """"status":"failed","exitCode":1,"aggregatedOutput":"CONFLICT (content): Merge conflict in App.kt"""",
    )

    assertTrue(events.filterIsInstance<ChatEvent.ToolResult>().single().isError)
    assertEquals(ChatEvent.Completed, events.last())
    assertTrue(events.none { it is ChatEvent.Failed })
  }

  @Test
  fun `a declined command still fails the turn`() {
    val events = approvedCommandTurn(ApprovalState.DENIED, """"status":"declined"""")

    val failure = assertIs<ChatEvent.Failed>(events.last())
    assertContains(failure.detail.orEmpty(), "declined")
  }

  @Test
  fun `steering replies share the turn reader and rejection leaves the turn usable`() = runTest {
    val input = Channel<ChatProcessInput>(Channel.UNLIMITED)
    val output = Channel<ChatProcessOutput>(Channel.UNLIMITED)
    val events = mutableListOf<ChatEvent>()
    val approval = CodexChatApproval({ true }, {}, RecordingDiagnostics(), {}, "/checkout")
    val rpc = CodexAppServerRpc(input, output, RecordingDiagnostics(), { events += it }, approval)
    val drain = launch { rpc.drainTurn("thread", "turn") }

    val rejected = async {
      rpc.steer("delivery-1", "thread", "turn", emptyList(), { acquire -> acquire() }, { register -> register() }, {})
    }
    acknowledgeWrite(input.receive())
    output.send(line("""{"id":1,"error":{"code":-32602,"message":"turn busy"}}"""))
    assertIs<ChatSteeringResult.Rejected>(rejected.await())

    val accepted = async {
      rpc.steer("delivery-2", "thread", "turn", emptyList(), { acquire -> acquire() }, { register -> register() }, {})
    }
    acknowledgeWrite(input.receive())
    output.send(line("""{"method":"item/agentMessage/delta","params":{"threadId":"thread","turnId":"turn","itemId":"message","delta":"Still running"}}"""))
    output.send(line("""{"id":2,"result":{"turnId":"turn"}}"""))
    output.send(line("""{"method":"turn/completed","params":{"threadId":"thread","turn":{"id":"turn","status":"completed"}}}"""))

    assertIs<ChatSteeringResult.Accepted>(accepted.await())
    drain.join()
    assertTrue(ChatEvent.TextDelta("Still running") in events)
    assertEquals(ChatEvent.Completed, events.last())
    approval.release()
    output.close()
  }

  @Test
  fun `written steering returns uncertain while active output continues and late rejection replaces it`() = runTest {
    val input = Channel<ChatProcessInput>(Channel.UNLIMITED)
    val output = Channel<ChatProcessOutput>(Channel.UNLIMITED)
    val events = mutableListOf<ChatEvent>()
    val updates = mutableListOf<ChatSteeringResult>()
    val approval = CodexChatApproval({ true }, {}, RecordingDiagnostics(), {}, "/checkout")
    val rpc = CodexAppServerRpc(input, output, RecordingDiagnostics(), { events += it }, approval)
    val drain = launch { rpc.drainTurn("thread", "turn") }
    val steer = async {
      rpc.steer("delivery-late-rejection", "thread", "turn", emptyList(), { acquire -> acquire() },
        { register -> register() }) { updates += it }
    }
    val frame = input.receive()
    acknowledgeWrite(frame)
    output.send(line("""{"method":"item/agentMessage/delta","params":{"threadId":"thread","turnId":"turn","itemId":"message","delta":"Still running"}}"""))
    yield()

    assertIs<ChatSteeringResult.DeliveryUncertain>(steer.await())
    assertIs<ChatSteeringResult.DeliveryUncertain>(updates.single())
    assertTrue(ChatEvent.TextDelta("Still running") in events)
    output.send(line("""{"id":1,"error":{"code":-32602,"message":"turn busy"}}"""))
    yield()

    assertEquals(2, updates.size)
    assertIs<ChatSteeringResult.Rejected>(updates.last())
    assertEquals("turn busy", (updates.last() as ChatSteeringResult.Rejected).reason)
    output.send(line("""{"method":"turn/completed","params":{"threadId":"thread","turn":{"id":"turn","status":"completed"}}}"""))
    drain.join()
    approval.release()
    output.close()
  }

  @Test
  fun `written steering returns uncertain while active and late acceptance replaces it`() = runTest {
    val input = Channel<ChatProcessInput>(Channel.UNLIMITED)
    val output = Channel<ChatProcessOutput>(Channel.UNLIMITED)
    val updates = mutableListOf<ChatSteeringResult>()
    val approval = CodexChatApproval({ true }, {}, RecordingDiagnostics(), {}, "/checkout")
    val rpc = CodexAppServerRpc(input, output, RecordingDiagnostics(), {}, approval)
    val drain = launch { rpc.drainTurn("thread", "turn") }
    val steer = async {
      rpc.steer("delivery-late-acceptance", "thread", "turn", emptyList(), { acquire -> acquire() },
        { register -> register() }) { updates += it }
    }
    acknowledgeWrite(input.receive())

    assertIs<ChatSteeringResult.DeliveryUncertain>(steer.await())
    output.send(line("""{"id":1,"result":{"turnId":"turn"}}"""))
    yield()

    assertEquals(2, updates.size)
    assertIs<ChatSteeringResult.DeliveryUncertain>(updates.first())
    assertIs<ChatSteeringResult.Accepted>(updates.last())
    output.send(line("""{"method":"turn/completed","params":{"threadId":"thread","turn":{"id":"turn","status":"completed"}}}"""))
    drain.join()
    approval.release()
    output.close()
  }

  @Test
  fun `acknowledgement racing write completion wins over uncertainty`() = runTest {
    val input = Channel<ChatProcessInput>(Channel.UNLIMITED)
    val output = Channel<ChatProcessOutput>(Channel.UNLIMITED)
    val updates = mutableListOf<ChatSteeringResult>()
    val approval = CodexChatApproval({ true }, {}, RecordingDiagnostics(), {}, "/checkout")
    val rpc = CodexAppServerRpc(input, output, RecordingDiagnostics(), {}, approval)
    val drain = launch { rpc.drainTurn("thread", "turn") }
    val steer = async {
      rpc.steer("delivery-racing-ack", "thread", "turn", emptyList(), { acquire -> acquire() },
        { register -> register() }) { updates += it }
    }
    val frame = input.receive()
    output.send(line("""{"id":1,"result":{"turnId":"turn"}}"""))
    yield()
    acknowledgeWrite(frame)

    assertIs<ChatSteeringResult.Accepted>(steer.await())
    assertEquals(listOf(ChatSteeringResult.Accepted("delivery-racing-ack")), updates)
    output.send(line("""{"method":"turn/completed","params":{"threadId":"thread","turn":{"id":"turn","status":"completed"}}}"""))
    drain.join()
    approval.release()
    output.close()
  }

  private fun acknowledgeWrite(input: ChatProcessInput) {
    assertTrue(input.acquireWrite())
    input.written.complete(true)
  }

  private fun approvedCommandTurn(decision: ApprovalState, completion: String): List<ChatEvent> = runBlocking {
    val owner = Owner()
    val approval = CodexChatApproval({ true }, {}, RecordingDiagnostics(), {}, "/checkout")
    approval.bind(owner)
    approval.bindTurn("thread", "turn")
    val output = Channel<ChatProcessOutput>(Channel.UNLIMITED)
    val events = mutableListOf<ChatEvent>()
    val rpc = CodexAppServerRpc(Channel(Channel.UNLIMITED), output, RecordingDiagnostics(), { events += it }, approval)
    val drain = launch { rpc.drainTurn("thread", "turn") }
    output.send(line("""{"method":"item/started","params":{"threadId":"thread","turnId":"turn","item":$COMMAND,"status":"inProgress"}}}"""))
    output.send(
      line(
        """{"id":7,"method":"item/commandExecution/requestApproval","params":{"threadId":"thread","turnId":"turn",""" +
          """"itemId":"cmd","command":"git merge origin/main","cwd":"/checkout",""" +
          """"commandActions":[{"type":"unknown","command":"git merge origin/main"}],"availableDecisions":null}}""",
      ),
    )
    while (owner.requests.isEmpty()) yield()
    assertEquals(ApprovalDeliveryState.WRITTEN, approval.deliver(owner.requests.single().copy(state = decision)))
    output.send(line("""{"method":"item/completed","params":{"threadId":"thread","turnId":"turn","item":$COMMAND,$completion}}}"""))
    output.send(
      line(
        """{"method":"item/completed","params":{"threadId":"thread","turnId":"turn",""" +
          """"item":{"id":"m","type":"agentMessage","text":"Resolved the conflict."}}}""",
      ),
    )
    output.send(line("""{"method":"turn/completed","params":{"threadId":"thread","turn":{"id":"turn","status":"completed"}}}"""))
    drain.join()
    approval.release()
    events
  }

  private fun line(text: String) = ChatProcessOutput.Line(text)

  private class Owner : ApprovalOwnerSession {
    override val key = ApprovalOwnerKey("repo", "run", "generation")
    val requests = mutableListOf<ApprovalRequestRecord>()

    override fun register(command: ApprovalRegistrationCommand): ApprovalRegistrationResult {
      val request = command.request.copy(
        actionFingerprint = command.canonicalInput,
        authorityScope = command.canonicalInput?.let { command.request.authorityScope?.copy(actionFingerprint = it) },
      )
      requests += request
      return ApprovalRegistrationResult.Registered(request)
    }

    override fun resolveByProvider(
      approvalId: String,
      providerRequestId: ApprovalProviderRequestId,
      disposition: ApprovalProviderDisposition,
    ): ApprovalDecisionResult = ApprovalDecisionResult.Missing

    override fun clearByProvider(
      approvalId: String,
      providerRequestId: ApprovalProviderRequestId,
      clearance: ApprovalProviderClearance,
    ): ApprovalDecisionResult = ApprovalDecisionResult.Missing

    override fun pendingWait(): ApprovalPendingWait? = null

    override fun release() = Unit
  }

  private companion object {
    // The open object lets each frame append its own status fields before closing the item.
    const val COMMAND = """{"id":"cmd","type":"commandExecution","command":"git merge origin/main","cwd":"/checkout""""
  }
}
