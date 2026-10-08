package ww86.hocon_fmt.maven;

import ww86.hocon_fmt.java.HoconFmt;
import ww86.hocon_fmt.java.Verdict;

/** What formatting makes of one file's content, decided without touching the file. */
sealed interface Outcome {

  record AlreadyFormatted() implements Outcome {}

  record NeedsFormatting(String formatted) implements Outcome {}

  /** The formatter will not handle this content, so the file has to stay exactly as it is. */
  record Refused(String reason) implements Outcome {}

  /** The name is how the build reports the file: a refusal carries it, and a name promising
   * another format is refused outright. */
  static Outcome of(byte[] content, String name) {
    // The plugin targets Java 17, where a pattern switch does not compile; the sealed Verdict is
    // read with instanceof and a verdict this code does not know fails loudly.
    Verdict verdict = HoconFmt.check(content, name);
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
