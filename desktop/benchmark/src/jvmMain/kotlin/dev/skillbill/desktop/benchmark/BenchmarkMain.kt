package dev.skillbill.desktop.benchmark

import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.ceil
import kotlin.system.exitProcess

fun main(args: Array<String>) {
  exitProcess(runBenchmark(args))
}

private data class Options(val store: Path?, val bursts: Int, val samples: Int, val coldRuns: Int)
private data class ColdResults(val values: List<Long>, val missed: Int, val reasons: List<String>)

private fun runBenchmark(args: Array<String>): Int {
  val options = parse(args)
  if (options == null) {
    System.err.println("Usage: benchmark [<store path>] [--bursts N] [--samples N] [--cold-runs N]")
    return 2
  }
  val temporary = Files.createTempDirectory("skill-bill-benchmark-")
  var status = 0
  try {
    val original = options.store
    val benchmarkStore = if (original == null) syntheticStore(temporary.resolve("synthetic")) else {
      if (!Files.isRegularFile(original) || !Files.isReadable(original)) {
        error("Store path is not a readable file: $original")
      }
      copyStore(original, temporary.resolve("copy"))
    }
    val cold = coldStart(benchmarkStore, temporary.resolve("cold"), options.coldRuns)
    val scenarioHome = temporary.resolve("scenario")
    Files.createDirectories(scenarioHome)
    val scenarioStore = copyStore(benchmarkStore, scenarioHome)
    val scenarios = runScenarios(scenarioStore.toString(), scenarioHome.toString(), options.bursts, options.samples)
    report("header paint data ready on selection", scenarios.headerMillis, 16)
    report("first detail sections", scenarios.sectionsMillis, 150)
    report("signal to board updated", scenarios.signalMillis, 100, scenarios.missedSignals, "lifecycle board transition timed out")
    report("filter to results", scenarios.filterMillis, 50)
    report(
      "cold start to first board", cold.values, 2500, cold.missed,
      cold.reasons.distinct().joinToString(", ").ifBlank { "cold probe did not return a figure" },
    )
    println("burst activity                       board emissions ${scenarios.burstBoardEmissions}; runtime reads ${scenarios.burstRuntimeReads}; missed updates ${scenarios.missedSignals}; total board emissions ${scenarios.boardEmissions}")
    report("runtime reads (diagnostics)", scenarios.runtimeReads, Long.MAX_VALUE)
    println("store mode                            ${if (original == null) "synthetic with 5,000 terminal records" else "copy of $original"}")
  } catch (failure: LinkageError) {
    System.err.println("Benchmark could not produce figures: ${failure.javaClass.simpleName}: ${failure.message}")
    status = 2
  } catch (failure: Exception) {
    System.err.println("Benchmark could not produce figures: ${failure.javaClass.simpleName}: ${failure.message}")
    status = 2
  } finally {
    try {
      check(temporary.toFile().deleteRecursively()) { "Temporary benchmark directory was not fully removed: $temporary" }
    } catch (cleanup: Exception) {
      System.err.println("Benchmark cleanup failed: ${cleanup.javaClass.simpleName}: ${cleanup.message}")
      status = 2
    }
  }
  return status
}

private fun parse(args: Array<String>): Options? {
  var store: Path? = null
  var bursts = 20
  var samples = 30
  var coldRuns = 5
  var index = 0
  while (index < args.size) {
    when (val arg = args[index++]) {
      "--bursts" -> bursts = args.getOrNull(index++)?.toIntOrNull() ?: return null
      "--samples" -> samples = args.getOrNull(index++)?.toIntOrNull() ?: return null
      "--cold-runs" -> coldRuns = args.getOrNull(index++)?.toIntOrNull() ?: return null
      else -> if (arg.startsWith("--") || store != null) return null else store = Path.of(arg).toAbsolutePath()
    }
  }
  if (bursts < 1 || samples < 1 || coldRuns < 1) return null
  return Options(store, bursts, samples, coldRuns)
}

private fun coldStart(database: Path, root: Path, count: Int): ColdResults {
  val results = mutableListOf<Long>()
  val reasons = mutableListOf<String>()
  repeat(count) { index ->
    val home = root.resolve("run-$index")
    Files.createDirectories(home)
    val copy = copyStore(database, home)
    try {
      val urls = System.getProperty("java.class.path").split(java.io.File.pathSeparator).map { Path.of(it).toUri().toURL() }.toTypedArray()
      URLClassLoader(urls, ClassLoader.getPlatformClassLoader()).use { loader ->
        val probe = loader.loadClass("dev.skillbill.desktop.benchmark.ColdStartProbe")
        val method = MethodHandles.publicLookup().findStatic(
          probe, "firstBoardMillis", MethodType.methodType(Long::class.javaPrimitiveType, String::class.java, String::class.java),
        )
        results += method.invokeWithArguments(copy.toString(), home.toString()) as Long
      }
    } catch (failure: LinkageError) {
      reasons += failure.javaClass.simpleName
    }
  }
  return ColdResults(results, count - results.size, reasons)
}

private fun report(name: String, values: List<Long>, target: Long, missed: Int = 0, reason: String = "no successful samples") {
  val targetLabel = if (target == Long.MAX_VALUE) "reported" else "< ${target} ms"
  if (values.isEmpty()) {
    val detail = if (missed > 0) "$reason; missed $missed" else reason
    println("%-36s unavailable: %-26s target %-22s (n=0)".format(name, detail, targetLabel))
    return
  }
  val p50 = percentile(values, 0.50)
  val p95 = percentile(values, 0.95)
  val verdict = if (target == Long.MAX_VALUE || p95 < target) "within" else "over"
  val misses = if (missed > 0) "; missed $missed: $reason" else ""
  println("%-36s p50 %6d ms  p95 %6d ms  target %-22s %s (n=%d)%s".format(name, p50, p95, targetLabel, verdict, values.size, misses))
}

private fun percentile(values: List<Long>, fraction: Double): Long {
  val sorted = values.sorted()
  return sorted[(ceil(sorted.size * fraction).toInt() - 1).coerceAtLeast(0)]
}
