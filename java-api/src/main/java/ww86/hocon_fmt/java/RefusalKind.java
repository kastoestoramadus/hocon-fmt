package ww86.hocon_fmt.java;

import org.jspecify.annotations.NullMarked;

/**
 * Why the formatter refused an input: one constant per case of the core's {@code Refusal} enum, in
 * the same order. The test suite reflects over the core's companion and fails when it grows a
 * case, so a new refusal cannot slip through this mirror unnamed.
 */
@NullMarked
public enum RefusalKind {
    /** Decoding the bytes leniently would have replaced the invalid ones. */
    NotUtf8,
    /** The input is not HOCON that sconfig can read. */
    NotHocon,
    /** sconfig rendered text it cannot read back. */
    BrokenOutput,
    /** sconfig would drop a comment that no field follows. */
    LostComment,
    /** sconfig would drop an include that a later definition of the same key replaces. */
    LostInclude,
    /** Formatting would carry a field across an include and change which values win. */
    MovedInclude,
    /** A second formatting pass would change the output again, so the file would never settle. */
    UnstableOutput;

    /**
     * The kind of a refusal raised by the core, for callers holding a {@code Refusal} from a
     * {@link FormatRefusedException}. The dispatch is on the case's own name because the core's
     * parameterless cases have no JVM type to test against, and their singletons sit behind a
     * {@code MODULE$} expression that {@code -Xlint:static} forbids; the names are pinned by the
     * parity test, and a case the mirror does not know fails loudly instead of masquerading as
     * one of these constants.
     */
    public static RefusalKind of(ww86.hocon_fmt.Refusal refusal) {
        return switch (refusal.productPrefix()) {
            case "NotUtf8" -> NotUtf8;
            case "NotHocon" -> NotHocon;
            case "BrokenOutput" -> BrokenOutput;
            case "LostComment" -> LostComment;
            case "LostInclude" -> LostInclude;
            case "MovedInclude" -> MovedInclude;
            case "UnstableOutput" -> UnstableOutput;
            default -> throw new IllegalStateException("the core grew a refusal kind the mirror"
                    + " does not know: " + refusal.productPrefix());
        };
    }
}
