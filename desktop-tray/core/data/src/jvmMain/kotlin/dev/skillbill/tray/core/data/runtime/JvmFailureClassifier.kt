package dev.skillbill.tray.core.data.runtime

import androidx.sqlite.SQLiteException
import dev.skillbill.tray.core.common.di.AppScope
import dev.skillbill.tray.core.data.port.FailureClassifier
import dev.skillbill.tray.core.data.port.ObservationEndedException
import dev.skillbill.tray.core.data.port.ObservationUnavailableException
import dev.skillbill.tray.core.data.port.ProviderFailure
import dev.skillbill.tray.core.data.port.RuntimeFailure
import dev.skillbill.tray.core.data.session.MAX_FAILURE_CAUSES
import me.tatarka.inject.annotations.Inject
import skillbill.error.core.DatabaseFailureCode
import skillbill.error.core.SkillBillRuntimeException
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.io.IOException

/** Recognises the JVM, SQLite provider and runtime exceptions the shared repositories react to. */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class JvmFailureClassifier : FailureClassifier {
  override fun runtimeFailure(error: Throwable): RuntimeFailure? {
    if (error !is SkillBillRuntimeException) return null
    return when (error.code) {
      DatabaseFailureCode.MISSING -> RuntimeFailure.MISSING
      DatabaseFailureCode.BUSY -> RuntimeFailure.BUSY
      DatabaseFailureCode.CORRUPT -> RuntimeFailure.CORRUPT
      DatabaseFailureCode.INCOMPATIBLE_SCHEMA -> RuntimeFailure.INCOMPATIBLE_SCHEMA
      DatabaseFailureCode.ACCESS -> RuntimeFailure.ACCESS
      else -> RuntimeFailure.OTHER
    }
  }

  override fun providerFailure(error: Throwable): ProviderFailure? {
    if (error !is SQLiteException) return null
    return ProviderFailure(error.message?.substringBefore(',')?.removePrefix("Error code: ")?.toIntOrNull())
  }

  override fun isIoFailure(error: Throwable): Boolean = error is IOException

  override fun isAccessDenied(error: Throwable): Boolean = error is SecurityException

  override fun isObserverFailure(error: Throwable): Boolean = generateSequence(error) { it.cause }
    .take(MAX_FAILURE_CAUSES)
    .any {
      it is IOException || it is SQLiteException || it is SecurityException || it is UnsupportedOperationException ||
        it is ObservationEndedException || it is ObservationUnavailableException
    }

  // Observation ending used to surface as an IOException, and its telemetry label stays "ioexception".
  override fun diagnosticName(error: Throwable): String =
    if (error is ObservationEndedException) "ioexception" else error.javaClass.simpleName.lowercase()
}
