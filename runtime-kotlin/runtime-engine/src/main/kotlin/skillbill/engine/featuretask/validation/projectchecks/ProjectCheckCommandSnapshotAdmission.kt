package skillbill.engine.featuretask.validation.projectchecks

import skillbill.contracts.config.projectchecks.ProjectCheckCategory
import skillbill.contracts.config.projectchecks.ProjectCheckCommandParserFindings
import skillbill.contracts.config.projectchecks.ProjectCheckCommandParserMetadata
import skillbill.contracts.config.projectchecks.ProjectCheckCommandSnapshot
import skillbill.engine.featuretask.validation.model.ValidationGateResolution
import skillbill.error.featuretask.IncompatibleFeatureTaskRuntimeExecutionPlanError
import skillbill.scaffold.model.ValidationGateCompilerDiagnosticsFormat
import skillbill.scaffold.model.ValidationGateCompilerDiagnosticsLocator
import skillbill.scaffold.model.ValidationGateDeclaration
import skillbill.scaffold.model.ValidationGateExecutedWorkFormat
import skillbill.scaffold.model.ValidationGateExecutedWorkSignal
import skillbill.scaffold.model.ValidationGateFindingsFormat
import skillbill.scaffold.model.ValidationGateFindingsLocator
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection

internal object ProjectCheckCommandSnapshotAdmission {
  fun freeze(
    snapshot: ProjectCheckConfigAccessSnapshot,
    categories: Set<ProjectCheckCategory>,
    packSlug: String?,
    declaration: ValidationGateDeclaration?,
    gradleWrapper: String?,
  ): ProjectCheckCommandSnapshot {
    val parserMetadata =
      if (packSlug != null && declaration != null) {
        parserMetadataFromDeclaration(packSlug, declaration)
      } else {
        null
      }
    return ProjectCheckCommandSnapshot(
      projectId = snapshot.location.projectId,
      buildCommands = if (ProjectCheckCategory.BUILD in categories) snapshot.config.buildCommands else null,
      validationCommands =
        if (ProjectCheckCategory.VALIDATION in categories) snapshot.config.validationCommands else null,
      parserMetadata = parserMetadata,
      gradleWrapper = gradleWrapper,
    ).frozen()
  }

  fun categoriesForQualityGate(qualityGate: FeatureTaskRuntimeQualityGateSelection?): Set<ProjectCheckCategory> =
    when (qualityGate) {
      FeatureTaskRuntimeQualityGateSelection.BUILD -> setOf(ProjectCheckCategory.BUILD)
      FeatureTaskRuntimeQualityGateSelection.VALIDATE -> setOf(ProjectCheckCategory.VALIDATION)
      null -> emptySet()
    }

  fun packContext(resolution: ValidationGateResolution): Pair<String?, ValidationGateDeclaration?> =
    when (resolution) {
      is ValidationGateResolution.Declared -> resolution.packSlug to resolution.declaration
      is ValidationGateResolution.Absent -> resolution.routedPackSlug to null
      is ValidationGateResolution.Incompatible ->
        throw IncompatibleFeatureTaskRuntimeExecutionPlanError(resolution.reason)
    }
}

internal fun parserMetadataFromDeclaration(
  packSlug: String,
  declaration: ValidationGateDeclaration,
): ProjectCheckCommandParserMetadata =
  ProjectCheckCommandParserMetadata(
    packSlug,
    ProjectCheckCommandParserFindings(
      declaration.findings.format.wireValue,
      declaration.findings.artifactGlobs,
      declaration.findings.compilerDiagnostics.format.wireValue,
      declaration.findings.executedWork?.format?.wireValue,
    ),
    declaration.suppressionMarkers,
  )

internal fun ProjectCheckCommandParserMetadata.findingsLocator(): ValidationGateFindingsLocator {
  val format =
    ValidationGateFindingsFormat.fromWire(findings.format)
      ?: error("admitted parser findings format is not a known pack format")
  val compilerFormat =
    ValidationGateCompilerDiagnosticsFormat.fromWire(findings.compilerDiagnosticsFormat)
      ?: error("admitted compiler diagnostics format is not a known pack format")
  val executedWork =
    findings.executedWorkFormat?.let { wire ->
      val executedFormat =
        ValidationGateExecutedWorkFormat.fromWire(wire)
          ?: error("admitted executed-work format is not a known pack format")
      ValidationGateExecutedWorkSignal(executedFormat)
    }
  return ValidationGateFindingsLocator(
    format,
    findings.artifactGlobs,
    ValidationGateCompilerDiagnosticsLocator(compilerFormat),
    executedWork,
  )
}
