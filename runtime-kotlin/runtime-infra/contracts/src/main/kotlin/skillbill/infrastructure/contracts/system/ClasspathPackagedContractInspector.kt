package skillbill.infrastructure.contracts.system

import com.fasterxml.jackson.databind.JsonNode
import com.networknt.schema.JsonSchemaException
import me.tatarka.inject.annotations.Inject
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_PHASE_OUTPUT_SCHEMA_ID
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_PREVIOUS_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_SUPPORTED_PHASE_OUTPUT_MIGRATIONS
import skillbill.contracts.workflow.goal.GOAL_PLANNING_PREPARATION_CONTRACT_VERSION
import skillbill.contracts.workflow.goal.GOAL_PLANNING_PREPARATION_MIGRATION_PATHS
import skillbill.contracts.workflow.goal.GOAL_PLANNING_PREPARATION_SCHEMA_ID
import skillbill.contracts.workflow.goal.GoalPlanningPreparationPayloadKeys
import skillbill.error.core.RuntimeFailureCode
import skillbill.error.core.SkillBillRuntimeException
import skillbill.infrastructure.contracts.ClasspathContractSchemaLoader
import skillbill.infrastructure.contracts.locator.FeatureTaskRuntimePhaseOutputSchemaPaths
import skillbill.infrastructure.contracts.locator.GoalPlanningPreparationSchemaPaths
import skillbill.infrastructure.contracts.workflow.issue.inlineIssueKeySchemaRefs
import skillbill.ports.system.PackagedContractInspector
import java.io.IOException

@Inject
class ClasspathPackagedContractInspector : PackagedContractInspector {
  override fun inspect() {
    inspect(javaClass.classLoader)
  }

  internal fun inspect(loader: ClassLoader) {
    phaseOutput(
      loader,
      FeatureTaskRuntimePhaseOutputSchemaPaths.CURRENT_CLASSPATH_RESOURCE,
      FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
    )
    phaseOutput(
      loader,
      FeatureTaskRuntimePhaseOutputSchemaPaths.HISTORICAL_0_6_CLASSPATH_RESOURCE,
      FEATURE_TASK_RUNTIME_PREVIOUS_CONTRACT_VERSION,
    )
    preparation(loader, GoalPlanningPreparationSchemaPaths.CLASSPATH_RESOURCE, FEATURE_TASK_RUNTIME_CONTRACT_VERSION)
    preparation(
      loader,
      GoalPlanningPreparationSchemaPaths.HISTORICAL_0_2_PHASE_OUTPUT_0_6_CLASSPATH_RESOURCE,
      FEATURE_TASK_RUNTIME_PREVIOUS_CONTRACT_VERSION,
    )
    if (FEATURE_TASK_RUNTIME_SUPPORTED_PHASE_OUTPUT_MIGRATIONS !=
      mapOf(FEATURE_TASK_RUNTIME_PREVIOUS_CONTRACT_VERSION to FEATURE_TASK_RUNTIME_CONTRACT_VERSION)
    ) {
      fail("producer phase-output migration paths")
    }
    val path = GOAL_PLANNING_PREPARATION_MIGRATION_PATHS.singleOrNull() ?: fail("producer migration paths")
    val planningVersions =
      listOf(
        path.sourcePreparationVersion,
        path.targetPreparationVersion,
        path.sourcePlanningVersion,
        path.targetPlanningVersion,
      )
    if (planningVersions.any { it != GOAL_PLANNING_PREPARATION_CONTRACT_VERSION }) fail("producer planning paths")
    if (path.sourcePhaseOutputVersion != FEATURE_TASK_RUNTIME_PREVIOUS_CONTRACT_VERSION ||
      path.targetPhaseOutputVersion != FEATURE_TASK_RUNTIME_CONTRACT_VERSION
    ) {
      fail("producer phase-output paths")
    }
  }

  private fun phaseOutput(
    loader: ClassLoader,
    resource: String,
    version: String,
  ) {
    val schema = load(loader, resource)
    pin(schema, PackageSchemaKeys.ID, FEATURE_TASK_RUNTIME_PHASE_OUTPUT_SCHEMA_ID, resource)
    pin(
      schema.path(PackageSchemaKeys.PROPERTIES).path(SharedPayloadKeys.CONTRACT_VERSION),
      PackageSchemaKeys.CONST,
      version,
      resource,
    )
    compile(schema, resource)
  }

