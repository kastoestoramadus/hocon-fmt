package eu.ww86.hoconfmt.maven;

import eu.ww86.hoconfmt.java.Verdict;

/** The Java API's file verdict, as reported by either goal. */
sealed interface Outcome {

  record AlreadyFormatted() implements Outcome {}

  record NeedsFormatting(String formatted) implements Outcome {}

  /** The formatter will not handle this content, so the file has to stay exactly as it is. */
  record Refused(String reason) implements Outcome {}

  /** Both goals report the verdict returned by the Java API's file operation. */
  static Outcome of(Verdict verdict) {
    // The plugin targets Java 17, where a pattern switch does not compile; the sealed Verdict is
    // read with instanceof and a verdict this code does not know fails loudly.
    if (verdict instanceof Verdict.NeedsFormatting needed) {
      return new NeedsFormatting(needed.formatted());
    }
    if (verdict instanceof Verdict.Refused refused) {
      return new Refused(refused.reason());
    }
    if (verdict instanceof Verdict.AlreadyFormatted) {
      return new AlreadyFormatted();
    }
    throw new IllegalStateException("the Java API grew a verdict the plugin does not know: " + verdict);
  }
}
