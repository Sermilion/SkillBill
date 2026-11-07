package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillSpace
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbButtonVariant
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.designsystem.detail.sbBoxSegment
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.WorkItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal fun DetailSections.revealPlanBriefing(planState: PlanScreenState, phaseId: String) {
  planState.briefingRevealJob?.cancel()
  planState.briefingReveal = phaseId
  if (collapse.isCollapsed("plan-briefing", true)) collapse.toggle("plan-briefing", true)
  val rawKey = "plan-briefing-raw-$phaseId"
  planState.openFoldKeys.add(rawKey)
  planState.briefingRevealJob = planState.revealScope.launch {
    try {
      repeat(10) {
        withFrameNanos { }
        if (collapse.isCollapsed("plan-briefing", true)) return@launch
        val index = indexOf(rawKey)
        if (index != null) {
          scrollState.animateScrollToItem(index)
          return@launch
        }
      }
    } finally {
      if (planState.briefingRevealJob == coroutineContext[Job]) {
        planState.briefingReveal = null
        planState.briefingRevealJob = null
      }
    }
  }
}

internal fun DetailSections.planBriefingSection(
  item: WorkItem,
  detail: Detail,
  planState: PlanScreenState,
  onAction: (DetailAction) -> Unit,
) {
  val plan = detail.standalonePlan ?: return
  val reached = planStageViews(item, detail).filter { it.state != PlanStageState.NotStarted }.map { it.phaseId }.toSet()
  val briefing = planBriefing(plan.stages, reached)
  val key = "plan-briefing"
  val open = !collapse.isCollapsed(key, true)
  item(key = "$key-gap") { Spacer(Modifier.height(SkillBillSpace.s5)) }
  item(key = key) {
    Column {
      PlanSectionHeader("Briefing", "${briefing.stages.count { it.text != null }} saved", open, { this@planBriefingSection.collapse.toggle(key, true) })
      if (open) Spacer(Modifier.height(planSectionBodyGap))
    }
  }
  if (open) {
    val segmentCount = briefing.stages.size + 1 + if (briefing.setup != null) 1 else 0
    var segment = 0
    if (briefing.setup != null) {
      val index = segment++
      item(key = "plan-briefing-overview") {
        Column(Modifier.sbBoxSegment(index, segmentCount).fillMaxWidth()) {
          PlanBundleDivider("RUN SETUP · SAME IN EVERY STAGE")
          SetupRows(briefing.setup, onAction)
          PlanBundleDivider("HANDED TO PLAN · PREPLAN DIGEST")
          DigestRows(briefing.digest, planState)
        }
      }
    }
    val dividerIndex = segment++
    item(key = "plan-briefing-raw-divider") {
      PlanBundleDivider("RAW BRIEFINGS", Modifier.sbBoxSegment(dividerIndex, segmentCount))
    }
    briefing.stages.forEach { stage ->
      val index = segment++
      item(key = "plan-briefing-raw-${stage.phaseId}") {
        RawBriefingRow(stage, planState, onAction, Modifier.sbBoxSegment(index, segmentCount))
      }
    }
  } else item(key = "plan-briefing-teaser") {
    PlanBriefingTeaser(briefing, reached) { this@planBriefingSection.collapse.toggle(key, true) }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlanBriefingTeaser(briefing: PlanBriefing, reached: Set<String>, onExpand: () -> Unit) {
  val colors = SkillBillTheme.colors
  val shape = RoundedCornerShape(SkillBillRadius.md)
  val maxBytes = briefing.stages.maxOfOrNull { it.bytes }?.coerceAtLeast(1) ?: 1
  Box(
    Modifier.fillMaxWidth().heightIn(max = 236.dp).clip(shape)
      .background(Brush.verticalGradient(0f to Color(130, 140, 255).copy(alpha = .05f), .7f to Color.Transparent))
      .border(1.dp, colors.border, shape).clickable(role = Role.Button, onClick = onExpand),
  ) {
    Column {
      Column(
        Modifier.weight(1f, fill = false).fillMaxWidth().clipToBounds()
          .padding(horizontal = SkillBillSpace.s3).padding(top = SkillBillSpace.s3),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
          briefing.stages.forEach { stage ->
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(stage.phaseId, Modifier.weight(1f), style = SkillBillTheme.type.tXs.copy(fontFamily = FontFamily.Monospace, color = colors.fgMuted))
                BasicText(stage.sizeLabel ?: if (stage.layers == "not started") "—" else "not saved", style = SkillBillTheme.type.t2xs.copy(color = colors.fgFaint))
              }
              BriefingBar(stage, maxBytes)
            }
          }
        }
        if (briefing.setup != null) {
          Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BriefingLegend(colors.fgFaint.copy(alpha = .55f), "run setup")
            if ("plan" in reached) BriefingLegend(colors.stDone, "preplan digest")
          }
          if ("plan" in reached) {
            val facts = listOfNotNull(
              fact(briefing.digest, setOf("code", "verified", "boundar"))?.let { Triple(LucideIcons.file, "Verified by reading:", it) },
              fact(briefing.digest, setOf("risk"))?.let { Triple(LucideIcons.warn, "Risk:", it) },
              fact(briefing.digest, setOf("not read", "unknown"))?.let { Triple(LucideIcons.search, "Not read:", it) },
            )
            facts.forEach { (icon, label, text) -> FactLine(icon, label, text) }
          } else FactLine(LucideIcons.clock, "", "Preplan is reading the repo. Its digest lands here and becomes most of what the plan stage reads.")
          val setup = briefing.setup
          FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            PlanPill(setup.featureSize, true)
            PlanPill("${setup.criteria.size} criteria", true)
            setup.ceremony.forEach { PlanPill(it) }
            PlanPill(if (setup.mandates.isEmpty()) "no mandates" else "${setup.mandates.size} mandates")
          }
        }
      }
      Spacer(Modifier.height(34.dp))
    }
    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(52.dp).background(Brush.verticalGradient(listOf(Color.Transparent, colors.bgPanel))), verticalArrangement = Arrangement.Bottom) {
      Row(Modifier.fillMaxWidth().padding(bottom = 9.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        BasicText("Read what each stage was told", style = SkillBillTheme.type.tXs.copy(color = colors.accentText, fontWeight = FontWeight.Medium))
        SbIcon(LucideIcons.chevronDown, 12.dp, modifier = Modifier.padding(start = 5.dp))
      }
    }
  }
}

