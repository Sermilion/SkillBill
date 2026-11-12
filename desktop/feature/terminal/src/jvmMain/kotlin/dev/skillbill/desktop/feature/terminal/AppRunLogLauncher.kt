package dev.skillbill.desktop.feature.terminal

import com.jediterm.core.util.TermSize
import com.jediterm.terminal.Questioner
import com.jediterm.terminal.TtyConnector
import dev.skillbill.desktop.core.ui.apprun.AppRunOutput
import dev.skillbill.desktop.core.ui.apprun.AppRunOutputEvent
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

internal class AppRunLogLauncher(private val output: AppRunOutput) : ShellLauncher {
  override fun start(launch: TerminalLaunch): ShellStart = ShellStart.Started(AppRunLogProcess(output))
}

private class AppRunLogProcess(output: AppRunOutput) : ShellProcess {
  override val connector = AppRunLogConnector()
  private val destroyed = AtomicBoolean(false)
  private val exitListeners = CopyOnWriteArrayList<() -> Unit>()
  private val attachment = output.attach { event ->
    when (event) {
      AppRunOutputEvent.Reset -> connector.enqueue("\u001b[H\u001b[2J\u001b[3J")
      is AppRunOutputEvent.Line -> connector.enqueue(event.text + "\r\n")
    }
  }

  override fun destroy() {
    if (!destroyed.compareAndSet(false, true)) return
    attachment.close()
    connector.end()
    exitListeners.forEach { it() }
    exitListeners.clear()
  }

  override fun onExit(listener: () -> Unit) {
    if (destroyed.get()) listener() else {
      exitListeners += listener
      if (destroyed.get() && exitListeners.remove(listener)) listener()
    }
  }
}

private class AppRunLogConnector : TtyConnector {
  private val chunks = LinkedBlockingQueue<CharArray>()
  private val ended = CharArray(0)
  private val done = CountDownLatch(1)
  private val currentLock = Any()
  private var current = CharArray(0)
  private var offset = 0
  @Volatile private var connected = true

  fun enqueue(text: String) {
    if (connected) chunks.offer(text.toCharArray())
  }

  fun end() {
    if (!connected) return
    connected = false
    chunks.offer(ended)
    done.countDown()
  }

  override fun read(buffer: CharArray, offset: Int, length: Int): Int {
    if (length == 0) return 0
    while (true) {
      synchronized(currentLock) {
        if (this.offset < current.size) {
          val count = minOf(length, current.size - this.offset)
          current.copyInto(buffer, offset, this.offset, this.offset + count)
          this.offset += count
          return count
        }
      }
      val next = try {
        chunks.take()
      } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        throw IOException("Interrupted while reading the Run app log", interrupted)
      }
      if (next === ended || next.isEmpty()) return -1
      synchronized(currentLock) {
        current = next
        this.offset = 0
      }
    }
  }

  override fun write(bytes: ByteArray) = Unit
  override fun write(string: String) = Unit
  override fun isConnected(): Boolean = connected
  override fun waitFor(): Int {
    done.await()
    return 0
  }
  override fun ready(): Boolean = synchronized(currentLock) { offset < current.size } || chunks.isNotEmpty() || !connected
  override fun getName(): String = "Run app log"
  override fun close() = end()
  override fun resize(termSize: TermSize) = Unit
  override fun init(questioner: Questioner): Boolean = true
}
