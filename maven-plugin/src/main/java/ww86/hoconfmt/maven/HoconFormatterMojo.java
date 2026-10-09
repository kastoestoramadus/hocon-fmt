package ww86.hoconfmt.maven;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Parameter;
import org.codehaus.plexus.util.DirectoryScanner;
import ww86.hoconfmt.java.HoconFmt;

/** What both goals share: which files to look at, and what the formatter makes of each. */
abstract class HoconFormatterMojo extends AbstractMojo {

  @Parameter(defaultValue = "${project.basedir}", readonly = true, required = true)
  private File baseDirectory;

  /** Files to examine, as Ant-style patterns relative to the project directory. */
  @Parameter(defaultValue = "src/**/*.conf,src/**/*.hocon")
  private String[] includes;

  /** Files to leave out of those matched by {@code includes}, in the same form. */
  @Parameter
  private String[] excludes;

  @Parameter(property = "hocon-fmt.skip", defaultValue = "false")
  private boolean skip;

  @Parameter(property = "hocon-fmt.separator", alias = "separator")
  private String separator;

  @Parameter(property = "hocon-fmt.double-indent", alias = "double-indent")
  private String doubleIndent;

  @Parameter(property = "hocon-fmt.simplify-nested-objects", alias = "simplify-nested-objects")
  private String simplifyNestedObjects;

  @Parameter(property = "hocon-fmt.fail-on-duplicates", alias = "fail-on-duplicates")
  private String failOnDuplicates;

  /** A matching file, the path it is reported under, and what the formatter makes of it. */
  record Examined(Path file, String relativePath, Outcome outcome) {}

  @Override
  public final void execute() throws MojoExecutionException, MojoFailureException {
    if (skip) {
      getLog().info("HOCON formatting skipped.");
      return;
    }

    boolean duplicateFailure = false;
    var overrides = new java.util.HashMap<String, String>();
    if (separator != null) overrides.put("separator", separator);
    if (doubleIndent != null) overrides.put("double-indent", doubleIndent);
    if (simplifyNestedObjects != null) overrides.put("simplify-nested-objects", simplifyNestedObjects);
    if (failOnDuplicates != null) overrides.put("fail-on-duplicates", failOnDuplicates);
    var examined = new ArrayList<Examined>();
    for (String relativePath : matchingFiles()) {
      Path file = baseDirectory.toPath().resolve(relativePath);
      Outcome outcome;
      try {
        var inspection = HoconFmt.inspectFile(file, overrides, this instanceof FormatMojo);
        outcome = Outcome.of(inspection.verdict());
        inspection.report().findings().forEach(finding -> getLog().warn(relativePath + ": " + finding.warning()));
        inspection.report().failure().ifPresent(reason -> getLog().warn(relativePath + ": duplicate report could not run: " + reason));
        duplicateFailure |= inspection.failsOnDuplicates();
      } catch (IOException | IllegalArgumentException e) {
        throw new MojoExecutionException("Cannot process " + relativePath + ": " + e.getMessage(), e);
      }
      if (outcome instanceof Outcome.Refused refused) {
        getLog().warn("Leaving " + relativePath + " unchanged: " + refused.reason());
      }
      examined.add(new Examined(file, relativePath, outcome));
    }
    actOn(examined);
    if (duplicateFailure) throw new MojoFailureException("HOCON duplicate definitions found.");
  }

  /** Receives every matching file; the ones the formatter refused are already reported. */
  abstract void actOn(List<Examined> examined)
      throws MojoExecutionException, MojoFailureException;

  static String summary(List<Examined> examined, String needsFormattingAs) {
    return "HOCON files: %d %s, %d already formatted, %d refused."
        .formatted(
            count(examined, Outcome.NeedsFormatting.class),
            needsFormattingAs,
            count(examined, Outcome.AlreadyFormatted.class),
            count(examined, Outcome.Refused.class));
  }

  private static long count(List<Examined> examined, Class<? extends Outcome> kind) {
    return examined.stream().filter(file -> kind.isInstance(file.outcome())).count();
  }

  /** Sorted, so the log lists files in the same order on every run and every file system. */
  private List<String> matchingFiles() {
    var scanner = new DirectoryScanner();
    scanner.setBasedir(baseDirectory);
    scanner.setIncludes(includes);
    scanner.setExcludes(excludes);
    scanner.scan();
    return Arrays.stream(scanner.getIncludedFiles()).sorted().toList();
  }

}
