package skillbill.application.review.packet

import skillbill.ports.review.model.ReviewStoredHunkBodyExtractor
import skillbill.review.context.model.hunk.ReviewChangedHunk

internal object ReviewLocatorHunkBodyExtractor : ReviewStoredHunkBodyExtractor {
  override fun extract(
    payload: String,
    hunk: ReviewChangedHunk,
  ): String = ReviewHunkStoreIndexing.extractStoredBody(hunk, payload, hunk.evidenceLocator.storePath)
}
