package dev.skillbill.desktop.core.domain.repository

interface ArchiveFileChooser {
  fun saveArchive(suggestedName: String, onChosen: (String?) -> Unit)

  fun openArchive(onChosen: (String?) -> Unit)
}
