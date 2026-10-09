package ww86.hoconfmt.java;

/**
 * A single read's verdict, original duplicate report, and effective repository options.
 * @param verdict the formatting decision
 * @param report findings from the source before writing
 * @param options repository values with explicit overrides applied
 */
public record Inspection(Verdict verdict, DuplicateReport report, FormatOptions options) {
    /** @return whether the effective policy makes these findings fail the run */
    public boolean failsOnDuplicates() {
        return options.failOnDuplicates() && !report.findings().isEmpty();
    }
}
