package dev.skillbill.tray.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.skillbill.tray.core.designsystem.ink
import dev.skillbill.tray.core.designsystem.muted
import dev.skillbill.tray.core.domain.model.IncidentDetailUnavailable
import dev.skillbill.tray.core.domain.model.RuntimeBuildProblem

@Composable
fun IncidentDetailUnavailablePanel(problem: RuntimeBuildProblem?, onRetry: () -> Unit, modifier: Modifier = Modifier) {
  Column(
    modifier.fillMaxSize().padding(32.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
  ) {
    Text(IncidentDetailUnavailable.MESSAGE, color = ink, style = MaterialTheme.typography.titleMedium)
    problem?.let {
      Text("Install a matched runtime and desktop pair with ${it.reinstallCommand}, then retry.", color = muted)
    }
    Row { Button(onClick = onRetry) { Text("Retry") } }
  }
}
