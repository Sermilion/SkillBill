package dev.skillbill.desktop.core.ui.feedback

import dev.skillbill.desktop.core.domain.model.ReadProblem
import dev.skillbill.desktop.core.domain.session.StorageFailure

/** The copy for each storage failure, as `desktop-tray`'s session problem mapping shows it. */
fun storageFailureText(failure: StorageFailure): String = when (failure) {
  StorageFailure.Missing -> "The selected database does not exist. Choose an existing runtime store."
  StorageFailure.Access -> "The selected database could not be read. Check its path and access permissions."
  StorageFailure.Busy -> "The database is busy. Retry after the writer finishes."
  StorageFailure.Corrupt -> "The selected database is corrupt. Restore it through the runtime's supported recovery route."
  StorageFailure.IncompatibleSchema ->
    "The database is incompatible with this app. Update the runtime and apply its supported migrations before reconnecting."
  StorageFailure.IncompatibleRecord -> "A saved workflow record could not be read. Check the runtime diagnostics for the affected workflow."
}

fun readProblemText(problem: ReadProblem): String = when (problem) {
  is ReadProblem.Storage -> storageFailureText(problem.failure)
  is ReadProblem.Runtime -> problem.message
}
