package dev.skillbill.desktop.feature.changes

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.skillbill.desktop.core.domain.model.CommentSide
import dev.skillbill.desktop.core.domain.model.DiffLine
import dev.skillbill.desktop.core.domain.model.DiffLineKind

internal data class CommentAnchor(val side: CommentSide, val line: Int)

internal fun commentAnchorFor(line: DiffLine): CommentAnchor? = when (line.kind) {
  DiffLineKind.Removed -> line.oldNumber?.let { CommentAnchor(CommentSide.Old, it) }
  DiffLineKind.Added, DiffLineKind.Context -> line.newNumber?.let { CommentAnchor(CommentSide.New, it) }
  DiffLineKind.NoNewlineMarker -> null
}

internal data class HoveredLine(val path: String, val anchor: CommentAnchor)

@Stable
internal class DiffAuthoringInput {
  var hovered: HoveredLine? by mutableStateOf(null)
  var commentTextFocused: Boolean by mutableStateOf(false)
}
