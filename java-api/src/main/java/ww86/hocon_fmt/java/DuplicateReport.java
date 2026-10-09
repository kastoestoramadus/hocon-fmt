package ww86.hocon_fmt.java;

import java.util.List;
import java.util.Optional;

/**
 * Original source findings, or a report failure that callers must warn about.
 * @param findings replaced definitions, in source order
 * @param failure reason the report could not run, if any
 */
public record DuplicateReport(List<Finding> findings, Optional<String> failure) {
    /** Keeps findings immutable for callers across threads and class loaders. */
    public DuplicateReport { findings = List.copyOf(findings); }

    /**
     * One definition made dead by a later definition.
     * @param keyPath rendered path, including array positions
     * @param earlierLine original definition's one-based line
     * @param laterLine replacing definition's one-based line
     */
    public record Finding(String keyPath, int earlierLine, int laterLine) {
        /** @return the finding in warning form */
        public String warning() {
            return keyPath + " defined again on line " + laterLine
                + "; definition on line " + earlierLine + " never takes effect";
        }
    }
}
