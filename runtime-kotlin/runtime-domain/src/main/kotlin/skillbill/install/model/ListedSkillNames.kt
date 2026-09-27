package skillbill.install.model

object ListedSkillNames {
  const val PREFIX: String = "bill-"
  const val DISPATCHER: String = "skill-bill"

  fun isListed(name: String): Boolean = name.startsWith(PREFIX) || name == DISPATCHER
}
