package skillbill.review.eval

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class ReviewEvalOnDemandRunnerTest {
  @Test
  fun `scores case registers only when the eval register directory is set`() {
    val registerDir = System.getenv(REVIEW_EVAL_REGISTER_DIR_ENV)?.trim().orEmpty()
    assumeTrue(registerDir.isNotEmpty(), "SKILL_BILL_REVIEW_EVAL_REGISTER_DIR is unset")
    println(ReviewEvalOnDemandRunner.run(Path.of(registerDir)))
  }
}
