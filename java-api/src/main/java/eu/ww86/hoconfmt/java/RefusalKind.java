package eu.ww86.hoconfmt.java;

/**
 * Why the formatter refused an input: one constant per case of the core's {@code Refusal} enum, in
 * the same order. The test suite reflects over the core's companion and fails when it grows a
 * case, so a new refusal cannot slip through this mirror unnamed.
 */
public enum RefusalKind {
    /** Decoding the bytes leniently would have replaced the invalid ones. */
    NotUtf8,
    /** The input is not HOCON that sconfig can read. */
    NotHocon,
    /** The file is named as a format of its own that Lightbend's loader also reads. */
    OtherFormat,
    /** sconfig rendered text it cannot read back. */
    BrokenOutput,
    /** sconfig would drop a comment that no field follows. */
    LostComment,
    /** sconfig would drop an include that a later definition of the same key replaces. */
    LostInclude,
    /** Formatting would carry a field across an include and change which values win. */
    MovedInclude,
    /** The text spells the name the formatter writes include placeholders with. */
    ReservedName,
    /** A second formatting pass would change the output again, so the file would never settle. */
    UnstableOutput;

    /**
     * The kind of a refusal raised by the core, for callers holding a {@code Refusal} from a
     * {@link eu.ww86.hoconfmt.FormatRefusedException}. The dispatch is on the case's own name
     * because the core's parameterless cases have no JVM type to test against, and their
     * singletons sit behind a {@code MODULE$} expression that {@code -Xlint:static} forbids; the
     * names are pinned by the parity test, and a case the mirror does not know fails loudly
     * instead of masquerading as one of these constants.
     *
     * @param refusal the refusal the core raised
     * @return the constant standing for the refusal's case
     */
    public static RefusalKind of(eu.ww86.hoconfmt.Refusal refusal) {
        return switch (refusal.productPrefix()) {
            case "NotUtf8" -> NotUtf8;
            case "NotHocon" -> NotHocon;
            case "OtherFormat" -> OtherFormat;
            case "BrokenOutput" -> BrokenOutput;
            case "LostComment" -> LostComment;
            case "LostInclude" -> LostInclude;
            case "MovedInclude" -> MovedInclude;
            case "ReservedName" -> ReservedName;
            case "UnstableOutput" -> UnstableOutput;
            default -> throw new IllegalStateException("the core grew a refusal kind the mirror"
                    + " does not know: " + refusal.productPrefix());
        };
    }
}
