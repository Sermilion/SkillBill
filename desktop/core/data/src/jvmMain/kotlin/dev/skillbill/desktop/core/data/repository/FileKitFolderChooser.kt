package dev.skillbill.desktop.core.data.repository

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.domain.repository.FolderChoice
import dev.skillbill.desktop.core.domain.repository.FolderChooser
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.openDirectoryPicker
import kotlinx.coroutines.CancellationException
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding

private const val CHOOSER_FAILED = "desktop_folder_chooser_failed"

/** Catches [LinkageError] too, because FileKit's portal check catches only [Exception] and a missing native link must not crash the app. */
@Inject
@ContributesBinding(AppScope::class)
class FileKitFolderChooser(private val diagnostics: Diagnostics) : FolderChooser {
  override suspend fun choose(): FolderChoice = try {
    FileKit.openDirectoryPicker()?.let { FolderChoice.Chosen(it.file.absolutePath) } ?: FolderChoice.Cancelled
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    failed(e)
  } catch (e: LinkageError) {
    failed(e)
  }

  private fun failed(cause: Throwable): FolderChoice {
    diagnostics.record(CHOOSER_FAILED)
    return FolderChoice.Failed(cause.message ?: cause::class.simpleName.orEmpty())
  }
}
