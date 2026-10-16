package skillbill.infrastructure.workflow.monitor

import org.junit.jupiter.api.io.TempDir
import skillbill.infrastructure.workflow.git.runGit
import skillbill.ports.monitor.model.MonitorPublicationRefusal
import skillbill.ports.monitor.model.MonitorPublicationRequest
import skillbill.ports.monitor.model.MonitorPublicationResult
import skillbill.ports.monitor.model.MonitorWorktree
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private const val OWNED_BRANCH = "skill-bill/monitor/publisher-test"

class GitMonitorRepairPublisherTest {
  @TempDir
  lateinit var temp: Path

  private val publisher = GitMonitorRepairPublisher()

  private fun fixture() = MonitorRepoFixture(Files.createDirectories(temp.toRealPath().resolve("case")))

  private fun ownedWorktree(fixture: MonitorRepoFixture): MonitorWorktree {
    val path = temp.toRealPath().resolve("owned")
    runGit(fixture.caller, "worktree", "add", "-b", OWNED_BRANCH, path.toString(), fixture.headSha)
    runGit(path, "config", "user.email", "monitor@example.com")
    runGit(path, "config", "user.name", "Monitor")
    return MonitorWorktree(path, OWNED_BRANCH)
  }

  private fun MonitorWorktree.repair(fixture: MonitorRepoFixture): String {
    fixture.commitFile("repair.txt", "repair", inside = path)
    return runGit(path, "rev-parse", "HEAD")
  }

  private fun remoteHead(
    remote: Path,
    branch: String,
  ) = runGit(remote, "rev-parse", "refs/heads/$branch")

  @Test
  fun `an accepted push advances only the head ref and still runs the repository's own pre-push hook`() {
    val fixture = fixture()
    val marker = temp.resolve("original-hook-ran")
    val hook = fixture.caller.resolve(".git/hooks/pre-push")
    Files.writeString(hook, "#!/bin/sh\ncat > /dev/null\ntouch '$marker'\n")
    hook.toFile().setExecutable(true)
    val worktree = ownedWorktree(fixture)
    val repair = worktree.repair(fixture)

    val result =
      publisher.publish(MonitorPublicationRequest(worktree, fixture.target().head, fixture.headSha, repair))

    assertEquals(MonitorPublicationResult.Acknowledged(repair), result)
    assertEquals(repair, remoteHead(fixture.remote, FEATURE_BRANCH))
    assertEquals(fixture.baseSha, remoteHead(fixture.remote, "main"))
    assertTrue(Files.exists(marker), "the repository's pre-push hook must still run behind the guard")
  }

  @Test
  fun `a head that moved past the last published commit is refused and the remote is left alone`() {
    val fixture = fixture()
    val worktree = ownedWorktree(fixture)
    val repair = worktree.repair(fixture)
    fixture.commitFile("someone-else.txt", "teammate push")
    runGit(fixture.caller, "push", "origin", FEATURE_BRANCH)
    val advanced = remoteHead(fixture.remote, FEATURE_BRANCH)

    val result =
      publisher.publish(MonitorPublicationRequest(worktree, fixture.target().head, fixture.headSha, repair))

    assertEquals(MonitorPublicationRefusal.GUARD_REFUSED, assertIs<MonitorPublicationResult.Rejected>(result).reason)
    assertEquals(advanced, remoteHead(fixture.remote, FEATURE_BRANCH))
  }

  @Test
  fun `a fork head is pushed to its own endpoint and never to origin`() {
    val fixture = fixture()
    val fork = temp.toRealPath().resolve("fork.git")
    runGit(temp, "clone", "--bare", fixture.remote.toString(), fork.toString())
    val worktree = ownedWorktree(fixture)
    val repair = worktree.repair(fixture)

    val forkHead = fixture.target(headFetchUrl = fork.toString()).head

    val result = publisher.publish(MonitorPublicationRequest(worktree, forkHead, fixture.headSha, repair))

    assertEquals(MonitorPublicationResult.Acknowledged(repair), result)
    assertEquals(repair, remoteHead(fork, FEATURE_BRANCH))
    assertEquals(fixture.headSha, remoteHead(fixture.remote, FEATURE_BRANCH))
  }

