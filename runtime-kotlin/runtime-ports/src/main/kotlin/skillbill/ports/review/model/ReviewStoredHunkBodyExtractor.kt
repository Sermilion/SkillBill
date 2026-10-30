package skillbill.ports.review.model

import skillbill.review.context.model.hunk.ReviewChangedHunk

fun interface ReviewStoredHunkBodyExtractor {
  fun extract(
    payload: String,
    hunk: ReviewChangedHunk,
  ): String

  companion object {
    val HUNK_CONTENT: ReviewStoredHunkBodyExtractor =
      ReviewStoredHunkBodyExtractor { _, hunk ->
        hunk.content.replace("\r\n", "\n")
      }
  }
}
