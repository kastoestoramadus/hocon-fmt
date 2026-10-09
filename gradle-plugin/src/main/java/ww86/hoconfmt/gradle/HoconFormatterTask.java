package ww86.hoconfmt.gradle;

import javax.inject.Inject;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Optional;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.IgnoreEmptyDirectories;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.SkipWhenEmpty;
import org.gradle.work.DisableCachingByDefault;
import org.gradle.workers.WorkerExecutor;

/** What both tasks share: the files, the formatter, and handing the work to it. */
@DisableCachingByDefault(because = "Each subclass decides.")
public abstract class HoconFormatterTask extends DefaultTask {

    @InputFiles
    @SkipWhenEmpty
    @IgnoreEmptyDirectories
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getSource();

    @Classpath
    public abstract ConfigurableFileCollection getFormatterClasspath();

    /** Where reported paths are relative to. */
    @Internal
    public abstract DirectoryProperty getProjectDirectory();

    @Input @Optional
    public abstract Property<String> getSeparator();

    @Input @Optional
    public abstract Property<Boolean> getDoubleIndent();

    @Input @Optional
    public abstract Property<Boolean> getSimplifyNestedObjects();

    @Input @Optional
    public abstract Property<Boolean> getFailOnDuplicates();

    /** Config files are inputs too: adding, removing or editing one invalidates a check. The walk
     * stops where the lookup stops — after the first directory holding .git — so a config the
     * formatter would never read cannot invalidate the check either.
     */
    @InputFiles
    @PathSensitive(PathSensitivity.ABSOLUTE)
    public org.gradle.api.provider.Provider<java.util.Set<java.io.File>> getConfigFiles() {
        return getSource().getElements().map(locations -> {
            var configs = new java.util.LinkedHashSet<java.io.File>();
            for (org.gradle.api.file.FileSystemLocation file : locations) {
                for (java.nio.file.Path dir = file.getAsFile().toPath().toAbsolutePath().getParent(); dir != null; dir = dir.getParent()) {
                    configs.add(dir.resolve(".hocon-fmt.conf").toFile());
                    if (java.nio.file.Files.exists(dir.resolve(".git"))) break;
                }
            }
            return configs;
        });
    }

    @Inject
    protected abstract WorkerExecutor getWorkerExecutor();

    /**
     * Runs {@link FormatAction} in a class loader holding only the formatter and its Scala library, so
     * they cannot clash with whatever else the buildscript classpath carries.
     */
    void examine(boolean checkOnly) {
        getWorkerExecutor()
                .classLoaderIsolation(spec -> spec.getClasspath().from(getFormatterClasspath()))
                .submit(FormatAction.class, parameters -> {
                    parameters.getFiles().from(getSource());
                    parameters.getProjectDirectory().set(getProjectDirectory());
                    parameters.getCheckOnly().set(checkOnly);
                    parameters.getSeparator().set(getSeparator());
                    parameters.getDoubleIndent().set(getDoubleIndent());
                    parameters.getSimplifyNestedObjects().set(getSimplifyNestedObjects());
                    parameters.getFailOnDuplicates().set(getFailOnDuplicates());
                });
        getWorkerExecutor().await();
    }
}
