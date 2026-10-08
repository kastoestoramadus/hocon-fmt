package ww86.hocon_fmt

/** A refusal raised in the error channel, for callers whose channel carries values rather than
  * `Either`s. The core itself never throws one: every integration that raises a refusal — the
  * facade the build plugins call, the cats adapter — raises this type, so a caller can catch one
  * exception for all of them and read the typed reason from `.refusal`. The message stays the
  * reason, because a Java caller sees only that.
  */
final case class FormatRefusedException(refusal: Refusal) extends Exception(refusal.reason)
