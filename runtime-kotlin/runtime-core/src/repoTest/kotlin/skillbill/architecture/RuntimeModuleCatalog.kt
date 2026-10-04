package skillbill.architecture

object RuntimeModuleCatalog {
  data class ModuleEdgeExpectation(
    val api: Set<String>,
    val implementation: Set<String>,
  )

  val declaredGradleModules: List<String> =
    listOf(
      "runtime-application",
      "runtime-contracts",
      "runtime-core",
      "runtime-domain",
      "runtime-engine",
      "runtime-infra",
      "runtime-infra:host",
      "runtime-infra:contracts",
      "runtime-infra:skills",
      "runtime-infra:launcher",
      "runtime-infra:workflow",
      "runtime-infra:http",
      "runtime-infra:sqlite",
      "runtime-cli",
      "runtime-mcp",
      "runtime-ports",
    )

  val moduleEdgeExpectations: Map<String, ModuleEdgeExpectation> =
    mapOf(
      "runtime-application" to
        ModuleEdgeExpectation(
          api = setOf("runtime-contracts", "runtime-domain", "runtime-ports"),
          implementation = emptySet(),
        ),
      "runtime-contracts" to
        ModuleEdgeExpectation(
          api = emptySet(),
          implementation = emptySet(),
        ),
      "runtime-core" to
        ModuleEdgeExpectation(
          api = setOf("runtime-application", "runtime-engine", "runtime-ports"),
          implementation =
            setOf(
              "runtime-domain",
              "runtime-contracts",
              "runtime-infra:host",
              "runtime-infra:contracts",
              "runtime-infra:skills",
              "runtime-infra:launcher",
              "runtime-infra:workflow",
              "runtime-infra:http",
              "runtime-infra:sqlite",
            ),
        ),
      "runtime-engine" to
        ModuleEdgeExpectation(
          api = setOf("runtime-contracts", "runtime-domain", "runtime-ports"),
          implementation = setOf("runtime-application"),
        ),
      "runtime-domain" to
        ModuleEdgeExpectation(
          api = emptySet(),
          implementation = setOf("runtime-contracts"),
        ),
      "runtime-infra" to
        ModuleEdgeExpectation(
          api = emptySet(),
          implementation = emptySet(),
        ),
      "runtime-infra:host" to
        ModuleEdgeExpectation(
          api = emptySet(),
          implementation = setOf("runtime-contracts", "runtime-domain", "runtime-ports"),
        ),
      "runtime-infra:contracts" to
        ModuleEdgeExpectation(
          api = emptySet(),
          implementation = setOf("runtime-contracts", "runtime-domain", "runtime-ports"),
        ),
      "runtime-infra:skills" to
        ModuleEdgeExpectation(
          api = emptySet(),
          implementation =
            setOf(
              "runtime-contracts",
              "runtime-domain",
              "runtime-ports",
              "runtime-infra:contracts",
              "runtime-infra:host",
            ),
        ),
      "runtime-infra:launcher" to
        ModuleEdgeExpectation(
          api = emptySet(),
          implementation =
            setOf(
              "runtime-contracts",
              "runtime-domain",
              "runtime-ports",
              "runtime-infra:skills",
              "runtime-infra:host",
            ),
        ),
      "runtime-infra:workflow" to
        ModuleEdgeExpectation(
          api = emptySet(),
          implementation =
            setOf(
              "runtime-contracts",
              "runtime-domain",
              "runtime-ports",
              "runtime-infra:skills",
              "runtime-infra:contracts",
              "runtime-infra:host",
            ),
        ),
      "runtime-infra:http" to
        ModuleEdgeExpectation(
          api = emptySet(),
          implementation = setOf("runtime-contracts", "runtime-domain", "runtime-ports"),
        ),
      "runtime-infra:sqlite" to
        ModuleEdgeExpectation(
          api = emptySet(),
          implementation = setOf("runtime-contracts", "runtime-domain", "runtime-ports"),
        ),
      "runtime-cli" to
        ModuleEdgeExpectation(
          api = emptySet(),
          implementation =
            setOf(
              "runtime-application",
              "runtime-contracts",
              "runtime-core",
              "runtime-domain",
              "runtime-engine",
              "runtime-ports",
            ),
        ),
      "runtime-mcp" to
        ModuleEdgeExpectation(
          api = emptySet(),
          implementation =
            setOf(
              "runtime-application",
              "runtime-contracts",
              "runtime-core",
              "runtime-domain",
              "runtime-engine",
              "runtime-ports",
            ),
        ),
      "runtime-ports" to
        ModuleEdgeExpectation(
          api = setOf("runtime-contracts", "runtime-domain"),
          implementation = emptySet(),
        ),
    )

