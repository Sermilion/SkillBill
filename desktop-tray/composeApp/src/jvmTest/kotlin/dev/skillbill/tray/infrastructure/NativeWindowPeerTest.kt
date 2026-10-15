package dev.skillbill.tray.infrastructure

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import java.awt.Canvas
import java.awt.Component
import java.awt.Container
import java.awt.GraphicsEnvironment
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NativeWindowPeerTest {
  @Test
  fun `recreating a native window peer keeps state updates and clicks rendering`() {
    assumeFalse(GraphicsEnvironment.isHeadless(), "A native desktop or Xvfb is required")
    val rendered = AtomicInteger(-1)
    val frames = Semaphore(0)
    var count by mutableIntStateOf(0)
    var window: ComposeWindow? = null
    try {
      onUi {
        window = ComposeWindow().apply {
          setSize(320, 240)
          setContent {
            val value = count
            SideEffect { rendered.set(value); frames.release() }
            Button(onClick = { count++ }, modifier = Modifier.fillMaxSize()) { Text("Count $value") }
          }
          isVisible = true
        }
      }
      awaitRendered(0, rendered, frames)
      repeat(2) { cycle ->
        onUi {
          checkNotNull(window).apply {
            isVisible = false
            removeNotify()
            addNotify()
            isVisible = true
          }
        }
        Thread.sleep(250)
        onUi { count++ }
        awaitRendered(cycle * 2 + 1, rendered, frames)
        onUi {
          val canvas = checkNotNull(canvas(checkNotNull(window)))
          val now = System.currentTimeMillis()
          canvas.dispatchEvent(MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, now, InputEvent.BUTTON1_DOWN_MASK, 100, 100, 1, false, MouseEvent.BUTTON1))
          canvas.dispatchEvent(MouseEvent(canvas, MouseEvent.MOUSE_RELEASED, now + 1, 0, 100, 100, 1, false, MouseEvent.BUTTON1))
        }
        awaitRendered(cycle * 2 + 2, rendered, frames)
      }
      assertEquals(4, count)
    } finally {
      onUi { window?.dispose() }
    }
  }

  private fun awaitRendered(expected: Int, rendered: AtomicInteger, frames: Semaphore) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while (rendered.get() != expected) {
      val remaining = deadline - System.nanoTime()
      assertTrue(remaining > 0 && frames.tryAcquire(remaining, TimeUnit.NANOSECONDS), "Window stopped rendering at ${rendered.get()}, expected $expected")
    }
  }

  private fun canvas(component: Component): Canvas? = when (component) {
    is Canvas -> component
    is Container -> component.components.firstNotNullOfOrNull(::canvas)
    else -> null
  }

  private fun onUi(action: () -> Unit) = SwingUtilities.invokeAndWait(action)
}
