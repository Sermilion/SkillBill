package dev.skillbill.desktop.app

import androidx.compose.material3.Text
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

/**
 * The one app window, showing [text] until the app shell replaces it. It runs in whichever class loader calls it, so it touches
 * nothing but Compose: the refusal path runs without the runtime's libraries on the classpath.
 */
fun showSkillBillWindow(text: String) = application {
  Window(onCloseRequest = ::exitApplication, title = "Skill Bill") {
    Text(text)
  }
}
