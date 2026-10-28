package dev.skillbill.desktop.core.common

/** The result of an operation that can fail with a typed error instead of an exception. */
sealed interface Outcome<out T, out E> {
  data class Success<out T>(val value: T) : Outcome<T, Nothing>

  data class Failure<out E>(val error: E) : Outcome<Nothing, E>
}
