package skillbill.application.review.model

internal class ReviewEvidenceReadCount {
  private var reads: Long = 0

  @Synchronized
  fun increment() {
    reads += 1
  }

  @Synchronized
  fun current(): Long = reads
}
