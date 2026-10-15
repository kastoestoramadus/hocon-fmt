package eu.ww86.hoconfmt.java;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The write flag {@link HoconFmt#inspectFile} takes from the sbt plugin: false is the check mode,
 * which must leave a file byte for byte and keep its modification time whatever the verdict is;
 * true rewrites a file that needs formatting, and still leaves a refused file alone.
 */
class InspectFileWriteFlagTest {
    @TempDir Path directory;
    static final String BEFORE = "a   =   1\n";
    static final String AFTER = "a = 1\n";
    static final FileTime OLD = FileTime.from(Instant.parse("2000-01-01T00:00:00Z"));

    private Path unformatted() throws IOException {
        Path file = directory.resolve("app.conf");
        Files.writeString(file, BEFORE);
        return file;
    }

    /** Bytes that are not valid UTF-8, which the formatter refuses rather than rewrites. */
    private Path refused() throws IOException {
        Path file = directory.resolve("strict.conf");
        Files.write(file, new byte[] {(byte) 0xff, 10});
        return file;
    }

    @Test void withoutTheFlagAnUnformattedFileIsReportedButUntouched() throws IOException {
        Path file = unformatted();
        Files.setLastModifiedTime(file, OLD);
        assertInstanceOf(Verdict.NeedsFormatting.class, HoconFmt.inspectFile(file, Map.of(), false).verdict());
        assertEquals(BEFORE, Files.readString(file));
        assertEquals(OLD, Files.getLastModifiedTime(file));
    }

    @Test void withoutTheFlagARefusedFileIsUntouched() throws IOException {
        Path file = refused();
        Files.setLastModifiedTime(file, OLD);
        assertInstanceOf(Verdict.Refused.class, HoconFmt.inspectFile(file, Map.of(), false).verdict());
        assertArrayEquals(new byte[] {(byte) 0xff, 10}, Files.readAllBytes(file));
        assertEquals(OLD, Files.getLastModifiedTime(file));
    }

    @Test void theFlagRewritesAnUnformattedFile() throws IOException {
        Path file = unformatted();
        Files.setLastModifiedTime(file, OLD);
        assertInstanceOf(Verdict.NeedsFormatting.class, HoconFmt.inspectFile(file, Map.of(), true).verdict());
        assertEquals(AFTER, Files.readString(file));
        assertTrue(Files.getLastModifiedTime(file).compareTo(OLD) > 0, "must be rewritten, not only reported");
    }

    @Test void theFlagStillLeavesARefusedFileUntouched() throws IOException {
        Path file = refused();
        Files.setLastModifiedTime(file, OLD);
        assertInstanceOf(Verdict.Refused.class, HoconFmt.inspectFile(file, Map.of(), true).verdict());
        assertArrayEquals(new byte[] {(byte) 0xff, 10}, Files.readAllBytes(file));
        assertEquals(OLD, Files.getLastModifiedTime(file));
    }
}
