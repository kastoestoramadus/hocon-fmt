package ww86.hoconfmt.gradle;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.Property;
import org.gradle.workers.WorkAction;
import org.gradle.workers.WorkParameters;
import ww86.hoconfmt.java.HoconFmt;
import ww86.hoconfmt.java.Verdict;

/** Formats or checks the files, inside the isolated class loader that holds the formatter. */
public abstract class FormatAction implements WorkAction<FormatAction.Parameters> {

    public interface Parameters extends WorkParameters {
        ConfigurableFileCollection getFiles();

        DirectoryProperty getProjectDirectory();

        Property<Boolean> getCheckOnly();
        Property<String> getSeparator();
        Property<Boolean> getDoubleIndent();
        Property<Boolean> getSimplifyNestedObjects();
        Property<Boolean> getFailOnDuplicates();
    }

    /** What the formatter makes of one file's content. */
    sealed interface Outcome {
        record AlreadyFormatted() implements Outcome {}

        record NeedsFormatting(String formatted) implements Outcome {}

        record Refused(String reason) implements Outcome {}

        /** Both tasks report the verdict returned by the Java API's file operation. */
        static Outcome of(Verdict verdict) {
            // The plugin targets Java 17, where a pattern switch does not compile; the sealed
            // Verdict is read with instanceof and a verdict this code does not know fails loudly.
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

    record Examined(Path file, String path, Outcome outcome) {}

    static final Logger LOGGER = Logging.getLogger(FormatAction.class);

    @Override
    public void execute() {
        Path root = getParameters().getProjectDirectory().get().getAsFile().toPath();
        var overrides = new java.util.HashMap<String, String>();
        if (getParameters().getSeparator().isPresent()) overrides.put("separator", getParameters().getSeparator().get().toString());
        if (getParameters().getDoubleIndent().isPresent()) overrides.put("double-indent", getParameters().getDoubleIndent().get().toString());
        if (getParameters().getSimplifyNestedObjects().isPresent()) overrides.put("simplify-nested-objects", getParameters().getSimplifyNestedObjects().get().toString());
        if (getParameters().getFailOnDuplicates().isPresent()) overrides.put("fail-on-duplicates", getParameters().getFailOnDuplicates().get().toString());
        var duplicateFailure = new java.util.concurrent.atomic.AtomicBoolean(false);
        List<Examined> examined = getParameters().getFiles().getFiles().stream()
                .map(File::toPath)
                .sorted(Comparator.naturalOrder())
                .map(file -> {
                    String path = root.relativize(file).toString();
                    try {
                        var inspection = HoconFmt.inspectFile(file, overrides, !getParameters().getCheckOnly().get());
                        inspection.report().findings().forEach(finding -> LOGGER.warn("{}: {}", path, finding.warning()));
                        inspection.report().failure().ifPresent(reason -> LOGGER.warn("{}: duplicate report could not run: {}", path, reason));
                        if (inspection.failsOnDuplicates()) duplicateFailure.set(true);
                        return new Examined(file, path, Outcome.of(inspection.verdict()));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                })
                .toList();

        for (Examined file : examined) {
            if (file.outcome() instanceof Outcome.Refused refused) {
                LOGGER.warn("Leaving {} unchanged: {}", file.path(), refused.reason());
            }
        }
        if (getParameters().getCheckOnly().get()) {
            check(examined);
        } else {
            format(examined);
        }
        if (duplicateFailure.get()) throw new GradleException("HOCON duplicate definitions found.");
    }

    static void format(List<Examined> examined) {
        for (Examined file : examined) {
            if (file.outcome() instanceof Outcome.NeedsFormatting) {
                LOGGER.lifecycle("Formatted {}", file.path());
            }
        }
        LOGGER.lifecycle(summary(examined, "formatted"));
    }

    static void check(List<Examined> examined) {
        List<String> unformatted = examined.stream()
                .filter(file -> file.outcome() instanceof Outcome.NeedsFormatting)
                .map(Examined::path)
                .toList();
        LOGGER.lifecycle(summary(examined, "not formatted"));
        if (!unformatted.isEmpty()) {
            throw new GradleException("HOCON files are not formatted:\n  " + String.join("\n  ", unformatted)
                    + "\nRun hoconFormat to fix them.");
        }
    }

    static String summary(List<Examined> examined, String needingFormatAre) {
        return "HOCON files: %d %s, %d already formatted, %d refused."
                .formatted(
                        count(examined, Outcome.NeedsFormatting.class),
                        needingFormatAre,
                        count(examined, Outcome.AlreadyFormatted.class),
                        count(examined, Outcome.Refused.class));
    }

    static long count(List<Examined> examined, Class<? extends Outcome> kind) {
        return examined.stream().filter(file -> kind.isInstance(file.outcome())).count();
    }

}
