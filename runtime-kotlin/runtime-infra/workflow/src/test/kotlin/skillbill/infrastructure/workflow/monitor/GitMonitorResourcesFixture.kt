package skillbill.infrastructure.workflow.monitor

import skillbill.infrastructure.workflow.git.git
import skillbill.infrastructure.workflow.git.runGit
import skillbill.ports.monitor.MonitorProcessInspector
import skillbill.ports.monitor.model.MonitorAcquireRequest
import skillbill.ports.monitor.model.MonitorAcquisition
import skillbill.ports.monitor.model.MonitorBranchTip
import skillbill.ports.monitor.model.MonitorContextResolution
import skillbill.ports.monitor.model.MonitorFence
import skillbill.ports.monitor.model.MonitorPullRequestHead
import skillbill.ports.monitor.model.MonitorPullRequestIdentity
import skillbill.ports.monitor.model.MonitorRepositoryContext
import skillbill.ports.monitor.model.MonitorRepositoryIdentity
import skillbill.ports.monitor.model.MonitorResolvedTarget
import skillbill.ports.monitor.model.MonitorWritability
import skillbill.ports.monitor.model.MonitorWriteEvidence
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessIdentity
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertIs

internal const val FEATURE_BRANCH = "feat/SKILL-427"

internal class FakeMonitorProcessInspector(
  var inspection: FeatureTaskRuntimeProcessInspection = FeatureTaskRuntimeProcessInspection.NotRunning,
  val hostIdentity: String = "host-1",
) : MonitorProcessInspector {
  override fun currentProcess() =
    FeatureTaskRuntimeProcessIdentity(hostIdentity, "boot-1", ProcessHandle.current().pid(), "birth-1")

  override fun inspect(recorded: FeatureTaskRuntimeProcessIdentity) = inspection
}

internal class MonitorRepoFixture(root: Path) {
  val remote: Path = root.resolve("remote.git")
  val caller: Path = root.resolve("caller")
  val repository = MonitorRepositoryIdentity.canonical("github.com", "acme", "repo")
  val baseSha: String
  val headSha: String

  init {
    Files.createDirectories(remote)
    runGit(remote, "init", "--bare", "-b", "main")
    Files.createDirectories(caller)
    git(caller, "init", "-b", "main")
    runGit(caller, "config", "user.email", "monitor@example.com")
    runGit(caller, "config", "user.name", "Monitor")
    runGit(caller, "remote", "add", "origin", remote.toString())
    commitFile("README.md", "base")
    runGit(caller, "push", "origin", "main")
    baseSha = runGit(caller, "rev-parse", "HEAD")
    runGit(caller, "checkout", "-b", FEATURE_BRANCH)
    commitFile("feature.txt", "feature")
    runGit(caller, "push", "origin", FEATURE_BRANCH)
    headSha = runGit(caller, "rev-parse", "HEAD")
  }

  fun commitFile(
    name: String,
    content: String,
    inside: Path = caller,
  ) {
    Files.writeString(inside.resolve(name), content)
    runGit(inside, "add", name)
    runGit(inside, "commit", "-m", "add $name")
  }

  fun target(
    headFetchUrl: String = remote.toString(),
    expectedHeadSha: String = headSha,
  ): MonitorResolvedTarget {
    val head =
      MonitorPullRequestHead(
        MonitorBranchTip(repository, FEATURE_BRANCH, expectedHeadSha),
        headFetchUrl,
        headFetchUrl,
        MonitorWriteEvidence(repositoryPush = true, maintainerCanModify = false),
      )
    return MonitorResolvedTarget(
      MonitorPullRequestIdentity(repository, 427),
      head,
      MonitorBranchTip(repository, "main", baseSha),
      remote.toString(),
      MonitorWritability.REPOSITORY_PUSH,
    )
  }

  fun context(from: Path = caller): MonitorRepositoryContext =
    assertIs<MonitorContextResolution.Resolved>(
      GitMonitorResources(FakeMonitorProcessInspector(), Clock.systemUTC()).resolveContext(from),
    ).context

  fun refs(prefix: String): List<String> =
    runGit(caller, "for-each-ref", "--format=%(refname)", prefix).lines().filter(String::isNotBlank)

  fun snapshot(): List<String> =
    listOf(
      runGit(caller, "rev-parse", "HEAD"),
      runGit(caller, "symbolic-ref", "HEAD"),
      runGit(caller, "status", "--porcelain"),
      runGit(caller, "for-each-ref", "--format=%(refname) %(objectname)", "refs/heads", "refs/remotes")
        .lines()
        .filterNot { "skill-bill/" in it }
        .joinToString("\n"),
      runGit(caller, "config", "--get-regexp", "^remote\\."),
      Files.readString(caller.resolve("README.md")),
    )
}

internal fun acquireFor(
  resources: GitMonitorResources,
  fixture: MonitorRepoFixture,
  context: MonitorRepositoryContext = fixture.context(),
  ownerToken: String = UUID.randomUUID().toString(),
): MonitorAcquisition =
  resources.acquire(MonitorAcquireRequest(context, fixture.target(), "invocation-$ownerToken", ownerToken))

internal fun acquiredFence(acquisition: MonitorAcquisition): MonitorFence =
  assertIs<MonitorAcquisition.Acquired>(acquisition).fence

private const val LOCK_PROBE_CONTENDED = 0
private const val LOCK_PROBE_ACQUIRED = 1
private const val LOCK_PROBE_TIMEOUT_SECONDS = 60L

internal fun lockHeldByAnotherProcess(
  lockFile: Path,
  scratch: Path,
): Boolean {
  val source = scratch.resolve("LockProbe.java")
  Files.writeString(
    source,
    """
    import java.nio.channels.FileChannel;
    import java.nio.file.Path;
    import java.nio.file.StandardOpenOption;

    public class LockProbe {
      public static void main(String[] args) throws Exception {
        try (FileChannel channel = FileChannel.open(Path.of(args[0]), StandardOpenOption.WRITE)) {
          System.exit(channel.tryLock() == null ? $LOCK_PROBE_CONTENDED : $LOCK_PROBE_ACQUIRED);
        }
      }
    }
    """.trimIndent(),
  )
  val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
  val process = ProcessBuilder(java, source.toString(), lockFile.toString()).redirectErrorStream(true).start()
  val output = process.inputStream.bufferedReader().readText()
  check(process.waitFor(LOCK_PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "The lock probe did not finish." }
  return when (process.exitValue()) {
    LOCK_PROBE_CONTENDED -> true
    LOCK_PROBE_ACQUIRED -> false
    else -> error("The lock probe failed: $output")
  }
}
