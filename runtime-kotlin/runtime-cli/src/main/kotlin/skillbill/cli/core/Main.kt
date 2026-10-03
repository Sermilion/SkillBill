package skillbill.cli.core

import skillbill.cli.model.CliRuntimeContext
import skillbill.di.core.PackagedContractComponent
import skillbill.di.core.create
import skillbill.error.core.SkillBillRuntimeException
import kotlin.system.exitProcess

fun main(args: Array<String>) {
  if (args.contentEquals(arrayOf("--check-packaged-contracts"))) {
    try {
      PackagedContractComponent::class.create().inspector.inspect()
      println("Packaged contracts match producer versions.")
    } catch (error: SkillBillRuntimeException) {
      System.err.println(error.message)
      exitProcess(1)
    }
    return
  }
  val result =
    CliRuntime.run(
      args.toList(),
      CliRuntimeContext(
        liveStdout = { print(it) },
        liveStderr = { System.err.print(it) },
      ),
    )
  emitCliProcessStdout(result, System.out)
  if (result.stderr.isNotEmpty()) {
    System.err.print(result.stderr)
  }
  exitProcess(result.exitCode)
}
