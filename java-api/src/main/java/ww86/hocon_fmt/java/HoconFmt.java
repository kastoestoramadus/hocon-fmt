package ww86.hocon_fmt.java;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import ww86.hocon_fmt.FormatRefusedException;

/**
 * Static entry points for Java and Kotlin callers. Every method returns a {@link Verdict} —
 * including the refusals, which are values here rather than exceptions — and never accepts or
 * returns null.
 *
 * <p>A name is the name the caller knows the text or file by: a refusal reports it as the place a
 * parse tripped, and a name promising another format (a {@code .json} or {@code .properties} file)
 * is refused rather than rewritten as HOCON.
 */
public final class HoconFmt {

    private HoconFmt() {}

    /**
     * What the formatter makes of HOCON text.
     *
     * @param text the HOCON to judge
     * @return the verdict: already formatted, needs formatting, or refused
     */
    public static Verdict check(String text) {
        return mirror(ww86.hocon_fmt.Verdict.of(text));
    }

    /**
     * What the formatter makes of HOCON text the caller knows a name for.
     *
     * @param text the HOCON to judge
     * @param name the name the caller knows the text by
     * @return the verdict: already formatted, needs formatting, or refused
     */
    public static Verdict check(String text, String name) {
        return mirror(ww86.hocon_fmt.Verdict.of(text, name));
    }

    /**
     * What the formatter makes of UTF-8 bytes; bytes that are not valid UTF-8 are refused.
     *
     * @param content the UTF-8 bytes of the HOCON to judge
     * @return the verdict: already formatted, needs formatting, or refused
     */
    public static Verdict check(byte[] content) {
        return mirror(ww86.hocon_fmt.Verdict.of(content));
    }

    /**
     * What the formatter makes of UTF-8 bytes the caller knows a name for.
     *
     * @param content the UTF-8 bytes of the HOCON to judge
     * @param name the name the caller knows the bytes by
     * @return the verdict: already formatted, needs formatting, or refused
     */
    public static Verdict check(byte[] content, String name) {
        return mirror(ww86.hocon_fmt.Verdict.of(content, name));
    }

    /**
     * Reads the file as UTF-8 bytes and judges its content, without touching it.
     *
     * @param file the file to read
     * @return the verdict: already formatted, needs formatting, or refused
     * @throws IOException when the file cannot be read
     */
    public static Verdict checkFile(Path file) throws IOException {
        return check(Files.readAllBytes(file), file.toString());
    }

    /**
     * Rewrites the file when the formatted text differs, and leaves it byte for byte when it is
     * already formatted or refused. The write itself is the one the Gradle and Maven plugins make:
     * {@link Files#writeString} over the whole file.
     *
     * @param file the file to read, and to rewrite when formatting changes it
     * @return the verdict, so a caller can tell a write from a pass without rereading
     * @throws IOException when the file cannot be read or written
     */
    public static Verdict formatFile(Path file) throws IOException {
        Verdict verdict = check(Files.readAllBytes(file), file.toString());
        if (verdict instanceof Verdict.NeedsFormatting needed) {
            Files.writeString(file, needed.formatted());
        }
        return verdict;
    }

    /**
     * The formatted text, or the exception the JVM integrations raise. Already formatted text
     * comes back as it is.
     *
     * @param text the HOCON to format
     * @return the text the input should become; the input itself when already formatted
     * @throws FormatRefusedException when the input must be left alone; the message says why
     */
    public static String formatOrThrow(String text) throws FormatRefusedException {
        return textOf(ww86.hocon_fmt.Verdict.of(text), text);
    }

    /**
     * As {@link #formatOrThrow(String)}, with the name the caller knows the text by.
     *
     * @param text the HOCON to format
     * @param name the name the caller knows the text by
     * @return the text the input should become; the input itself when already formatted
     * @throws FormatRefusedException when the input must be left alone; the message says why
     */
    public static String formatOrThrow(String text, String name) throws FormatRefusedException {
        return textOf(ww86.hocon_fmt.Verdict.of(text, name), text);
    }

    private static String textOf(ww86.hocon_fmt.Verdict verdict, String text) throws FormatRefusedException {
        if (verdict instanceof ww86.hocon_fmt.Verdict.NeedsFormatting needed) {
            return needed.formatted();
        }
        if (verdict instanceof ww86.hocon_fmt.Verdict.Refused refused) {
            throw new FormatRefusedException(refused.refusal());
        }
        return text;
    }

    /**
     * The core's Scala 3 enum has no JVM-level type for its parameterless case and no
     * {@code PermittedSubclasses}, so the named case classes are read with {@code instanceof} and
     * the singleton by the case's own name — pinned by the parity test. A verdict the mirror does
     * not know fails loudly instead of passing as one of the three.
     */
    private static Verdict mirror(ww86.hocon_fmt.Verdict verdict) {
        if (verdict instanceof ww86.hocon_fmt.Verdict.NeedsFormatting needed) {
            return new Verdict.NeedsFormatting(needed.formatted());
        }
        if (verdict instanceof ww86.hocon_fmt.Verdict.Refused refused) {
            ww86.hocon_fmt.Refusal refusal = refused.refusal();
            return new Verdict.Refused(RefusalKind.of(refusal), refusal.reason());
        }
        if ("AlreadyFormatted".equals(verdict.productPrefix())) {
            return new Verdict.AlreadyFormatted();
        }
        throw new IllegalStateException("the core grew a verdict the mirror does not know: "
                + verdict.productPrefix());
    }
}
