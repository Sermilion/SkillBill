package dev.skillbill.desktop.core.data.control

import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.domain.repository.DesktopClipboard
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.SwingUtilities

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class AwtDesktopClipboard : DesktopClipboard {
  override fun copy(text: String) {
    SwingUtilities.invokeLater { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
  }
}
