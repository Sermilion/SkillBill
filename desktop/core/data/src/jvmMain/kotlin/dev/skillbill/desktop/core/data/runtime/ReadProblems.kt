package dev.skillbill.desktop.core.data.runtime

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.Outcome
import dev.skillbill.desktop.core.data.session.StorageFailureClassifier
import dev.skillbill.desktop.core.domain.model.ReadProblem
import skillbill.di.core.RuntimeComponent
import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs [read] and returns its value, or the [ReadProblem] it failed with: a classified storage failure, otherwise the runtime
 * failure's message, recorded as a diagnostic. Cancellation is rethrown.
 */
internal suspend fun <T> readOrProblem(
  runtime: RuntimeComponent,
  diagnostics: Diagnostics,
  read: suspend () -> T,
): Outcome<T, ReadProblem> = try {
  Outcome.Success(read())
} catch (failure: CancellationException) {
  throw failure
} catch (failure: Exception) {
  val path = runCatching { runtime.databaseSession.resolveDbPath().toString() }.getOrDefault("")
  val storage = StorageFailureClassifier.classify(failure, path)
  if (storage == null) diagnostics.record("desktop_read_failed")
  Outcome.Failure(storage?.let(ReadProblem::Storage) ?: ReadProblem.Runtime(failure.message ?: failure::class.java.simpleName))
}
