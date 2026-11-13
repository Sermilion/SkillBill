package skillbill.install.model

object ListedSkillNames {
  const val PREFIX: String = "bill-"
  const val DISPATCHER: String = "skill-bill"
  const val PUBLIC_DISPATCHER: String = "sona"

  fun publicName(sourceName: String): String = if (sourceName == DISPATCHER) PUBLIC_DISPATCHER else sourceName

  fun sourceName(publicName: String): String = if (publicName == PUBLIC_DISPATCHER) DISPATCHER else publicName

  fun isListed(name: String): Boolean = name.startsWith(PREFIX) || name == DISPATCHER
}
