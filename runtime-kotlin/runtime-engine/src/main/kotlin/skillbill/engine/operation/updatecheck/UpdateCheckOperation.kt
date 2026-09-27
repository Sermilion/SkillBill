package skillbill.engine.operation.updatecheck

import skillbill.application.updatecheck.UpdateCheckService
import skillbill.application.updatecheck.toText
import skillbill.engine.operation.core.Operation
import skillbill.engine.operation.core.OperationContext
import skillbill.engine.operation.core.OperationOutcome
import skillbill.engine.operation.core.OperationRunResult

/**
 * `operation:update-check`: reports installed vs latest exactly as `skill-bill update-check` does; mutates nothing.
 * `post` stays the default no-op until the telemetry contract gains an operation event (see `agent/decisions.md`).
 */
class UpdateCheckOperation(
  private val service: UpdateCheckService,
) : Operation {
  override val id: String = "update-check"

  override fun run(context: OperationContext): OperationRunResult =
    OperationRunResult.Finished(OperationOutcome.Completed(service.check(includePrereleases = false).toText()))
}
