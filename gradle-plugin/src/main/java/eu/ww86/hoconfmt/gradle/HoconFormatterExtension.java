package eu.ww86.hoconfmt.gradle;

import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.provider.Property;

/** The {@code hoconFormatter { }} block of a build script. */
public abstract class HoconFormatterExtension {

    /** The HOCON files to format: every {@code *.conf} and {@code *.hocon} under {@code src} by default. */
    public abstract ConfigurableFileCollection getSource();
    public abstract Property<String> getSeparator();

    public abstract Property<Boolean> getDoubleIndent();

    public abstract Property<Boolean> getSimplifyNestedObjects();

    public abstract Property<Boolean> getFailOnDuplicates();

}
