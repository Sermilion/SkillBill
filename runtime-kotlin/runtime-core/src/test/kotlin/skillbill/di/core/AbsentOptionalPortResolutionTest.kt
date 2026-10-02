package skillbill.di.core

import skillbill.infrastructure.http.JdkHttpRequester
import skillbill.infrastructure.workflow.git.GitWorkflowGitOperations
import skillbill.model.EnvironmentContext
import skillbill.ports.telemetry.model.RemoteTransportResponse
import skillbill.ports.telemetry.transport.RemoteTransportPort
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

class AbsentOptionalPortResolutionTest {
  private val provides = object : RuntimeOptionalCallbackProvides {}

  @Test
  fun `an absent requester resolves to the JDK transport`() {
    val resolved =
      RuntimeBootstrapBindings.runtimeContext(
        RuntimeContext(
          environment = EnvironmentContext(),
          transport = TransportContext(),
          workflowOps = WorkflowOpsContext(),
          callbacks = OptionalCallbacks(),
        ),
      )

    assertSame(JdkHttpRequester, resolved.transport.requester)
  }

  @Test
  fun `a caller-supplied requester survives bootstrap`() {
    val supplied = RemoteTransportPort { _, _, _, _ -> RemoteTransportResponse(200, "") }

    val resolved =
      RuntimeBootstrapBindings.runtimeContext(
        RuntimeContext(
          environment = EnvironmentContext(),
          transport = TransportContext(requester = supplied),
          workflowOps = WorkflowOpsContext(),
          callbacks = OptionalCallbacks(),
        ),
      )

    assertSame(supplied, resolved.transport.requester)
  }

  @Test
  fun `an unresolved transport context fails as a composition defect`() {
    assertFailsWith<IllegalStateException> {
      RuntimeComponent::class.create(
        RuntimeContext(
          environment = EnvironmentContext(),
          transport = TransportContext(),
          workflowOps = WorkflowOpsContext(),
          callbacks = OptionalCallbacks(),
        ),
      ).remoteTransportPort(TransportContext())
    }
  }

  @Test
  fun `absent workflow git operations resolve to the git adapter`() {
    assertIs<GitWorkflowGitOperations>(provides.workflowGitOperations(WorkflowOpsContext()))
  }
}
