package dev.skillbill.desktop.core.domain.qa

import dev.skillbill.desktop.core.domain.model.WorkItem

fun leaseHolderIssueKeys(items: List<WorkItem>): Map<String, String> = items.mapNotNull { item ->
  val invocation = item.control.invocationId?.takeIf(String::isNotBlank) ?: return@mapNotNull null
  val issue = item.issueKey?.takeIf(String::isNotBlank) ?: return@mapNotNull null
  invocation to issue
}.toMap()
