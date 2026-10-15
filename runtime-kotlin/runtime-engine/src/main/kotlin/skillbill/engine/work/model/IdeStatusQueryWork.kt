package skillbill.engine.work.model

/**
 * Work one status request spent reading storage, accumulated across every page, per-row lookup and observation-fenced
 * retry. Counters are mutated only on the request's own read thread.
 */
class IdeStatusQueryWork {
  private var statements = 0
  private var rowsRead = 0
  private var pages = 0
  private var retries = 0
  private var budgetExhausted = false

  fun statement(rows: Int = 0) {
    statements++
    rowsRead += rows
  }

  fun page(rows: Int) {
    pages++
    statement(rows)
  }

  fun retry() {
    retries++
  }

  fun exhaustedBudget() {
    budgetExhausted = true
  }

  fun report(): IdeStatusQueryWorkReport = IdeStatusQueryWorkReport(statements, rowsRead, pages, retries, budgetExhausted)
}

data class IdeStatusQueryWorkReport(
  val statements: Int,
  val rowsRead: Int,
  val pages: Int,
  val retries: Int,
  val budgetExhausted: Boolean,
) {
  companion object {
    val NONE = IdeStatusQueryWorkReport(0, 0, 0, 0, false)
  }
}
