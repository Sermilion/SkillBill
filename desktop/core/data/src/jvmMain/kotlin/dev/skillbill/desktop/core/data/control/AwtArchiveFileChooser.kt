package dev.skillbill.desktop.core.data.control

import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.domain.repository.ArchiveFileChooser
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.awt.FileDialog
import java.awt.Frame
import java.io.FilenameFilter
import javax.swing.SwingUtilities

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class AwtArchiveFileChooser : ArchiveFileChooser {
  override fun saveArchive(suggestedName: String, onChosen: (String?) -> Unit) {
    SwingUtilities.invokeLater {
      val dialog = FileDialog(Frame.getFrames().firstOrNull { it.isShowing }, "Export work", FileDialog.SAVE)
      dialog.file = suggestedName
      dialog.isVisible = true
      onChosen(dialog.file?.let { file -> dialog.directory?.let { directory -> java.io.File(directory, file).toPath().toString() } })
      dialog.dispose()
    }
  }

  override fun openArchive(onChosen: (String?) -> Unit) {
    SwingUtilities.invokeLater {
      val dialog = FileDialog(Frame.getFrames().firstOrNull { it.isShowing }, "Import work", FileDialog.LOAD)
      dialog.filenameFilter = FilenameFilter { _, name -> name.endsWith(".zip", ignoreCase = true) }
      dialog.isVisible = true
      onChosen(dialog.file?.let { file -> dialog.directory?.let { directory -> java.io.File(directory, file).toPath().toString() } })
      dialog.dispose()
    }
  }
}
