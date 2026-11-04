package skillbill.engine.featuretask.slot

import java.lang.reflect.Proxy
import kotlin.test.fail

class InterfaceStubs {
  val unexpectedCalls: MutableList<String> = mutableListOf()

  inline fun <reified T : Any> stub(crossinline answer: (String, List<Any?>) -> Any?): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
      when (method.name) {
        "toString" -> "stub ${T::class.simpleName}"
        "hashCode" -> 0
        "equals" -> false
        else ->
          answer(method.name, args?.toList().orEmpty()).also { value ->
            if (value === Unstubbed) {
              val call = "${T::class.simpleName}.${method.name}"
              unexpectedCalls += call
              fail("unexpected call $call")
            }
          }
      }
    } as T

  object Unstubbed
}
