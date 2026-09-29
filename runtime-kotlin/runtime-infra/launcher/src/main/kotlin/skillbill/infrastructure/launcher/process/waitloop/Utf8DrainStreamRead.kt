package skillbill.infrastructure.launcher.process.waitloop

import skillbill.infrastructure.launcher.process.launch.Utf8Drain
import java.nio.CharBuffer
import java.nio.charset.CoderResult

internal fun Utf8Drain.decodeAvailable(
  decoded: CharBuffer,
  decode: () -> CoderResult,
) {
  while (true) {
    val result = decode()
    decoded.flip()
    if (decoded.hasRemaining()) {
      val chunk = decoded.toString()
      onChunkRead(chunk)
      outputSink.write(outputStream, chunk)
    }
    decoded.clear()
    if (!result.isOverflow) return
  }
}
