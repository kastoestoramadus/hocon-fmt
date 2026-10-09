package eu.ww86.hoconfmt.java;

/**
 * What formatting makes of one input, as a Java caller sees it. The core decides this as a Scala 3
 * enum; this mirror carries the same three outcomes in types the JVM understands natively.
 *
 * <p>The mirror exists because Scala 3 writes sealed-ness into TASTy, not into the class file: the
 * compiled {@code eu.ww86.hoconfmt.Verdict} carries no {@code PermittedSubclasses} attribute, its
 * parameterless case has no class of its own to test against, and so no Java compiler — not even
 * one on Java 21 — can check a {@code switch} over it for exhaustiveness. Sealed plus records
 * restores exactly that, on Java 21; on Java 17 callers read the outcome with {@code instanceof}.
 */
public sealed interface Verdict
        permits Verdict.AlreadyFormatted, Verdict.NeedsFormatting, Verdict.Refused {

    /** The input already is the exact text the formatter would write. */
    record AlreadyFormatted() implements Verdict {}

    /**
     * The formatted text differs; {@link #formatted()} is what should replace the input.
     *
     * @param formatted the text the input should become
     */
    record NeedsFormatting(String formatted) implements Verdict {}

    /**
     * The formatter refuses the input; nothing may be written, and {@link #reason()} says why.
     *
     * @param kind which refusal the core raised
     * @param reason why the input is left alone, as a user reads it
     */
    record Refused(RefusalKind kind, String reason) implements Verdict {}
}
