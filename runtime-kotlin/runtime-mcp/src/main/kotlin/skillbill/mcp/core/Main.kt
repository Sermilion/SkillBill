package skillbill.mcp.core

import skillbill.di.core.OptionalCallbacks
import skillbill.di.core.PackagedContractComponent
import skillbill.di.core.RuntimeComponent
import skillbill.di.core.RuntimeContext
import skillbill.di.core.TransportContext
import skillbill.di.core.WorkflowOpsContext
import skillbill.di.core.create
import skillbill.error.core.SkillBillRuntimeException
import skillbill.mcp.review.GovernedReviewEvidenceBridge
import skillbill.mcp.shared.McpComponent
import skillbill.mcp.shared.create
import skillbill.model.EnvironmentContext
import kotlin.system.exitProcess

fun main(args: Array<String>) {
  if (args.contentEquals(arrayOf("--check-packaged-contracts"))) {
    try {
      PackagedContractComponent::class.create().inspector.inspect()
      println("Packaged contracts match producer versions.")
    } catch (error: SkillBillRuntimeException) {
      System.err.println(error.message)
      exitProcess(1)
    }
    return
  }
  val environment = System.getenv()
  if (GovernedReviewEvidenceBridge.enabled(environment)) {
    GovernedReviewEvidenceBridge.run(environment)
  } else {
    val runtimeComponent =
      RuntimeComponent::class.create(
        RuntimeContext(
          environment = EnvironmentContext(environment = environment),
          transport = TransportContext(),
          workflowOps = WorkflowOpsContext(),
          callbacks = OptionalCallbacks(),
        ),
      )
    val mcpComponent = McpComponent::class.create(runtimeComponent)
    McpStdioServer.run(mcpComponent)
  }
}
