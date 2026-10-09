package ww86.hocon_fmt.java;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginOptionsTest {
    @TempDir Path dir;

    @Test void repositoryOptionsAndOriginalDuplicates() throws Exception {
        Files.writeString(dir.resolve(".hocon-fmt.conf"), "separator = \":\"\nfail-on-duplicates = true\n");
        Path file = Files.writeString(dir.resolve("app.conf"), "a=1\na=2\nb {c=3}\n");
        Inspection result = HoconFmt.inspectFile(file, Map.of(), true);
        assertEquals("a: 2\nb.c: 3\n", Files.readString(file));
        assertTrue(result.options().failOnDuplicates());
        assertEquals(1, result.report().findings().size());
        assertEquals("a", result.report().findings().get(0).keyPath());
        assertEquals(1, result.report().findings().get(0).earlierLine());
        assertEquals(2, result.report().findings().get(0).laterLine());
        assertEquals("=", HoconFmt.optionsFor(file, Map.of("separator", "=", "fail-on-duplicates", "false")).separator());
        assertFalse(HoconFmt.optionsFor(file, Map.of("fail-on-duplicates", "false")).failOnDuplicates());
    }

    @Test void nearestConfigAndGitFileBoundary() throws Exception {
        Files.writeString(dir.resolve(".hocon-fmt.conf"), "separator = \":\"\n");
        Path nested = Files.createDirectory(dir.resolve("nested"));
        Path file = Files.writeString(nested.resolve("app.conf"), "a=1");
        Files.writeString(nested.resolve(".git"), "gitdir: somewhere");
        assertEquals("=", HoconFmt.optionsFor(file, Map.of()).separator());
        Files.writeString(nested.resolve(".hocon-fmt.conf"), "separator = \":\"\n");
        assertEquals(":", HoconFmt.optionsFor(file, Map.of()).separator());
    }

    @Test void invalidConfigIsAnErrorEvenWhenOverridden() throws Exception {
        Path config = Files.writeString(dir.resolve(".hocon-fmt.conf"), "unknown = true");
        Path file = Files.writeString(dir.resolve("app.conf"), "a=1");
        Exception error = assertThrows(IllegalArgumentException.class,
            () -> HoconFmt.optionsFor(file, Map.of("separator", "=")));
        assertTrue(error.getMessage().contains(config.toString()));
        Files.write(config, new byte[]{(byte) 0xff});
        var readError = assertThrows(java.io.IOException.class, () -> HoconFmt.optionsFor(file, Map.of()));
        assertTrue(readError.getMessage().contains(config.toString()), readError.getMessage());
    }

    @Test void optionsAndReportUseJdkRecords() {
        FormatOptions options = new FormatOptions(":", true, false, false);
        assertInstanceOf(Verdict.NeedsFormatting.class, HoconFmt.check("a=1", "app.conf", options));
        DuplicateReport report = HoconFmt.report("a=1\na=2\n", "app.conf");
        assertEquals(1, report.findings().size());
        assertTrue(report.failure().isEmpty());
        assertFalse(HoconFmt.report("a=${", "app.conf").failure().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> report.findings().clear());
    }
}
