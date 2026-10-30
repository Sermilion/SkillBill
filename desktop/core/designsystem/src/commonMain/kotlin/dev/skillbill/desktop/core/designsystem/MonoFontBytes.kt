package dev.skillbill.desktop.core.designsystem

import dev.skillbill.desktop.core.designsystem.generated.resources.Res

/** JetBrains Mono's file bytes, for Swing hosts such as the terminal that cannot take a Compose font. */
suspend fun monoFontBytes(): ByteArray = Res.readBytes("font/jetbrains_mono.ttf")
