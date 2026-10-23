package skillbill.architecture

import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PackageSiblingCountArchitectureTest {
  @Test
  fun `production package census stays within ceilings outside the remainder inventory`() {
    val sourceRoots = PrincipleEnforcementInventory.productionPackageSiblingCountSourceRoots
    assertEngineAreaRootsKeepOnlyFacades()
    assertTrue(
      sourceRoots.all { sourceRoot ->
        ArchitectureScanSupport.kotlinFilesUnder(
          ArchitectureScanSupport.runtimeRoot.resolve(sourceRoot),
        ).isNotEmpty()
      },
      "Every production source root must contribute Kotlin files to the sibling census.",
    )
    val counts = ArchitectureScanSupport.productionPackageSiblingCounts(sourceRoots)
    val remainderInventory = PrincipleEnforcementInventory.packageSiblingCountRemainderInventory
    assertEquals(emptyMap(), remainderInventory)
    assertEquals(
      emptyList(),
      ArchitectureScanSupport.productionPackageSiblingCountViolations(
        sourceRoots = sourceRoots,
        remainderInventory = remainderInventory.keys,
      ),
    )
    assertCensusCoversNestedEnginePackages(counts, remainderInventory)
  }

  private fun assertEngineAreaRootsKeepOnlyFacades() {
    val featureTaskRoot =
      ArchitectureScanSupport.runtimeRoot.resolve(
        "runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/featuretask",
      )
    val goalRunnerRoot =
      ArchitectureScanSupport.runtimeRoot.resolve(
        "runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/goalrunner",
      )
    val goalRunnerPlanningRoot = goalRunnerRoot.resolve("planning")
    assertEquals(
      emptyList(),
      ArchitectureScanSupport.kotlinFilesUnder(featureTaskRoot)
        .filter { sourceFile -> sourceFile.parent == featureTaskRoot },
      "Run-loop, phase, review, persist, lifecycle, prepare, and runner types must not remain in the area root.",
    )
    assertEquals(
      emptyList(),
      ArchitectureScanSupport.kotlinFilesUnder(goalRunnerRoot)
        .filter { sourceFile ->
          sourceFile.parent == goalRunnerRoot &&
            sourceFile.fileName.toString() != "GoalRunner.kt" &&
            sourceFile.fileName.toString() != "GoalOperatorDecisionService.kt" &&
            sourceFile.fileName.toString() != "GoalPreflightService.kt" &&
            sourceFile.fileName.toString() != "GoalRunnerStatusService.kt" &&
            sourceFile.fileName.toString() != "GoalRepositoryIdentity.kt"
        },
      "Status, preflight, repair, launch, manifest, execution, " +
        "and telemetry types must not remain in the goal-runner area root.",
    )
    assertEquals(
      emptyList(),
      ArchitectureScanSupport.kotlinFilesUnder(goalRunnerPlanningRoot)
        .filter { sourceFile ->
          sourceFile.parent == goalRunnerPlanningRoot &&
            sourceFile.fileName.toString() != "GoalPlanningLogService.kt"
        },
      "Planning context, attempt, sweep, outcome, recovery, and " +
        "remedy types must not remain in the planning area root.",
    )
  }

  private fun assertCensusCoversNestedEnginePackages(
    counts: List<ArchitectureScanSupport.PackageSiblingCount>,
    remainderInventory: Map<String, String>,
  ) {
    assertTrue(
      counts.any { count -> count.packageName == "skillbill.engine.featuretask.lifecycle.core" },
      "The sibling census must scan the nested feature-task production packages.",
    )
    assertTrue(
      counts.any { count -> count.packageName == "skillbill.engine.goalrunner.execution.core" },
      "The sibling census must scan the nested goal-runner production packages.",
    )
    assertTrue(
      counts.filter { count -> count.packageName.startsWith("skillbill.engine.featuretask") }
        .all { count -> count.fileCount <= count.ceiling },
      "Feature-task packages must remain within their applicable sibling ceilings.",
    )
    assertTrue(
      counts.filter { count -> count.packageName.startsWith("skillbill.engine.goalrunner") }
        .all { count -> count.fileCount <= count.ceiling },
      "Goal-runner packages must remain within their applicable sibling ceilings.",
    )
    assertTrue(
      remainderInventory.keys.none { packageName ->
        packageName == "skillbill.engine.featuretask" ||
          packageName.startsWith("skillbill.engine.featuretask.")
      },
      "The feature-task package tree must not be deferred in the remainder inventory.",
    )
    assertTrue(
      remainderInventory.keys.none { packageName ->
        packageName == "skillbill.engine.goalrunner" ||
          packageName.startsWith("skillbill.engine.goalrunner.")
      },
      "The goal-runner package tree must not be deferred in the remainder inventory.",
    )
    assertTrue(
      remainderInventory.keys.all { packageName ->
        counts.single { count -> count.packageName == packageName }.fileCount >
          counts.single { count -> count.packageName == packageName }.ceiling
      },
      "Every remainder entry must identify a package that is still over its ceiling.",
    )
  }

  @Test
  fun `feature-task model sources stay data-only and below their parent boundary`() {
    val modelRoot =
      ArchitectureScanSupport.runtimeRoot.resolve(
        "runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/featuretask/model",
      )
    val violations =
      ArchitectureScanSupport.kotlinFilesUnder(modelRoot).flatMap { sourceFile ->
        val source = sourceFile.readText()
        val parentImports =
          ArchitectureScanSupport.declaredImports(source)
            .filter { imported ->
              imported.startsWith("skillbill.engine.featuretask.") &&
                !imported.startsWith("skillbill.engine.featuretask.model.")
            }
            .map { imported -> "${sourceFile.fileName}: imports $imported" }
        val injected =
          Regex("""(?m)^\s*@Inject\b""")
            .find(source)
            ?.let { listOf("${sourceFile.fileName}: declares an injected model service") }
            .orEmpty()
        parentImports + injected
      }
    assertEquals(emptyList(), violations)
  }

  @Test
  fun `task-runtime and application review models stay data-only below their parent boundaries`() {
    val modelAreas =
      listOf(
        Triple(
          "runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/taskruntime/model",
          "skillbill.workflow.taskruntime.model",
          "skillbill.workflow.taskruntime",
        ),
        Triple(
          "runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/review/model",
          "skillbill.application.review.model",
          "skillbill.application.review",
        ),
      )
    val violations =
      modelAreas.flatMap { (relativeRoot, modelPackage, parentPackage) ->
        val modelRoot = ArchitectureScanSupport.runtimeRoot.resolve(relativeRoot)
        ArchitectureScanSupport.kotlinFilesUnder(modelRoot).flatMap { sourceFile ->
          val source = sourceFile.readText()
          val declaredPackage = ArchitectureScanSupport.declaredPackage(source).orEmpty()
          if (declaredPackage != modelPackage && !declaredPackage.startsWith("$modelPackage.")) {
            emptyList()
          } else {
            val parentImports =
              ArchitectureScanSupport.declaredImports(source)
                .filter { imported ->
                  imported == parentPackage ||
                    (imported.startsWith("$parentPackage.") && !imported.startsWith("$modelPackage."))
                }
                .map { imported -> "${sourceFile.fileName}: imports $imported" }
            val injected =
              Regex("""(?m)^\s*@Inject\b""")
                .find(source)
                ?.let { listOf("${sourceFile.fileName}: declares an injected model service") }
                .orEmpty()
            parentImports + injected
          }
        }
      }
    assertEquals(emptyList(), violations)
  }

  @Test
  fun `goal-runner model sources stay data-only and below their parent boundary`() {
    val modelRoot =
      ArchitectureScanSupport.runtimeRoot.resolve(
        "runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/goalrunner/model",
      )
    val violations =
      ArchitectureScanSupport.kotlinFilesUnder(modelRoot).flatMap { sourceFile ->
        val source = sourceFile.readText()
        val parentImports =
          ArchitectureScanSupport.declaredImports(source)
            .filter { imported ->
              imported.startsWith("skillbill.engine.goalrunner.") &&
                !imported.startsWith("skillbill.engine.goalrunner.model.")
            }
            .map { imported -> "${sourceFile.fileName}: imports $imported" }
        val injected =
          Regex("""(?m)^\s*@Inject\b""")
            .find(source)
            ?.let { listOf("${sourceFile.fileName}: declares an injected model service") }
            .orEmpty()
        parentImports + injected
      }
    assertEquals(emptyList(), violations)
  }

  @Test
  fun `goal-runner test sources use the production package they exercise`() {
    val sourceSetRoots =
      listOf(
        ArchitectureScanSupport.runtimeRoot.resolve("runtime-kotlin/runtime-engine/src/test/kotlin"),
        ArchitectureScanSupport.runtimeRoot.resolve("runtime-kotlin/runtime-engine/src/testFixtures/kotlin"),
      )
    val misplaced =
      sourceSetRoots.flatMap { sourceSetRoot ->
        ArchitectureScanSupport.kotlinFilesUnder(sourceSetRoot.resolve("skillbill/engine/goalrunner"))
          .mapNotNull { sourceFile ->
            val packageName = ArchitectureScanSupport.declaredPackage(sourceFile.readText()) ?: return@mapNotNull null
            val expectedDirectory = sourceSetRoot.resolve(packageName.replace('.', '/'))
            (sourceFile.parent != expectedDirectory).let { isMisplaced ->
              if (isMisplaced) {
                "${sourceSetRoot.relativize(sourceFile)} declares $packageName"
              } else {
                null
              }
            }
          }
      }
    assertEquals(emptyList(), misplaced)
  }

  @Test
  fun `feature-task test sources use the production package they exercise`() {
    val sourceSetRoots =
      listOf(
        ArchitectureScanSupport.runtimeRoot.resolve("runtime-kotlin/runtime-engine/src/test/kotlin"),
        ArchitectureScanSupport.runtimeRoot.resolve("runtime-kotlin/runtime-engine/src/testFixtures/kotlin"),
      )
    val misplaced =
      sourceSetRoots.flatMap { sourceSetRoot ->
        ArchitectureScanSupport.kotlinFilesUnder(sourceSetRoot.resolve("skillbill/engine/featuretask"))
          .mapNotNull { sourceFile ->
            val packageName = ArchitectureScanSupport.declaredPackage(sourceFile.readText()) ?: return@mapNotNull null
            val expectedDirectory = sourceSetRoot.resolve(packageName.replace('.', '/'))
            (sourceFile.parent != expectedDirectory).let { isMisplaced ->
              if (isMisplaced) {
                "${sourceSetRoot.relativize(sourceFile)} declares $packageName"
              } else {
                null
              }
            }
          }
      }
    assertEquals(emptyList(), misplaced)
  }

  @Test
  fun `runtime test sources remain co-located with their declared packages`() {
    val sourceSetRoots =
      PrincipleEnforcementInventory.productionPackageSiblingCountSourceRoots
        .mapNotNull { sourceRoot ->
          val moduleRoot = ArchitectureScanSupport.runtimeRoot.resolve(sourceRoot).parent.parent.parent
          listOf(
            moduleRoot.resolve("src/test/kotlin"),
            moduleRoot.resolve("src/testFixtures/kotlin"),
            moduleRoot.resolve("src/repoTest/kotlin"),
          ).filter { sourceSetRoot -> sourceSetRoot.toFile().isDirectory }
        }
        .flatten()
    val misplaced =
      sourceSetRoots.flatMap { sourceSetRoot ->
        ArchitectureScanSupport.kotlinFilesUnder(sourceSetRoot).mapNotNull { sourceFile ->
          val packageName =
            ArchitectureScanSupport.declaredPackage(sourceFile.readText())
              ?: return@mapNotNull null
          val expectedDirectory = sourceSetRoot.resolve(packageName.replace('.', '/'))
          if (sourceFile.parent == expectedDirectory) {
            null
          } else {
            "${sourceSetRoot.relativize(sourceFile)} declares $packageName"
          }
        }
      }
    assertEquals(emptyList(), misplaced)
  }

  @Test
  fun `sibling count guard rejects synthetic packages above model and non-model ceilings`() {
    assertEquals(
      listOf(
        "skillbill.synthetic has 13 production Kotlin siblings; the 12-file ceiling applies to this package.",
      ),
      ArchitectureScanSupport.productionPackageSiblingCountViolationsForCounts(
        counts =
          listOf(
            ArchitectureScanSupport.PackageSiblingCount(
              packageName = "skillbill.synthetic",
              fileCount = 13,
              ceiling = 12,
            ),
          ),
        remainderInventory = emptySet(),
      ),
    )
    assertEquals(
      "skillbill.synthetic.model has 21 production Kotlin siblings; the 20-file ceiling applies to this package.",
      ArchitectureScanSupport.packageSiblingCountViolationMessage(
        packageName = "skillbill.synthetic.model",
        fileCount = 21,
      ),
    )
    assertEquals(
      null,
      ArchitectureScanSupport.packageSiblingCountViolationMessage(
        packageName = "skillbill.synthetic",
        fileCount = 12,
      ),
    )
  }
}
