package eu.ww86.hoconfmt.maven;

import java.util.List;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;

/**
 * Rewrites the HOCON files that are not formatted. Files the formatter refuses are reported and
 * left untouched, without failing the build.
 */
// process-sources comes before process-resources, so the files copied into the build are the
// formatted ones.
@Mojo(name = "format", defaultPhase = LifecyclePhase.PROCESS_SOURCES, threadSafe = true)
public final class FormatMojo extends HoconFormatterMojo {

  @Override
  void actOn(List<Examined> examined) {
    for (Examined file : examined) {
      if (file.outcome() instanceof Outcome.NeedsFormatting) {
        getLog().info("Formatted " + file.relativePath());
      }
    }
    getLog().info(summary(examined, "reformatted"));
  }
}
