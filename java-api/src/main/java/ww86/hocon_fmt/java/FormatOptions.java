package ww86.hocon_fmt.java;

/**
 * Complete renderer options, with the same defaults and names as the CLI.
 * @param separator assignment token, = or :
 * @param doubleIndent whether object contents use double indentation
 * @param simplifyNestedObjects whether single-field objects become dotted paths
 * @param failOnDuplicates whether findings fail the caller's run
 */
public record FormatOptions(String separator, boolean doubleIndent,
        boolean simplifyNestedObjects, boolean failOnDuplicates) {
    /** Options used when no config or explicit setting supplies a value. */
    public static final FormatOptions DEFAULT = new FormatOptions("=", false, true, false);

    /** Validates the assignment token. */
    public FormatOptions {
        if (!separator.equals("=") && !separator.equals(":")) {
            throw new IllegalArgumentException("separator: expected = or :");
        }
    }

    ww86.hocon_fmt.FormatOptions core() {
        return new ww86.hocon_fmt.FormatOptions(
            separator.equals(":") ? ww86.hocon_fmt.Separator.valueOf("Colon") : ww86.hocon_fmt.Separator.valueOf("Equals"),
            doubleIndent, simplifyNestedObjects, failOnDuplicates);
    }

    static FormatOptions of(ww86.hocon_fmt.FormatOptions options) {
        return new FormatOptions(options.separator().toString().equals("Colon") ? ":" : "=",
            options.doubleIndent(), options.simplifyNestedObjects(), options.failOnDuplicates());
    }
}
