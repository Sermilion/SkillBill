package skillbill.infrastructure.workflow.goalplanning

import skillbill.text.Utf8Text
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Logger

internal fun goalPlanningReadFileOrNull(
  path: Path,
  maxBytes: Long,
): BoundaryFileRead? {
  val cap = maxBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
  val bytes =
    try {
      Files.newInputStream(path).use { input -> input.readNBytes(cap) }
    } catch (error: IOException) {
      goalPlanningIoLog.warning("goal planning could not read $path: ${error.message}")
      return null
    }
  return BoundaryFileRead(bytes.decodeToString(), cut = bytes.size >= cap)
}

internal fun goalPlanningTruncateToUtf8Bytes(
  text: String,
  maxBytes: Int,
): String = Utf8Text.truncateToUtf8Bytes(text, maxBytes)

internal fun goalPlanningUtf8Size(text: String): Int = Utf8Text.utf8Size(text)

private val goalPlanningIoLog: Logger =
  Logger.getLogger("skillbill.infrastructure.workflow.goalplanning.GoalPlanningRepositoryScopeIo")
