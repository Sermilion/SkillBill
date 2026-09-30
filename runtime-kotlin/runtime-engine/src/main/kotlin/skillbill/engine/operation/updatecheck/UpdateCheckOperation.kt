package skillbill.engine.operation.updatecheck

import skillbill.application.updatecheck.UpdateCheckService
import skillbill.application.updatecheck.model.UpdateCheckResult
import skillbill.application.updatecheck.toText
import skillbill.contracts.JsonCodec
import skillbill.contracts.system.UpdateCheckContract
import skillbill.engine.operation.core.Operation
import skillbill.engine.operation.core.OperationContext
import skillbill.engine.operation.core.OperationOutcome
import skillbill.engine.operation.core.OperationOutputFormat
import skillbill.engine.operation.core.OperationRunResult

class UpdateCheckOperation(
  private val service: UpdateCheckService,
) : Operation {
  override val id: String = "update-check"

  override fun run(context: OperationContext): OperationRunResult {
    val result = service.check(includePrereleases = context.arguments.includePrereleases)
    val report =
      when (context.arguments.format) {
        OperationOutputFormat.TEXT -> result.toText()
        OperationOutputFormat.JSON -> JsonCodec.mapToJsonString(updateCheckContract(result).toPayload())
      }
    return OperationRunResult.Finished(OperationOutcome.Completed(report))
  }
}

fun updateCheckContract(result: UpdateCheckResult): UpdateCheckContract =
  UpdateCheckContract(
    status = result.status.wireName,
    installedVersion = result.installedVersion,
    latestVersion = result.latestVersion,
    releaseUrl = result.releaseUrl,
    recommendedInstallCommand = result.recommendedInstallCommand,
    reason = result.reason,
    releaseNotes = result.releaseNotes,
  )
