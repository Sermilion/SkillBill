package dev.skillbill.intellij.domain

data class StatusExecutionMetadata(
    val executionScope: String? = null,
    val executionId: String? = null,
    val statusStoreId: String? = null,
    val branchCorrelation: String? = null,
    val runSequence: String? = null,
    val statusRevision: String? = null,
    val invocationId: String? = null,
    val phaseId: String? = null,
    val currentActivity: String? = null,
)