  @Test
  fun `a branch that is not at the repair commit is refused before anything is pushed`() {
    val fixture = fixture()
    val worktree = ownedWorktree(fixture)
    worktree.repair(fixture)

    val result =
      publisher.publish(MonitorPublicationRequest(worktree, fixture.target().head, fixture.headSha, fixture.baseSha))

    assertEquals(
      MonitorPublicationRefusal.LOCAL_STATE_MISMATCH,
      assertIs<MonitorPublicationResult.Rejected>(result).reason,
    )
    assertEquals(fixture.headSha, remoteHead(fixture.remote, FEATURE_BRANCH))
  }

  private fun installHook(
    path: Path,
    body: String,
  ) {
    Files.createDirectories(path.parent)
    Files.writeString(path, "#!/bin/sh\n$body\n")
    path.toFile().setExecutable(true)
  }

  private fun publish(
    worktree: MonitorWorktree,
    fixture: MonitorRepoFixture,
    repair: String,
    headFetchUrl: String = fixture.remote.toString(),
  ): MonitorPublicationResult =
    publisher.publish(
      MonitorPublicationRequest(worktree, fixture.target(headFetchUrl = headFetchUrl).head, fixture.headSha, repair),
    )

  private fun assertNotPublished(
    result: MonitorPublicationResult,
    worktree: MonitorWorktree,
    repair: String,
  ) {
    assertFalse(result is MonitorPublicationResult.Acknowledged, "no publication receipt may be issued: $result")
    assertEquals(repair, runGit(worktree.path, "rev-parse", "refs/heads/${worktree.branch}"), "the repair is retained")
  }

  @Test
  fun `a teammate push that lands after the head was advertised is refused by the server and survives`() {
    val fixture = fixture()
    val teammate = temp.toRealPath().resolve("teammate")
    runGit(temp, "clone", "-b", FEATURE_BRANCH, fixture.remote.toString(), teammate.toString())
    runGit(teammate, "config", "user.email", "teammate@example.com")
    runGit(teammate, "config", "user.name", "Teammate")
    fixture.commitFile("teammate.txt", "teammate", inside = teammate)
    val teammateSha = runGit(teammate, "rev-parse", "HEAD")
    installHook(
      fixture.caller.resolve(".git/hooks/pre-push"),
      "cat > /dev/null\nenv -u GIT_DIR -u GIT_WORK_TREE -u GIT_INDEX_FILE -u GIT_COMMON_DIR " +
        "-u GIT_CONFIG_PARAMETERS git -C '$teammate' push -q origin $FEATURE_BRANCH",
    )
    val worktree = ownedWorktree(fixture)
    val repair = worktree.repair(fixture)

    val result = publish(worktree, fixture, repair)

    assertNotPublished(result, worktree, repair)
    assertEquals(teammateSha, remoteHead(fixture.remote, FEATURE_BRANCH), "the concurrent commit must survive")
  }

  @Test
  fun `a repair that does not fast-forward the published head is refused instead of forced`() {
    val fixture = fixture()
    val worktree = ownedWorktree(fixture)
    runGit(worktree.path, "reset", "--hard", fixture.baseSha)
    val repair = worktree.repair(fixture)

    val result = publish(worktree, fixture, repair)

    assertNotPublished(result, worktree, repair)
    assertEquals(fixture.headSha, remoteHead(fixture.remote, FEATURE_BRANCH))
  }