  val mainProjectDependenciesByModule: Map<String, Set<String>> =
    moduleEdgeExpectations.mapValues { (_, expectation) -> expectation.api + expectation.implementation }

  val testFixturesProjectDependenciesByModule: Map<String, Set<String>> =
    mapOf(
      "runtime-application" to
        setOf(
          "runtime-infra:host",
          "runtime-infra:contracts",
          "runtime-infra:workflow",
          "runtime-ports",
        ),
      "runtime-contracts" to emptySet(),
      "runtime-core" to emptySet(),
      "runtime-engine" to
        setOf(
          "runtime-application",
          "runtime-domain",
          "runtime-infra:contracts",
          "runtime-infra:sqlite",
          "runtime-ports",
        ),
      "runtime-domain" to emptySet(),
      "runtime-infra" to emptySet(),
      "runtime-infra:host" to setOf("runtime-ports"),
      "runtime-infra:contracts" to emptySet(),
      "runtime-infra:skills" to setOf("runtime-domain", "runtime-ports"),
      "runtime-infra:launcher" to emptySet(),
      "runtime-infra:workflow" to emptySet(),
      "runtime-infra:http" to emptySet(),
      "runtime-infra:sqlite" to setOf("runtime-contracts", "runtime-domain", "runtime-ports"),
      "runtime-cli" to emptySet(),
      "runtime-mcp" to emptySet(),
      "runtime-ports" to emptySet(),
    )

  val declaredSubsystemPackages: List<String> =
    listOf(
      "skillbill.agent.model",
      "skillbill.agentaddon",
      "skillbill.application",
      "skillbill.cli",
      "skillbill.config",
      "skillbill.contracts",
      "skillbill.di",
      "skillbill.skillremove",
      "skillbill.engine",
      "skillbill.error",
      "skillbill.featurespec",
      "skillbill.goalrunner",
      "skillbill.idestatus",
      "skillbill.infrastructure",
      "skillbill.install",
      "skillbill.learnings",
      "skillbill.mcp",
      "skillbill.model",
      "skillbill.ports",
      "skillbill.review",
      "skillbill.scaffold",
      "skillbill.telemetry",
      "skillbill.text",
      "skillbill.workflow",
      "skillbill.workflow.verify",
    )

  val moduleMainPackageRoots: Map<String, String> =
    mapOf(
      "runtime-application" to "skillbill.application",
      "runtime-cli" to "skillbill.cli",
      "runtime-core" to "skillbill.di",
      "runtime-engine" to "skillbill.engine",
      "runtime-infra:host" to "skillbill.infrastructure.host",
      "runtime-infra:contracts" to "skillbill.infrastructure.contracts",
      "runtime-infra:skills" to "skillbill.infrastructure.skills",
      "runtime-infra:launcher" to "skillbill.infrastructure.launcher",
      "runtime-infra:workflow" to "skillbill.infrastructure.workflow",
      "runtime-infra:http" to "skillbill.infrastructure.http",
      "runtime-infra:sqlite" to "skillbill.infrastructure.sqlite",
      "runtime-mcp" to "skillbill.mcp",
    )

  fun gradleModuleIdToDirectoryPath(gradleModuleId: String): String = gradleModuleId.replace(':', '/')

  fun gradleModuleIdToBaselineStem(gradleModuleId: String): String = gradleModuleId.replace(':', '-')

  fun runtimeKotlinModuleDirectory(gradleModuleId: String): String =
    "runtime-kotlin/${gradleModuleIdToDirectoryPath(gradleModuleId)}"
}
