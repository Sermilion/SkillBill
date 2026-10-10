package skillbill.di.core

import java.io.PrintWriter
import java.io.StringWriter
import java.util.logging.ConsoleHandler
import java.util.logging.Formatter
import java.util.logging.Level
import java.util.logging.LogManager
import java.util.logging.LogRecord
import java.util.logging.Logger

fun verboseLoggingRequestedByEnvironment(environment: Map<String, String>): Boolean {
  val value = environment[ProcessLoggingEnvironmentKeys.SKILL_BILL_VERBOSE]?.trim() ?: return false
  return value == "1" || value.equals("true", ignoreCase = true)
}

fun configureProcessLogging(verbose: Boolean) {
  LogManager.getLogManager().reset()
  val root = Logger.getLogger("")
  if (!verbose) {
    root.level = Level.OFF
    return
  }
  root.level = Level.ALL
  val handler = ConsoleHandler()
  handler.level = Level.ALL
  handler.formatter = ProcessLoggingFormatter()
  root.addHandler(handler)
}

private class ProcessLoggingFormatter : Formatter() {
  override fun format(record: LogRecord): String {
    val line =
      "${record.level.name} ${record.sourceClassName}.${record.sourceMethodName}: ${formatMessage(record)}\n"
    val thrown = record.thrown ?: return line
    val buffer = StringWriter()
    thrown.printStackTrace(PrintWriter(buffer))
    return line + buffer.toString()
  }
}
