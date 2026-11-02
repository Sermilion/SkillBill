package dev.skillbill.desktop.core.domain.model

fun isFileUnderDirectory(path: String, directory: String): Boolean {
  val prefix = "$directory/"
  return directory.isNotBlank() && path.startsWith(prefix) && path.length > prefix.length && ".." !in path.split('/')
}
