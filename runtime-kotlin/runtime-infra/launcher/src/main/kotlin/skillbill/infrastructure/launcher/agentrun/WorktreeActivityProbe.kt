package skillbill.infrastructure.launcher.agentrun

import skillbill.infrastructure.launcher.process.launch.AgentRunActivityProbe
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit

internal class WorktreeActivityProbe(
  private val root: Path,
  private val scanIntervalNanos: Long = WORKTREE_ACTIVITY_SCAN_INTERVAL_NANOS,
) : AgentRunActivityProbe {
  private var lastScanNanos: Long? = null
  private var lastToken: String? = null

  override fun activityToken(): String? {
    val nowNanos = System.nanoTime()
    val previousScanNanos = lastScanNanos
    if (previousScanNanos != null && nowNanos - previousScanNanos < scanIntervalNanos) {
      return lastToken
    }
    lastScanNanos = nowNanos
    lastToken = scanToken()
    return lastToken
  }

  override fun activityLabel(): String = "worktree files changed"

  private fun scanToken(): String? {
    if (!Files.isDirectory(root)) return null
    val stats = WorktreeActivityStats()
    try {
      Files.walkFileTree(root, WorktreeActivityVisitor(root, stats))
    } catch (_: IOException) {
    }
    return stats.token()
  }
}

private class WorktreeActivityVisitor(
  private val root: Path,
  private val stats: WorktreeActivityStats,
) : SimpleFileVisitor<Path>() {
  override fun preVisitDirectory(
    dir: Path,
    attrs: BasicFileAttributes,
  ): FileVisitResult =
    if (dir != root && dir.fileName?.toString() in IGNORED_WORKTREE_DIRECTORIES) {
      FileVisitResult.SKIP_SUBTREE
    } else {
      FileVisitResult.CONTINUE
    }

  override fun visitFile(
    file: Path,
    attrs: BasicFileAttributes,
  ): FileVisitResult {
    if (attrs.isRegularFile && file.fileName?.toString() !in IGNORED_WORKTREE_FILES) {
      stats.include(file, attrs)
    }
    return FileVisitResult.CONTINUE
  }

  override fun visitFileFailed(
    file: Path,
    exc: IOException,
  ): FileVisitResult = FileVisitResult.CONTINUE
}

private class WorktreeActivityStats {
  private var fileCount: Long = 0
  private var latestModifiedNanos: Long = 0
  private var fingerprint: Long = 0

  fun include(
    file: Path,
    attrs: BasicFileAttributes,
  ) {
    val modifiedNanos = attrs.lastModifiedTime().to(TimeUnit.NANOSECONDS)
    fileCount += 1
    latestModifiedNanos = latestModifiedNanos.coerceAtLeast(modifiedNanos)
    fingerprint = fingerprint xor (modifiedNanos + WORKTREE_ACTIVITY_HASH_MULTIPLIER * file.toString().hashCode())
  }

  fun token(): String = "$fileCount|$latestModifiedNanos|$fingerprint"
}

private val IGNORED_WORKTREE_DIRECTORIES =
  setOf(
    ".git",
    ".gradle",
    ".idea",
    ".kotlin",
    ".mypy_cache",
    ".pytest_cache",
    "build",
    "node_modules",
    "out",
    "target",
  )

private val IGNORED_WORKTREE_FILES =
  setOf(
    ".DS_Store",
  )

private const val WORKTREE_ACTIVITY_HASH_MULTIPLIER = 31L
private const val WORKTREE_ACTIVITY_SCAN_INTERVAL_SECONDS = 5L
private val WORKTREE_ACTIVITY_SCAN_INTERVAL_NANOS =
  TimeUnit.SECONDS.toNanos(WORKTREE_ACTIVITY_SCAN_INTERVAL_SECONDS)
