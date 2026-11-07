package dev.skillbill.desktop.core.data.qa

import dev.skillbill.desktop.core.domain.model.QaPreviewFrame
import dev.skillbill.desktop.core.domain.model.QaPreviewState
import kotlinx.coroutines.CancellationException
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal sealed interface XwdDecodeResult {
  data class Frame(val value: QaPreviewFrame) : XwdDecodeResult
  data class Rejected(val reason: QaPreviewState.Reason) : XwdDecodeResult
}

internal object XwdFrameDecoder {
  private const val HEADER_BYTES = 100
  private const val COLOR_BYTES = 12
  private const val MAX_BYTES = 33_554_432
  private const val MAX_PIXELS = 4_194_304
  private const val MAX_DIMENSION = 4096
  private const val MAX_HEADER = 65_536
  private const val MAX_COLORS = 65_536

  fun decode(bytes: ByteArray, validBytes: Int = bytes.size): XwdDecodeResult {
    if (validBytes < 0 || validBytes > bytes.size || validBytes > MAX_BYTES) return rejected(QaPreviewState.Reason.LimitsExceeded)
    if (validBytes < HEADER_BYTES) return rejected(QaPreviewState.Reason.IncompleteFrame)
    val header = ByteBuffer.wrap(bytes, 0, HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
    val values = LongArray(25) { header.int.toLong() and 0xffff_ffffL }
    return decodeHeader(bytes, validBytes, values)
  }

  private fun decodeHeader(bytes: ByteArray, validBytes: Int, values: LongArray): XwdDecodeResult {
    val headerSize = values[0]
    val version = values[1]
    val format = values[2]
    val depth = values[3]
    val width = values[4]
    val height = values[5]
    val xOffset = values[6]
    val byteOrder = values[7]
    val bitsPerPixel = values[11]
    val stride = values[12]
    val visual = values[13]
    val red = values[14]
    val green = values[15]
    val blue = values[16]
    val colors = values[19]
    if (headerSize !in HEADER_BYTES.toLong()..MAX_HEADER.toLong() || colors > MAX_COLORS.toLong()) {
      return rejected(QaPreviewState.Reason.LimitsExceeded)
    }
    if (width !in 1L..MAX_DIMENSION.toLong() || height !in 1L..MAX_DIMENSION.toLong() || width * height > MAX_PIXELS.toLong()) {
      return rejected(QaPreviewState.Reason.LimitsExceeded)
    }
    if (version != 7L) return rejected(QaPreviewState.Reason.InvalidSource)
    if (headerSize > validBytes) return rejected(QaPreviewState.Reason.IncompleteFrame)
    if (format != 2L || visual != 4L || depth != 24L || bitsPerPixel != 32L) {
      return rejected(QaPreviewState.Reason.UnsupportedFormat)
    }
    if (xOffset != 0L || byteOrder !in 0L..1L) return rejected(QaPreviewState.Reason.InvalidSource)
    if (!validMask(red) || !validMask(green) || !validMask(blue) || (red and green) != 0L || (red and blue) != 0L || (green and blue) != 0L) {
      return rejected(QaPreviewState.Reason.InvalidSource)
    }
    val rowBytes = width * 4L
    if (stride < rowBytes) return rejected(QaPreviewState.Reason.InvalidSource)
    if (stride > MAX_BYTES.toLong()) return rejected(QaPreviewState.Reason.LimitsExceeded)
    val colorOffset = safeAdd(headerSize, safeMultiply(colors, COLOR_BYTES.toLong()) ?: return rejected(QaPreviewState.Reason.LimitsExceeded))
      ?: return rejected(QaPreviewState.Reason.LimitsExceeded)
    val imageBytes = safeMultiply(stride, height) ?: return rejected(QaPreviewState.Reason.LimitsExceeded)
    val imageEnd = safeAdd(colorOffset, imageBytes) ?: return rejected(QaPreviewState.Reason.LimitsExceeded)
    if (imageEnd > MAX_BYTES.toLong()) return rejected(QaPreviewState.Reason.LimitsExceeded)
    if (imageEnd > validBytes) return rejected(QaPreviewState.Reason.IncompleteFrame)
    val pixelData = ByteBuffer.wrap(bytes, 0, validBytes).order(if (byteOrder == 1L) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
    val pixels = IntArray((width * height).toInt())
    val redShift = red.countTrailingZeroBits()
    val greenShift = green.countTrailingZeroBits()
    val blueShift = blue.countTrailingZeroBits()
    for (y in 0 until height.toInt()) {
      if (Thread.currentThread().isInterrupted) throw CancellationException("XWD frame decoding was cancelled")
      val row = colorOffset.toInt() + y * stride.toInt()
      for (x in 0 until width.toInt()) {
        val pixel = pixelData.getInt(row + x * 4).toLong() and 0xffff_ffffL
        pixels[y * width.toInt() + x] = (0xff shl 24) or
          (((pixel and red) ushr redShift).toInt() shl 16) or
          (((pixel and green) ushr greenShift).toInt() shl 8) or
          ((pixel and blue) ushr blueShift).toInt()
      }
    }
    return XwdDecodeResult.Frame(QaPreviewFrame(width.toInt(), height.toInt(), pixels))
  }

  private fun validMask(mask: Long): Boolean {
    if (mask == 0L) return false
    val shifted = mask ushr mask.countTrailingZeroBits()
    return shifted == 255L
  }

  private fun safeMultiply(left: Long, right: Long): Long? = if (left != 0L && right > Long.MAX_VALUE / left) null else left * right
  private fun safeAdd(left: Long, right: Long): Long? = if (right > Long.MAX_VALUE - left) null else left + right
  private fun rejected(reason: QaPreviewState.Reason) = XwdDecodeResult.Rejected(reason)
}
