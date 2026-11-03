package skillbill.infrastructure.host

import skillbill.workflow.model.FeatureTaskExecutionIdentityPolicy
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class CanonicalRepositoryRootTest {
  private val scratch: Path = Files.createTempDirectory("skillbill-canonical-root")

  @AfterTest
  fun cleanUp() {
    scratch.toFile().deleteRecursively()
  }

  @Test
  fun `primary identity is shared by the main checkout linked worktree and a subdirectory`() {
    val main = scratch.resolve("main")
    git(scratch, "init", "-q", main.toString())
    git(
      main,
      "-c",
      "user.name=skillbill",
      "-c",
      "user.email=skillbill@example.com",
      "commit",
      "-q",
      "--allow-empty",
      "-m",
      "i",
    )
    val linked = scratch.resolve("linked")
    git(main, "worktree", "add", "-q", "--detach", linked.toString())
    val nested = linked.resolve("nested/dir")
    Files.createDirectories(nested)

    val primaryFromMain = CanonicalRepositoryRoot.primaryRepositoryIdentity(main)
    val primaryFromLinked = CanonicalRepositoryRoot.primaryRepositoryIdentity(linked)
    val primaryFromNested = CanonicalRepositoryRoot.primaryRepositoryIdentity(nested)

    assertEquals(primaryFromMain, primaryFromLinked)
    assertEquals(primaryFromMain, primaryFromNested)
    assertEquals(
      FeatureTaskExecutionIdentityPolicy.REPOSITORY_IDENTITY_PREFIX + main.toRealPath(),
      primaryFromMain,
    )

    val exactMain = CanonicalRepositoryRoot.repositoryIdentity(main)
    val exactLinked = CanonicalRepositoryRoot.repositoryIdentity(linked)
    assertEquals(exactMain, primaryFromMain)
    assertNotEquals(exactLinked, primaryFromMain)
    assertEquals(
      FeatureTaskExecutionIdentityPolicy.REPOSITORY_IDENTITY_PREFIX + linked.toRealPath(),
      exactLinked,
    )
  }

  private fun git(
    directory: Path,
    vararg arguments: String,
  ) {
    val builder = ProcessBuilder(listOf("git", "-C", directory.toString()) + arguments).redirectErrorStream(true)
    builder.environment() += mapOf("GIT_CONFIG_GLOBAL" to "/dev/null", "GIT_CONFIG_NOSYSTEM" to "1")
    val process = builder.start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor() == 0) { "git ${arguments.toList()}: $output" }
  }
}
