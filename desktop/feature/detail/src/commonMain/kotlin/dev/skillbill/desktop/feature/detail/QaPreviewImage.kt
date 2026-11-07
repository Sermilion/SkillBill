package dev.skillbill.desktop.feature.detail

import androidx.compose.ui.graphics.ImageBitmap
import dev.skillbill.desktop.core.domain.model.QaPreviewFrame

internal expect fun QaPreviewFrame.toImageBitmap(): ImageBitmap
