package dev.skillbill.desktop.core.ui.apprun

import java.util.concurrent.CopyOnWriteArrayList

sealed interface AppRunOutputEvent {
  data object Reset : AppRunOutputEvent
  data class Line(val text: String) : AppRunOutputEvent
}

class AppRunOutput {
  private val lines = mutableListOf<String>()
  private val listeners = CopyOnWriteArrayList<(AppRunOutputEvent) -> Unit>()

  @Synchronized
  fun reset() {
    lines.clear()
    listeners.forEach { it(AppRunOutputEvent.Reset) }
  }

  @Synchronized
  fun append(line: String) {
    lines += line
    val event = AppRunOutputEvent.Line(line)
    listeners.forEach { it(event) }
  }

  @Synchronized
  fun attach(listener: (AppRunOutputEvent) -> Unit): AutoCloseable {
    listener(AppRunOutputEvent.Reset)
    lines.forEach { listener(AppRunOutputEvent.Line(it)) }
    listeners += listener
    return AutoCloseable { listeners -= listener }
  }
}
