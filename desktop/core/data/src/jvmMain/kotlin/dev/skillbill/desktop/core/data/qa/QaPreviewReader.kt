package dev.skillbill.desktop.core.data.qa

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.domain.model.QaPreviewRequest
import dev.skillbill.desktop.core.domain.model.QaPreviewState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.InvalidPathException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

internal class QaPreviewReader(
  private val diagnostics: Diagnostics,
  private val io: CoroutineDispatcher = Dispatchers.IO,
  private val pause: suspend (Duration) -> Unit = { delay(it) },
  private val source: QaPreviewSource = QaPreviewSource(),
) {
  fun observe(request: QaPreviewRequest): Flow<QaPreviewState> = flow {
    if (!request.active) {
      emit(QaPreviewState.Ended)
      return@flow
    }
    var last: QaPreviewState? = null
    emit(QaPreviewState.Waiting(QaPreviewState.Reason.SourceUnavailable))
    while (true) {
      val next = try {
        runInterruptible(io) { read(request.invocationDirectory) }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: InvalidPathException) {
        QaPreviewState.Waiting(QaPreviewState.Reason.InvalidSource)
      } catch (_: NoSuchFileException) {
        QaPreviewState.Waiting(QaPreviewState.Reason.SourceUnavailable)
      } catch (_: SecurityException) {
        QaPreviewState.Waiting(QaPreviewState.Reason.ReadFailed)
      } catch (_: IOException) {
        QaPreviewState.Waiting(QaPreviewState.Reason.ReadFailed)
      }
      if (next is QaPreviewState.Waiting) {
        val previousReason = (last as? QaPreviewState.Waiting)?.reason
        if (previousReason != next.reason) withContext(io) { diagnostics.record(next.reason.category()) }
      }
      if (next != last) emit(next)
      last = next
      pause(POLL_INTERVAL)
    }
  }

  private fun read(directoryValue: String?): QaPreviewState {
    if (directoryValue.isNullOrBlank()) return waiting(QaPreviewState.Reason.SourceUnavailable)
    val invocation = try {
      Path.of(directoryValue).normalize()
    } catch (_: InvalidPathException) {
      return waiting(QaPreviewState.Reason.InvalidSource)
    }
    if (!invocation.isAbsolute) return waiting(QaPreviewState.Reason.InvalidSource)
    return when (val decoded = source.read(invocation)) {
      is XwdDecodeResult.Frame -> QaPreviewState.Live(decoded.value)
      is XwdDecodeResult.Rejected -> waiting(decoded.reason)
    }
  }

  private fun waiting(reason: QaPreviewState.Reason) = QaPreviewState.Waiting(reason)

  private fun QaPreviewState.Reason.category(): String = when (this) {
    QaPreviewState.Reason.SourceUnavailable -> "qa_preview_source_unavailable"
    QaPreviewState.Reason.InvalidSource -> "qa_preview_source_invalid"
    QaPreviewState.Reason.IncompleteFrame -> "qa_preview_frame_incomplete"
    QaPreviewState.Reason.UnsupportedFormat -> "qa_preview_format_unsupported"
    QaPreviewState.Reason.LimitsExceeded -> "qa_preview_limits_exceeded"
    QaPreviewState.Reason.ReadFailed -> "qa_preview_read_failed"
  }

  private companion object {
    val POLL_INTERVAL = 250.milliseconds
  }
}
