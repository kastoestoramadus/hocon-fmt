package ww86.hocon_fmt.maven;

import ww86.hocon_fmt.FormatRefusedException;
import ww86.hocon_fmt.JvmFacade;

/** What formatting makes of one file's content, decided without touching the file. */
sealed interface Outcome {

  record AlreadyFormatted() implements Outcome {}

  record NeedsFormatting(String formatted) implements Outcome {}

  /** The formatter will not handle this content, so the file has to stay exactly as it is. */
  record Refused(String reason) implements Outcome {}

  /** The name is how the build reports the file: a refusal carries it, and a name promising
   * another format is refused outright. */
  static Outcome of(byte[] content, String name) {
    try {
      return JvmFacade.reformat(content, name)
          .<Outcome>map(NeedsFormatting::new)
          .orElseGet(AlreadyFormatted::new);
    } catch (FormatRefusedException e) {
      return new Refused(e.getMessage());
    }
  }
}