  @Test
  fun `a fork that no longer has the head branch is refused and no branch is created there`() {
    val fixture = fixture()
    val fork = temp.toRealPath().resolve("fork.git")
    runGit(temp, "clone", "--bare", fixture.remote.toString(), fork.toString())
    runGit(fork, "update-ref", "-d", "refs/heads/$FEATURE_BRANCH")
    val worktree = ownedWorktree(fixture)
    val repair = worktree.repair(fixture)

    val result = publish(worktree, fixture, repair, headFetchUrl = fork.toString())

    assertEquals(MonitorPublicationRefusal.GUARD_REFUSED, assertIs<MonitorPublicationResult.Rejected>(result).reason)
    assertNotPublished(result, worktree, repair)
    assertEquals("", runGit(fork, "for-each-ref", "--format=%(refname)", "refs/heads/$FEATURE_BRANCH"))
  }

  @Test
  fun `an unwritable fork rejects the push without a receipt`() {
    val fixture = fixture()
    val fork = temp.toRealPath().resolve("readonly-fork.git")
    runGit(temp, "clone", "--bare", fixture.remote.toString(), fork.toString())
    val worktree = ownedWorktree(fixture)
    val repair = worktree.repair(fixture)
    val writable = fork.toFile().walkTopDown().filter { it.canWrite() }.toList()
    writable.forEach { it.setWritable(false, false) }
    val result =
      try {
        publish(worktree, fixture, repair, headFetchUrl = fork.toString())
      } finally {
        writable.forEach { it.setWritable(true, true) }
      }

    assertNotPublished(result, worktree, repair)
    assertEquals(fixture.headSha, remoteHead(fork, FEATURE_BRANCH))
  }

  @Test
  fun `the repository's own pre-push hook can still refuse the publication`() {
    val fixture = fixture()
    val marker = temp.resolve("policy-hook-ran")
    installHook(fixture.caller.resolve(".git/hooks/pre-push"), "cat > /dev/null\ntouch '$marker'\nexit 1")
    val worktree = ownedWorktree(fixture)
    val repair = worktree.repair(fixture)

    val result = publish(worktree, fixture, repair)

    assertTrue(Files.exists(marker), "the repository hook must be consulted")
    assertNotPublished(result, worktree, repair)
    assertEquals(fixture.headSha, remoteHead(fixture.remote, FEATURE_BRANCH))
  }

  @Test
  fun `a remote head that moves right after the accepted push is reported uncertain rather than published`() {
    val fixture = fixture()
    runGit(fixture.caller, "checkout", "-b", "side")
    fixture.commitFile("side.txt", "side")
    runGit(fixture.caller, "push", "origin", "side")
    val sideSha = runGit(fixture.caller, "rev-parse", "HEAD")
    runGit(fixture.caller, "checkout", FEATURE_BRANCH)
    installHook(
      fixture.remote.resolve("hooks/post-receive"),
      "cat > /dev/null\ngit update-ref refs/heads/$FEATURE_BRANCH $sideSha",
    )
    val worktree = ownedWorktree(fixture)
    val repair = worktree.repair(fixture)

    val result = publish(worktree, fixture, repair)

    assertIs<MonitorPublicationResult.Uncertain>(result)
    assertNotPublished(result, worktree, repair)
    assertNotEquals(repair, remoteHead(fixture.remote, FEATURE_BRANCH))
  }

  @Test
  fun `a head endpoint the repository rewrites with insteadOf is still published to the pinned head`() {
    val fixture = fixture()
    val pinnedUrl = "https://github.com/acme/repo.git"
    runGit(fixture.caller, "config", "url.${fixture.remote}.insteadOf", pinnedUrl)
    val worktree = ownedWorktree(fixture)
    val repair = worktree.repair(fixture)

    val result = publish(worktree, fixture, repair, headFetchUrl = pinnedUrl)

    assertEquals(MonitorPublicationResult.Acknowledged(repair), result)
    assertEquals(repair, remoteHead(fixture.remote, FEATURE_BRANCH))
  }
}