@Composable
private fun BriefingBar(stage: PlanBriefingStage, maxBytes: Int) {
  val colors = SkillBillTheme.colors
  Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(colors.bgInset)) {
    Canvas(Modifier.fillMaxWidth().height(6.dp)) {
      if (stage.text == null) {
        drawLine(colors.border, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
      } else {
        val setupWidth = size.width * (stage.setupBytes.toFloat() / maxBytes).coerceIn(0f, 1f)
        val digestWidth = size.width * (stage.digestBytes.toFloat() / maxBytes).coerceIn(0f, 1f)
        if (setupWidth > 0f) drawRoundRect(colors.fgFaint.copy(alpha = .55f), size = Size(setupWidth, size.height))
        if (digestWidth > 0f) drawRoundRect(colors.stDone, topLeft = Offset(setupWidth + if (setupWidth > 0f) 2.dp.toPx() else 0f, 0f), size = Size(digestWidth, size.height))
      }
    }
  }
}

@Composable
private fun BriefingLegend(color: Color, label: String) {
  Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(7.dp).background(color, RoundedCornerShape(2.dp)))
    BasicText(label, style = SkillBillTheme.type.t2xs.copy(color = SkillBillTheme.colors.fgFaint))
  }
}

@Composable
private fun FactLine(icon: ImageVector, label: String, text: String) {
  val colors = SkillBillTheme.colors
  Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
    CompositionLocalProvider(LocalContentColor provides colors.fgFaint) { SbIcon(icon, 12.dp) }
    BasicText(buildAnnotatedString {
      if (label.isNotEmpty()) withStyle(SpanStyle(color = colors.fgMuted, fontWeight = FontWeight.Medium)) { append("$label ") }
      append(text)
    }, style = SkillBillTheme.type.tXs.copy(color = colors.fg2), maxLines = 2, overflow = TextOverflow.Ellipsis)
  }
}

private fun fact(digest: List<PlanDigestSection>?, words: Set<String>): String? = digest.orEmpty()
  .firstOrNull { section -> words.any { section.title.contains(it, ignoreCase = true) } }?.items?.firstOrNull()

@Composable
private fun PlanPill(text: String, strong: Boolean = false) {
  val colors = SkillBillTheme.colors
  Box(Modifier.background(colors.bgActive, RoundedCornerShape(SkillBillRadius.xs)).padding(horizontal = 7.dp, vertical = 2.dp)) {
    BasicText(text, style = SkillBillTheme.type.t2xs.copy(color = if (strong) colors.fg2 else colors.fgMuted, fontWeight = if (strong) FontWeight.Medium else FontWeight.Normal))
  }
}

@Composable
private fun SetupRows(setup: PlanRunSetup, onAction: (DetailAction) -> Unit) {
  Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
    KeyValue("Spec") {
      val path = setup.specPath
      if (path == null) BasicText("—", style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fgFaint))
      else BasicText(path.substringAfterLast('/').substringAfterLast('\\'), Modifier.clickable { onAction(DetailAction.OpenSpecFile(path)) }, style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.accentText))
    }
    KeyValue("Size") { BasicText(setup.featureSize, style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fg2)) }
    KeyValue("Ceremony") { BasicText(setup.ceremony.joinToString(" · "), style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fg2)) }
    KeyValue("Mandates") { BasicText(if (setup.mandates.isEmpty()) "none" else setup.mandates.joinToString(", "), style = SkillBillTheme.type.tXs.copy(color = if (setup.mandates.isEmpty()) SkillBillTheme.colors.fgFaint else SkillBillTheme.colors.fg2)) }
    KeyValue("Criteria") {
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { setup.criteria.forEach { criterion ->
        BasicText(buildAnnotatedString { withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = SkillBillTheme.colors.fgMuted)) { append("${criterion.id} ") }; append(criterion.text) }, style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fg2))
      } }
    }
  }
}

