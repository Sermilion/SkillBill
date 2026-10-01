package skillbill.di.core

import skillbill.model.EnvironmentContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertSame

class RuntimeComponentScopedIdentityTest {
  @Test
  fun `scoped connection holder returns the same instance across accessor reads`() {
    val component = runtimeComponent(Files.createTempDirectory("skillbill-scoped-identity"))
    val first = component.featureTaskRuntimeWorkerCoordinator
    val second = component.featureTaskRuntimeWorkerCoordinator
    assertSame(first, second)
  }

  private fun runtimeComponent(userHome: Path): RuntimeComponent =
    RuntimeComponent::class.create(
      RuntimeContext(
        environment = EnvironmentContext(environment = emptyMap(), userHome = userHome),
        transport = TransportContext(),
        workflowOps = WorkflowOpsContext(),
        callbacks = OptionalCallbacks(),
      ),
    )
}
