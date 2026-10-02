package skillbill.infrastructure.launcher.review

import skillbill.contracts.JsonCodec
import skillbill.contracts.JsonPayloadContract
import skillbill.contracts.review.GovernedReviewEvidenceContracts
import skillbill.error.core.InvalidGovernedReviewEvidenceRequestError
import skillbill.ports.review.model.ReviewEvidenceBatchRequest
import skillbill.ports.review.model.ReviewEvidenceBatchResult
import skillbill.ports.review.model.ReviewExpansionAuthorizationRequest
import skillbill.review.context.model.execution.GovernedReviewJsonRpcArguments
import skillbill.review.context.model.hunk.ReviewExpansionRecord

internal object GovernedReviewEvidenceCodec {
  fun toolSpecList(): GovernedReviewToolSpecList =
    GovernedReviewToolSpecList.from(GovernedReviewEvidenceCodecWireSchemas.toolSpecs())

  fun readRequest(
    lane: String,
    arguments: GovernedReviewJsonRpcArguments,
    expansionById: (String) -> ReviewExpansionRecord?,
  ): ReviewEvidenceBatchRequest {
    requestMetadata(arguments)
    if (arguments.keys.any { it !in setOf(GovernedReviewEvidencePayloadKeys.REQUESTS) }) {
      throw InvalidGovernedReviewEvidenceRequestError("review-evidence", "Malformed read operation.")
    }
    val rawRequests = evidenceReadItems(arguments)
    return ReviewEvidenceBatchRequest(
      lane = lane,
      requests =
        rawRequests.map { raw ->
          GovernedReviewEvidenceCodecWireParsing.evidenceRequest(lane, raw, expansionById)
        },
    )
  }

  fun expansionRequest(
    lane: String,
    arguments: GovernedReviewJsonRpcArguments,
  ): ReviewExpansionAuthorizationRequest {
    requestMetadata(arguments)
    if (arguments.keys.any {
        it !in
          setOf(
            GovernedReviewEvidencePayloadKeys.LANE,
            GovernedReviewEvidencePayloadKeys.PATH,
            GovernedReviewEvidencePayloadKeys.REACHABILITY_REASON,
          )
      }
    ) {
      throw InvalidGovernedReviewEvidenceRequestError("review-expansion", "Unknown expansion request field.")
    }
    return ReviewExpansionAuthorizationRequest(
      lane =
        if (GovernedReviewEvidencePayloadKeys.LANE in arguments) {
          GovernedReviewEvidenceCodecWireParsing.requiredString(arguments, GovernedReviewEvidencePayloadKeys.LANE)
        } else {
          lane
        },
      path = GovernedReviewEvidenceCodecWireParsing.requiredString(arguments, GovernedReviewEvidencePayloadKeys.PATH),
      reachabilityReason =
        GovernedReviewEvidenceCodecWireParsing.requiredString(
          arguments,
          GovernedReviewEvidencePayloadKeys.REACHABILITY_REASON,
        ),
    )
  }

  fun batchResultPayload(result: ReviewEvidenceBatchResult): JsonPayloadContract =
    GovernedReviewWirePayload.from(
      linkedMapOf(
        GovernedReviewEvidencePayloadKeys.DELIVERY_RECEIPT to result.deliveryReceipt,
        GovernedReviewEvidencePayloadKeys.RESULTS to
          result.results.map(GovernedReviewEvidenceCodecWirePayloads::resultPayload),
        GovernedReviewEvidencePayloadKeys.CUMULATIVE_BYTES to result.cumulativeBytes,
        GovernedReviewEvidencePayloadKeys.EXPANSIONS to
          result.expansions.map(GovernedReviewEvidenceCodecWirePayloads::expansionPayload),
        GovernedReviewEvidencePayloadKeys.TERMINAL_OUTCOME to
          result.terminalOutcome?.let(GovernedReviewEvidenceCodecWirePayloads::budgetPayload),
      ),
    )

  fun expansionRecordPayload(record: ReviewExpansionRecord): JsonPayloadContract =
    GovernedReviewWirePayload.from(GovernedReviewEvidenceCodecWirePayloads.expansionPayload(record))

  fun requestMetadataBytes(request: ReviewEvidenceBatchRequest): Int =
    JsonCodec.mapToJsonString(
      mapOf(
        GovernedReviewEvidencePayloadKeys.LANE to request.lane,
        GovernedReviewEvidencePayloadKeys.REQUESTS to
          request.requests.map { item ->
            mapOf(
              GovernedReviewEvidencePayloadKeys.LANE to item.lane,
              GovernedReviewEvidencePayloadKeys.PATH to item.path,
              GovernedReviewEvidencePayloadKeys.SELECTOR to item.selector,
              GovernedReviewEvidencePayloadKeys.REACHABILITY_REASON to item.reachabilityReason,
              "authorized_expansion" to item.authorizedExpansion?.let { expansionRecordPayload(it).toPayload() },
              "offset" to item.offset,
              "limit" to item.limit,
              "pagination_token" to item.paginationToken,
            )
          },
      ),
    ).toByteArray(Charsets.UTF_8).size

  private fun requestMetadata(arguments: Map<String, Any?>) {
    if (
      JsonCodec.mapToJsonString(arguments).toByteArray(Charsets.UTF_8).size >
      GovernedReviewEvidenceContracts.REQUEST_BYTES
    ) {
      throw InvalidGovernedReviewEvidenceRequestError("review-evidence", "Request metadata exceeds its byte limit.")
    }
  }
}
