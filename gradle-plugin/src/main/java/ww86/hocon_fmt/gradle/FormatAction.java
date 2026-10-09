package ww86.hocon_fmt.gradle;

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
import ww86.hocon_fmt.java.HoconFmt;
import ww86.hocon_fmt.java.Verdict;

/** Formats or checks the files, inside the isolated class loader that holds the formatter. */
public abstract class FormatAction implements WorkAction<FormatAction.Parameters> {

    public interface Parameters extends WorkParameters {
        ConfigurableFileCollection getFiles();

        DirectoryProperty getProjectDirectory();

        Property<Boolean> getCheckOnly();
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
        List<Examined> examined = getParameters().getFiles().getFiles().stream()
                .map(File::toPath)
                .sorted(Comparator.naturalOrder())
                .map(file -> {
                    String path = root.relativize(file).toString();
                    return new Examined(file, path, Outcome.of(examine(file, getParameters().getCheckOnly().get())));
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

    static Verdict examine(Path file, boolean checkOnly) {
        try {
            return checkOnly ? HoconFmt.checkFile(file) : HoconFmt.formatFile(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
