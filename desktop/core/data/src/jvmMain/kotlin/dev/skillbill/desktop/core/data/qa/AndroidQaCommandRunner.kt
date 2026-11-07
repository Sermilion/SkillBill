package dev.skillbill.desktop.core.data.qa

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.data.chat.ChatCommand
import dev.skillbill.desktop.core.data.chat.ChatProcessLauncher
import dev.skillbill.desktop.core.data.chat.ChatProcessOutput
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path

internal data class QaCommandResult(val exitCode: Int?, val stdout: String, val failureReason: String? = null)

internal interface QaCommandRunner {
  suspend fun run(executable: Path, arguments: List<String>): QaCommandResult
}

internal class AndroidQaCommandRunner(
  private val launcher: ChatProcessLauncher,
  private val host: HostEnvironment,
  private val diagnostics: Diagnostics,
  private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
  private val timeoutMillis: Long = 5_000L,
) : QaCommandRunner {
  override suspend fun run(executable: Path, arguments: List<String>): QaCommandResult = withContext(dispatcher) {
    val command = ChatCommand(
      argv = listOf(executable.toAbsolutePath().toString()) + arguments,
      workingDirectory = executable.toAbsolutePath().parent.toString(),
      environment = host.variables,
      stdin = "",
    )
    withTimeoutOrNull(timeoutMillis) {
      val output = ByteArrayOutputStream()
      var result = QaCommandResult(null, "", "command_output_unavailable")
      launcher.run(command).takeWhile { event ->
        when (event) {
          is ChatProcessOutput.Line -> {
            val bytes = event.text.toByteArray(StandardCharsets.UTF_8)
            if (bytes.size + output.size() + 1 > MAX_OUTPUT_BYTES) {
              diagnostics.record("desktop_qa_command_output_truncated")
              result = QaCommandResult(null, "", "command_output_truncated")
              false
            } else {
              output.write(bytes)
              output.write('\n'.code)
              true
            }
          }
          is ChatProcessOutput.Exited -> {
            if (event.stderrTail.isNotEmpty()) diagnostics.record("desktop_qa_command_stderr_observed")
            result = QaCommandResult(event.code, output.toString(StandardCharsets.UTF_8))
            false
          }
          is ChatProcessOutput.StartFailed -> {
            result = QaCommandResult(null, "", "command_launch_failed")
            false
          }
        }
      }.collect()
      result
    } ?: QaCommandResult(null, "", "command_timed_out")
  }

  private companion object {
    const val MAX_OUTPUT_BYTES = 256 * 1024
  }
}
