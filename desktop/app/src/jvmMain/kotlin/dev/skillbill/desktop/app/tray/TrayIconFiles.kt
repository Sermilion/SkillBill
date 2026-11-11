package dev.skillbill.desktop.app.tray

import dev.nucleusframework.composenativetray.utils.ComposableIconUtils
import dev.nucleusframework.composenativetray.utils.IconRenderProperties
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.domain.model.TrayStatus
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

data class TrayIconPaths(val png: String, val ico: String)
data class TrayIconRenderResult(val paths: TrayIconPaths, val imagePng: ByteArray)

/** Keeps the current tray icon and the one before it in [directory], named by content so a changed icon always has a new path. */
class TrayIconFiles(private val directory: () -> Path) {
  private val prefix = "${UUID.randomUUID()}-"
  private val directories = mutableSetOf<Path>()
  private var currentStem: String? = null
  private var closed = false

  /** Synchronized because a cancelled effect's blocking render can overlap the next one. */
  @Synchronized
  fun write(png: ByteArray, ico: ByteArray): TrayIconPaths {
    check(!closed)
    val target = Files.createDirectories(directory())
    directories.add(target)
    val stem = prefix + MessageDigest.getInstance("SHA-256").digest(png).take(8).joinToString("") { "%02x".format(it) }
    // An identical icon is rewritten too, which refreshes its modified time for the scratch sweep.
    val paths = TrayIconPaths(
      png = writeAtomically(target, "$stem.png", png).toString(),
      ico = writeAtomically(target, "$stem.ico", ico).toString(),
    )
    prune(target, setOfNotNull(stem, currentStem))
    currentStem = stem
    return paths
  }

  @Synchronized
  fun close() {
    closed = true
    directories.forEach { target ->
      try {
        prune(target, emptySet())
      } catch (_: IOException) {
        TrayDiagnostics.record("tray_icon_cleanup_failed")
      }
    }
  }

  private fun writeAtomically(target: Path, name: String, bytes: ByteArray): Path {
    val temporary = Files.createTempFile(target, "icon-", ".tmp")
    try {
      Files.write(temporary, bytes)
      return Files.move(temporary, target.resolve(name), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (failure: IOException) {
      Files.deleteIfExists(temporary)
      throw failure
    }
  }

  private fun prune(target: Path, keptStems: Set<String>) {
    val stale = Files.list(target).use { entries ->
      entries.filter {
        val name = it.fileName.toString()
        name.startsWith(prefix) && name.substringBeforeLast('.') !in keptStems
      }.toList()
    }
    stale.forEach { entry ->
      try {
        Files.deleteIfExists(entry)
      } catch (_: IOException) {
        TrayDiagnostics.record("tray_icon_cleanup_failed")
      }
    }
  }
}

fun renderTrayIcon(
  files: TrayIconFiles,
  status: TrayStatus,
  running: Int,
  showOverlay: Boolean,
  reducedMotion: Boolean,
  showRunningCount: Boolean = showOverlay,
): TrayIconRenderResult {
  val properties = IconRenderProperties()
  val png = ComposableIconUtils.renderComposableToPngBytes(properties) {
    SkillBillTheme(reducedMotion) { TrayIconRenderer.Content(status, running, showOverlay, showRunningCount) }
  }
  val ico = ComposableIconUtils.renderComposableToIcoBytes(properties) {
    SkillBillTheme(reducedMotion) { TrayIconRenderer.Content(status, running, showOverlay, showRunningCount) }
  }
  return TrayIconRenderResult(files.write(png, ico), png)
}