@Composable
private fun KeyValue(label: String, value: @Composable () -> Unit) {
  Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
    BasicText(label, Modifier.width(96.dp), style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fgFaint))
    Box(Modifier.weight(1f)) { value() }
  }
}

@Composable
private fun DigestRows(digest: List<PlanDigestSection>?, state: PlanScreenState) {
  val colors = SkillBillTheme.colors
  val codeStyle = SpanStyle(fontFamily = FontFamily.Monospace, color = colors.fg2, background = colors.bgInset)
  if (digest == null) {
    BasicText("The preplan digest appears here when preplan finishes. It is what the plan stage reads first.", Modifier.padding(12.dp), style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fgFaint))
    return
  }
  digest.forEachIndexed { index, section ->
    val key = "plan-briefing-digest-$index"
    val open = key in state.openFoldKeys
    Column {
      Row(Modifier.fillMaxWidth().semantics { stateDescription = if (open) "Expanded" else "Collapsed" }.clickable(role = Role.Button) { state.toggleFold(key) }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        SbIcon(LucideIcons.chevronDown, 12.dp, modifier = Modifier.rotate(if (open) 0f else -90f))
        BasicText(section.title, Modifier.weight(1f), style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fg2), maxLines = 1, overflow = TextOverflow.Ellipsis)
        BasicText(section.items.firstOrNull().orEmpty(), Modifier.weight(1f), style = SkillBillTheme.type.t2xs.copy(color = SkillBillTheme.colors.fgFaint), maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
      if (open) Column(Modifier.padding(start = 31.dp, end = 12.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        section.items.forEach { item -> BasicText(digestItem(item, codeStyle), style = SkillBillTheme.type.tXs.copy(color = colors.fg2)) }
      }
    }
  }
}

private fun digestItem(text: String, codeStyle: SpanStyle) = buildAnnotatedString {
  append("• ")
  val spans = Regex("`([^`]+)`").findAll(text)
  var from = 0
  spans.forEach { match ->
    append(text.substring(from, match.range.first))
    withStyle(codeStyle) { append(match.groupValues[1]) }
    from = match.range.last + 1
  }
  append(text.substring(from))
}

@Composable
private fun RawBriefingRow(stage: PlanBriefingStage, state: PlanScreenState, onAction: (DetailAction) -> Unit, modifier: Modifier = Modifier) {
  val key = "plan-briefing-raw-${stage.phaseId}"
  val open = key in state.openFoldKeys
  val text = stage.text
  val colors = SkillBillTheme.colors
  Column(modifier) {
    Row(Modifier.fillMaxWidth().then(if (text == null) Modifier else Modifier.semantics { stateDescription = if (open) "Expanded" else "Collapsed" }.clickable(role = Role.Button) { state.toggleFold(key) }).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
      SbIcon(LucideIcons.chevronDown, 12.dp, modifier = Modifier.rotate(if (open) 0f else -90f))
      BasicText(stage.phaseId, style = SkillBillTheme.type.tXs.copy(fontFamily = FontFamily.Monospace, color = colors.fg2))
      BasicText(stage.layers, Modifier.weight(1f), style = SkillBillTheme.type.t2xs.copy(color = colors.fgFaint), maxLines = 1, overflow = TextOverflow.Ellipsis)
      BasicText(stage.sizeLabel ?: "—", style = SkillBillTheme.type.t2xs.copy(color = colors.fgFaint))
    }
    if (open && text != null) Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) {
      val shape = RoundedCornerShape(SkillBillRadius.sm)
      val verticalScroll = rememberScrollState()
      val horizontalScroll = rememberScrollState()
      Column(Modifier.fillMaxWidth().clip(shape).background(colors.bgInset).border(1.dp, colors.borderSubtle, shape)) {
        Box(Modifier.fillMaxWidth().height(220.dp).clipToBounds()) {
          BasicText(
            text,
            Modifier.horizontalScroll(horizontalScroll).verticalScroll(verticalScroll).padding(8.dp),
            style = SkillBillTheme.type.t2xs.copy(fontFamily = FontFamily.Monospace, color = colors.fg2),
            softWrap = false,
          )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.borderSubtle))
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
          BasicText("Exactly what the agent received", Modifier.weight(1f), style = SkillBillTheme.type.tXs.copy(color = colors.fgFaint))
          SbButton("Copy", { onAction(DetailAction.Copy(text)) }, variant = SbButtonVariant.Ghost, size = SbControlSize.Small, icon = LucideIcons.copy)
        }
      }
    }
  }
}
