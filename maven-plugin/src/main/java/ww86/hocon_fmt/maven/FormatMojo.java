package ww86.hocon_fmt.maven;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import ww86.hocon_fmt.java.HoconFmt;
import ww86.hocon_fmt.java.Verdict;

/**
 * Rewrites the HOCON files that are not formatted. Files the formatter refuses are reported and
 * left untouched, without failing the build.
 */
// process-sources comes before process-resources, so the files copied into the build are the
// formatted ones.
@Mojo(name = "format", defaultPhase = LifecyclePhase.PROCESS_SOURCES, threadSafe = true)
public final class FormatMojo extends HoconFormatterMojo {

  @Override
  Verdict verdictFor(Path file) throws IOException {
    return HoconFmt.formatFile(file);
  }

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
