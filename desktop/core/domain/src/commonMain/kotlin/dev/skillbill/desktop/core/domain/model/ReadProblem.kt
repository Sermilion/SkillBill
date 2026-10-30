package dev.skillbill.desktop.core.domain.model

import dev.skillbill.desktop.core.domain.session.StorageFailure

/** Why a read produced no result: a classified storage failure, or a runtime failure shown by its message. */
sealed interface ReadProblem {
  data class Storage(val failure: StorageFailure) : ReadProblem

  data class Runtime(val message: String) : ReadProblem
}
