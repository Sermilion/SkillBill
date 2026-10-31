package dev.skillbill.desktop.core.designsystem.icon

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.generated.resources.Res
import dev.skillbill.desktop.core.designsystem.generated.resources.skill_bill
import org.jetbrains.compose.resources.painterResource

@Composable
fun skillBillIcon(): Painter = painterResource(Res.drawable.skill_bill)

@Composable
fun BrandMark(size: Dp = 20.dp) {
  require(size.value in 16f..32f)
  Image(skillBillIcon(), contentDescription = null, modifier = Modifier.size(size))
}
