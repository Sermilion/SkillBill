package skillbill.ports.blockincident.model

import skillbill.workflow.taskruntime.model.blockincident.report.ProductReportFailure
import java.time.Instant

sealed interface ProductIssueResult {
  data class Found(val number: Long, val url: String, val originReportId: String? = null) : ProductIssueResult

  data object NotFound : ProductIssueResult

  data class Created(val number: Long, val url: String) : ProductIssueResult

  data class Failed(val failure: ProductReportFailure, val retryAt: Instant? = null) : ProductIssueResult

  data object Ambiguous : ProductIssueResult
}