  private fun preparation(
    loader: ClassLoader,
    resource: String,
    phaseVersion: String,
  ) {
    val schema = load(loader, resource)
    pin(schema, PackageSchemaKeys.ID, GOAL_PLANNING_PREPARATION_SCHEMA_ID, resource)
    pin(
      schema.path(PackageSchemaKeys.PROPERTIES).path(SharedPayloadKeys.CONTRACT_VERSION),
      PackageSchemaKeys.CONST,
      GOAL_PLANNING_PREPARATION_CONTRACT_VERSION,
      resource,
    )
    val definitions = schema.path(PackageSchemaKeys.DEFINITIONS)
    listOf(PackageSchemaKeys.SHARED_PREPLAN, PackageSchemaKeys.SUBTASK_PLAN).forEach { variant ->
      val properties = definitions.path(variant).path(PackageSchemaKeys.PROPERTIES)
      pin(
        properties.path(SharedPayloadKeys.CONTRACT_VERSION),
        PackageSchemaKeys.CONST,
        GOAL_PLANNING_PREPARATION_CONTRACT_VERSION,
        resource,
      )
      pin(
        properties.path(GoalPlanningPreparationPayloadKeys.PROVENANCE),
        PackageSchemaKeys.REF,
        "#/${PackageSchemaKeys.DEFINITIONS}/${GoalPlanningPreparationPayloadKeys.PROVENANCE}",
        resource,
      )
    }
    val provenance = definitions.path(GoalPlanningPreparationPayloadKeys.PROVENANCE).path(PackageSchemaKeys.PROPERTIES)
    mapOf(
      GoalPlanningPreparationPayloadKeys.PLANNING_CONTRACT_ID to GOAL_PLANNING_PREPARATION_SCHEMA_ID,
      GoalPlanningPreparationPayloadKeys.PLANNING_CONTRACT_VERSION to GOAL_PLANNING_PREPARATION_CONTRACT_VERSION,
      GoalPlanningPreparationPayloadKeys.PHASE_OUTPUT_CONTRACT_ID to FEATURE_TASK_RUNTIME_PHASE_OUTPUT_SCHEMA_ID,
      GoalPlanningPreparationPayloadKeys.PHASE_OUTPUT_CONTRACT_VERSION to phaseVersion,
    ).forEach { (key, expected) -> pin(provenance.path(key), PackageSchemaKeys.CONST, expected, resource) }
    schema.inlineIssueKeySchemaRefs()
    compile(schema, resource)
  }

  private fun load(
    loader: ClassLoader,
    resource: String,
  ): JsonNode =
    try {
      val stream = loader.getResourceAsStream(resource.removePrefix("/")) ?: fail(resource)
      stream.use { ClasspathContractSchemaLoader.sharedYamlMapper().readTree(it) } ?: fail(resource)
    } catch (error: IOException) {
      fail(resource, error)
    }

  private fun compile(
    schema: JsonNode,
    resource: String,
  ) {
    try {
      ClasspathContractSchemaLoader.compileUncachedYamlNode(schema)
    } catch (error: JsonSchemaException) {
      fail(resource, error)
    }
  }

  private fun pin(
    node: JsonNode,
    key: String,
    expected: String,
    resource: String,
  ) {
    if (!node.path(key).isTextual || node.path(key).asText() != expected) fail(resource)
  }

  private fun fail(
    resource: String,
    cause: Throwable? = null,
  ): Nothing =
    throw SkillBillRuntimeException(
      PackagedContractFailureCode.INCOMPATIBLE_PACKAGE,
      "Packaged contract parity failed for '$resource'. Install CLI and MCP images from the same complete build. " +
        "Durable workflow state cannot repair a mixed package.",
      cause,
    )
}

internal enum class PackagedContractFailureCode : RuntimeFailureCode {
  INCOMPATIBLE_PACKAGE,
}

private object PackageSchemaKeys {
  const val ID = "\$id"
  const val DEFINITIONS = "\$defs"
  const val REF = "\$ref"
  const val PROPERTIES = "properties"
  const val CONST = "const"
  const val SHARED_PREPLAN = "sharedPreplan"
  const val SUBTASK_PLAN = "subtaskPlan"
}
