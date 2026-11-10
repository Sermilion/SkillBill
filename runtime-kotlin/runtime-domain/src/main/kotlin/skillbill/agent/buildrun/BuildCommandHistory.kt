package skillbill.agent.buildrun

private val commandWords = Regex("\\s+")
private val gradleOutputFlags =
  setOf("-q", "--quiet", "-i", "--info", "-d", "--debug", "-s", "--stacktrace", "-S", "--full-stacktrace")
private val gradleOutputOptions = setOf("--console", "--warning-mode")

fun buildCommandHistoryKey(command: String): List<String> {
  if ('\'' in command || '"' in command) return listOf(command)
  val words = command.trim().split(commandWords)
  if (words.first() !in setOf("gradlew", "./gradlew")) return listOf(command)
  return buildList {
    add("./gradlew")
    var index = 1
    while (index < words.size) {
      val word = words[index++]
      when {
        word == "--" -> {
          add(word)
          addAll(words.drop(index))
          break
        }
        word in gradleOutputFlags -> Unit
        word in gradleOutputOptions && index < words.size -> index++
        word.substringBefore('=') in gradleOutputOptions && '=' in word -> Unit
        else -> add(word)
      }
    }
  }
}
