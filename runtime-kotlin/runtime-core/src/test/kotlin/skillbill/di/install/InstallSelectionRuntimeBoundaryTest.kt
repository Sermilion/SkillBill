package skillbill.di.install

import skillbill.di.core.OptionalCallbacks
import skillbill.di.core.RuntimeComponent
import skillbill.di.core.RuntimeContext
import skillbill.di.core.TransportContext
import skillbill.di.core.WorkflowOpsContext
import skillbill.di.core.create
import skillbill.model.EnvironmentContext
import skillbill.ports.install.selection.model.ReadLatestSuccessfulInstallSelectionRequest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertNull

class InstallSelectionRuntimeBoundaryTest {
  @Test
  fun `runtime component exposes shared install selection persistence port`() {
    val home = Files.createTempDirectory("skillbill-install-selection-di")
    val component =
      RuntimeComponent::class.create(
        RuntimeContext(
          environment = EnvironmentContext(environment = emptyMap(), userHome = home),
          transport = TransportContext(),
          workflowOps = WorkflowOpsContext(),
          callbacks = OptionalCallbacks(),
        ),
      )

    val result =
      component.installSelectionPersistencePort.readLatestSuccessfulSelection(
        ReadLatestSuccessfulInstallSelectionRequest(home),
      )
    assertNull(result.selection)
  }
}
