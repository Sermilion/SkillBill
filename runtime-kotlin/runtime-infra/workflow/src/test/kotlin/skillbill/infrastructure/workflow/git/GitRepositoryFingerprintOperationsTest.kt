package skillbill.infrastructure.workflow.git

import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.taskruntime.model.core.MAX_REPOSITORY_FINGERPRINT_LENGTH
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class GitRepositoryFingerprintOperationsTest {
  @Test
  fun `scoped contents survive staging commits and committed deletions but detect file changes`() {
    val root = initRepo("skillbill-content-fingerprint")
    try {
      val operations = GitWorkflowGitOperations()
      val added = root.resolve("new.txt")
      Files.writeString(added, "new source\n")
      val paths = listOf("tracked.txt", "new.txt")

      fun fingerprint() =
        assertIs<WorkflowGitOperationResult.Ok>(operations.scopedContentFingerprint(root, paths)).value
      val before = fingerprint()
      git(root, "add", ".")
      assertEquals(before, fingerprint())
      git(root, "commit", "-m", "checkpoint")
      assertEquals(before, fingerprint())
      Files.writeString(added, "changed source\n")
      assertNotEquals(before, fingerprint())
      Files.delete(added)
      val deleted = fingerprint()
      git(root, "add", ".")
      git(root, "commit", "-m", "delete")
      assertEquals(deleted, operations.scopedContentFingerprint(root, listOf("tracked.txt")).value)
      assertIs<WorkflowGitOperationResult.Failed>(operations.scopedContentFingerprint(root, listOf("../outside")))
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  @Test
  fun `scoped contents detect executable mode symlink targets and same size large file edits`() {
    val root = initRepo("skillbill-content-fingerprint-types")
    try {
      val operations = GitWorkflowGitOperations()
      val source = root.resolve("tracked.txt")
      val link = root.resolve("source.link")
      Files.createSymbolicLink(link, Path.of("tracked.txt"))

      fun fingerprint() =
        assertIs<WorkflowGitOperationResult.Ok>(
          operations.scopedContentFingerprint(root, listOf("tracked.txt", "source.link")),
        ).value
      val before = fingerprint()
      assertTrue(source.toFile().setExecutable(!Files.isExecutable(source)))
      val executable = fingerprint()
      assertNotEquals(before, executable)
      Files.delete(link)
      Files.createSymbolicLink(link, Path.of("absent.txt"))
      assertNotEquals(executable, fingerprint())
      Files.write(source, ByteArray(2 * 1024 * 1024) { 1 })
      val large = fingerprint()
      val modified = Files.getLastModifiedTime(source)
      Files.write(source, ByteArray(2 * 1024 * 1024) { 2 })
      Files.setLastModifiedTime(source, modified)
      assertNotEquals(large, fingerprint())
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  @Test
  fun `repository fingerprint changes when tracked content changes`() {
    val repoRoot = initRepo("skillbill-fingerprint-tracked")
    val operations = GitWorkflowGitOperations()
    val before = operations.repositoryFingerprint(repoRoot)
    Files.writeString(repoRoot.resolve("tracked.txt"), "base\nchanged\n")
    val after = operations.repositoryFingerprint(repoRoot)
    Files.writeString(repoRoot.resolve("tracked.txt"), "base\nchanged\nagain\n")
    val later = operations.repositoryFingerprint(repoRoot)

    assertTrue(before is WorkflowGitOperationResult.Ok, before.error)
    assertTrue(after is WorkflowGitOperationResult.Ok, after.error)
    assertTrue(later is WorkflowGitOperationResult.Ok, later.error)
    assertFalse(before.value == after.value)
    assertFalse(after.value == later.value)
    assertEquals(later.value, operations.repositoryFingerprint(repoRoot).value)
  }

  @Test
  fun `repository fingerprint stays within the durable fingerprint bound`() {
    val repoRoot = initRepo("skillbill-fingerprint-bound")
    repeat(20) { index ->
      Files.writeString(repoRoot.resolve("modified-$index.txt"), "content-$index\n")
    }
    git(repoRoot, "add", ".")
    git(repoRoot, "commit", "-m", "many files")
    repeat(20) { index ->
      Files.writeString(repoRoot.resolve("modified-$index.txt"), "changed-$index\n")
    }

    val fingerprint = GitWorkflowGitOperations().repositoryFingerprint(repoRoot)

    assertTrue(fingerprint is WorkflowGitOperationResult.Ok, fingerprint.error)
    assertTrue(
      fingerprint.value.orEmpty().length <= MAX_REPOSITORY_FINGERPRINT_LENGTH,
      fingerprint.value.orEmpty(),
    )
  }

  @Test
  fun `repository fingerprint skips an untracked dangling symlink instead of failing`() {
    val repoRoot = initRepo("skillbill-fingerprint-symlink")
    Files.createSymbolicLink(repoRoot.resolve("dangling.link"), repoRoot.resolve("never-created.txt"))

    val fingerprint = GitWorkflowGitOperations().repositoryFingerprint(repoRoot)

    assertTrue(fingerprint is WorkflowGitOperationResult.Ok, fingerprint.error)
  }

  @Test
  fun `repository fingerprint does not follow an untracked symlink out of the repository`() {
    val repoRoot = initRepo("skillbill-fingerprint-escaping-symlink")
    val outside = Files.createTempDirectory("skillbill-fingerprint-outside").resolve("secret.txt")
    Files.writeString(outside, "outside-one\n")
    Files.createSymbolicLink(repoRoot.resolve("outside.link"), outside)
    val operations = GitWorkflowGitOperations()
    val before = operations.repositoryFingerprint(repoRoot)
    Files.writeString(outside, "outside-two\n")
    val after = operations.repositoryFingerprint(repoRoot)

    assertTrue(before is WorkflowGitOperationResult.Ok, before.error)
    assertTrue(after is WorkflowGitOperationResult.Ok, after.error)
    assertEquals(before.value, after.value)
  }

  @Test
  fun `repository fingerprint digests an oversized untracked file by size and mtime`() {
    val repoRoot = initRepo("skillbill-fingerprint-large")
    val large = repoRoot.resolve("large.bin")
    Files.write(large, ByteArray(2 * 1024 * 1024) { 1 })
    val operations = GitWorkflowGitOperations()
    val before = operations.repositoryFingerprint(repoRoot)
    Files.write(large, ByteArray(3 * 1024 * 1024) { 1 })
    val after = operations.repositoryFingerprint(repoRoot)

    assertTrue(before is WorkflowGitOperationResult.Ok, before.error)
    assertTrue(after is WorkflowGitOperationResult.Ok, after.error)
    assertFalse(before.value == after.value)
  }

  @Test
  fun `repository fingerprint reports an untracked file it cannot read without failing the phase`() {
    val repoRoot = initRepo("skillbill-fingerprint-unreadable")
    val unreadable = repoRoot.resolve("unreadable.txt")
    Files.writeString(unreadable, "secret\n")
    unreadable.toFile().setReadable(false, false)

    val fingerprint = GitWorkflowGitOperations().repositoryFingerprint(repoRoot)

    assertTrue(fingerprint is WorkflowGitOperationResult.Ok, fingerprint.error)
  }

  @Test
  fun `repository fingerprint comes from the git adapter, never a stand-in default`() {
    val repoRoot = initRepo("skillbill-fingerprint-adapter")

    val fingerprint = GitWorkflowGitOperations().repositoryFingerprint(repoRoot)

    assertTrue(fingerprint is WorkflowGitOperationResult.Ok, fingerprint.error)
    assertNotEquals(NoopWorkflowGitOperations.repositoryFingerprint(repoRoot).value, fingerprint.value)
  }

  private fun initRepo(prefix: String): Path {
    val repoRoot = Files.createTempDirectory(prefix)
    git(repoRoot, "init")
    git(repoRoot, "config", "user.email", "skill-bill@example.test")
    git(repoRoot, "config", "user.name", "Skill Bill")
    Files.writeString(repoRoot.resolve("tracked.txt"), "base\n")
    git(repoRoot, "add", ".")
    git(repoRoot, "commit", "-m", "initial")
    return repoRoot
  }

  private fun git(
    repoRoot: Path,
    vararg args: String,
  ): String {
    val output = runGit(repoRoot, *args)
    if (args.firstOrNull() == "init") {
      runGit(repoRoot, "config", "commit.gpgsign", "false")
      runGit(repoRoot, "config", "tag.gpgsign", "false")
    }
    return output
  }

  private fun runGit(
    repoRoot: Path,
    vararg args: String,
  ): String {
    val process =
      ProcessBuilder(listOf("git", "-C", repoRoot.toString()) + args)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText().trim()
    val exitCode = process.waitFor()
    check(exitCode == 0) { "git ${args.joinToString(" ")} failed with $exitCode: $output" }
    return output
  }
}
