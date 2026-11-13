package skillbill.infrastructure.launcher

import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.SkillStagingFailureCode
import skillbill.model.EnvironmentContext
import skillbill.ports.review.model.ReviewLaunchAgentStagingRequest
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FileSystemReviewLaunchAgentStagingTest {
  private val tempDirs = mutableListOf<Path>()

  @AfterTest
  fun cleanup() {
    tempDirs.forEach { dir ->
      if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
        Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
      }
    }
  }

  @Test
  fun `missing managed agent inventory reports the supplied repair command and worker evidence`() {
    val home = Files.createTempDirectory("review-launch-agent-staging").also(tempDirs::add)
    val logicalName = "bill-code-review-worker"
    val failure =
      assertFailsWith<SkillBillRuntimeException> {
        FileSystemReviewLaunchAgentStaging(EnvironmentContext(userHome = home)).stage(
          ReviewLaunchAgentStagingRequest(
            agentId = "cursor",
            reviewLaunchDirectory = home.resolve("review-launch"),
            logicalWorkerNames = listOf(logicalName),
          ),
        )
      }

    assertEquals(SkillStagingFailureCode.MISSING_INSTALLED_NATIVE_AGENT, failure.code)
    assertContains(failure.message.orEmpty(), "Native agent '$logicalName' for provider 'cursor'")
    assertContains(failure.message.orEmpty(), home.toString())
    assertContains(failure.message.orEmpty(), "failed preflight at '")
    assertContains(failure.message.orEmpty(), "Repair with: sona install apply")
  }
}
