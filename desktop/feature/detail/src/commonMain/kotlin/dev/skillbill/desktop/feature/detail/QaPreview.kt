package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillSpace
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.detail.PaneSection
import dev.skillbill.desktop.core.domain.model.QaPreviewState
import dev.skillbill.desktop.feature.detail.generated.resources.Res
import dev.skillbill.desktop.feature.detail.generated.resources.qa_preview_description
import dev.skillbill.desktop.feature.detail.generated.resources.qa_preview_display_blank
import dev.skillbill.desktop.feature.detail.generated.resources.qa_preview_ended
import dev.skillbill.desktop.feature.detail.generated.resources.qa_preview_ended_detail
import dev.skillbill.desktop.feature.detail.generated.resources.qa_preview_frame_incomplete
import dev.skillbill.desktop.feature.detail.generated.resources.qa_preview_format_unsupported
import dev.skillbill.desktop.feature.detail.generated.resources.qa_preview_limits_exceeded
import dev.skillbill.desktop.feature.detail.generated.resources.qa_preview_live
import dev.skillbill.desktop.feature.detail.generated.resources.qa_preview_read_failed
import dev.skillbill.desktop.feature.detail.generated.resources.qa_preview_run
import dev.skillbill.desktop.feature.detail.generated.resources.qa_preview_source_invalid
import dev.skillbill.desktop.feature.detail.generated.resources.qa_preview_source_unavailable
import dev.skillbill.desktop.feature.detail.generated.resources.qa_preview_title
import dev.skillbill.desktop.feature.detail.generated.resources.qa_preview_waiting
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private val PreviewMaxHeight = 360.dp

@Composable
internal fun QaPreview(invocationId: String, state: QaPreviewState, modifier: Modifier = Modifier) {
  PaneSection(modifier = modifier) {
    BasicText(
      stringResource(Res.string.qa_preview_title),
      modifier = Modifier.semantics { heading() },
      style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fgFaint),
    )
    Column(Modifier.fillMaxWidth().padding(top = SkillBillSpace.s2), verticalArrangement = Arrangement.spacedBy(SkillBillSpace.s2)) {
      BasicText(stringResource(Res.string.qa_preview_run, invocationId), style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgMuted))
      val status = when (state) {
        is QaPreviewState.Waiting -> Res.string.qa_preview_waiting
        is QaPreviewState.Live -> Res.string.qa_preview_live
        QaPreviewState.Ended -> Res.string.qa_preview_ended
      }
      Column(
        modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(SkillBillSpace.s2),
      ) {
        BasicText(stringResource(status), style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fg))
        when (state) {
          is QaPreviewState.Waiting -> BasicText(stringResource(waitingText(state.reason)), style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgMuted))
          QaPreviewState.Ended -> BasicText(stringResource(Res.string.qa_preview_ended_detail), style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgMuted))
          is QaPreviewState.Live -> Unit
        }
      }
      if (state is QaPreviewState.Live) {
        val bitmap = remember(state.frame) { state.frame.toImageBitmap() }
        Image(
          bitmap = bitmap,
          contentDescription = stringResource(Res.string.qa_preview_description, invocationId),
          modifier = Modifier.fillMaxWidth().heightIn(max = PreviewMaxHeight).clip(RoundedCornerShape(SkillBillRadius.sm)),
          contentScale = ContentScale.Fit,
        )
      }
    }
  }
}

private fun waitingText(reason: QaPreviewState.Reason): StringResource = when (reason) {
  QaPreviewState.Reason.SourceUnavailable -> Res.string.qa_preview_source_unavailable
  QaPreviewState.Reason.InvalidSource -> Res.string.qa_preview_source_invalid
  QaPreviewState.Reason.IncompleteFrame -> Res.string.qa_preview_frame_incomplete
  QaPreviewState.Reason.UnsupportedFormat -> Res.string.qa_preview_format_unsupported
  QaPreviewState.Reason.LimitsExceeded -> Res.string.qa_preview_limits_exceeded
  QaPreviewState.Reason.ReadFailed -> Res.string.qa_preview_read_failed
  QaPreviewState.Reason.BlankDisplay -> Res.string.qa_preview_display_blank
}
