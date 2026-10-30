package dev.skillbill.desktop.feature.detail

import androidx.compose.runtime.mutableStateOf

internal class DetailSectionExpansion {
  val subtasks = mutableStateOf(false)
  val dependencies = mutableStateOf(false)
  val activityCategories = mutableStateOf(emptySet<String>())
}
