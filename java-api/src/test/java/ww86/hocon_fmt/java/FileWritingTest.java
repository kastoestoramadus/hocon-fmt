package ww86.hocon_fmt.java;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileWritingTest {
    @TempDir Path directory;
    static final String BEFORE = "a   =   1\n";
    static final String AFTER = "a = 1\n";

    Path input() throws IOException {
        return Files.writeString(directory.resolve("app.conf"), BEFORE);
    }

    void unix() {
        assumeTrue(directory.getFileSystem().supportedFileAttributeViews().contains("unix"));
    }

    @Test void replacementKeepsOwnerGroupAndEveryModeBit() throws IOException {
        unix();
        Path file = input();
        Files.setAttribute(file, "unix:mode", 07750);
        var identity = Files.readAttributes(file, "unix:uid,gid,mode");
        assertEquals(07750, ((Integer) identity.get("mode")) & 07777);
        HoconFmt.formatFile(file);
        assertEquals(AFTER, Files.readString(file));
        assertEquals(identity, Files.readAttributes(file, "unix:uid,gid,mode"));
    }

    @Test void symlinkSurvivesAndItsTargetIsReplaced() throws IOException {
        unix();
        Path target = input();
        Path link = Files.createSymbolicLink(directory.resolve("alias.conf"), target.getFileName());
        Path other = Files.createLink(directory.resolve("other.conf"), target);
        HoconFmt.formatFile(link);
        assertTrue(Files.isSymbolicLink(link));
        assertEquals(target.getFileName(), Files.readSymbolicLink(link));
        assertEquals(AFTER, Files.readString(target));
        assertEquals(BEFORE, Files.readString(other));
    }

    @Test void atomicReplacementLeavesOtherHardLinksOnOldContent() throws IOException {
        unix();
        Path target = input();
        Path other = Files.createLink(directory.resolve("other.conf"), target);
        HoconFmt.formatFile(target);
        assertEquals(AFTER, Files.readString(target));
        assertEquals(BEFORE, Files.readString(other));
        assertFalse(Files.isSameFile(target, other));
        try (var files = Files.list(directory)) {
            assertEquals(2, files.count(), "staging must be cleaned up");
        }
    }

    @Test void unwritableDirectoryWritesInPlaceAndUpdatesHardLinks() throws IOException {
        unix();
        Path target = input();
        Path other = Files.createLink(directory.resolve("other.conf"), target);
        int mode = (Integer) Files.getAttribute(directory, "unix:mode");
        try {
            Files.setAttribute(directory, "unix:mode", 0500);
            assumeTrue(!Files.isWritable(directory), "requires an unprivileged process");
            HoconFmt.formatFile(target);
            assertEquals(AFTER, Files.readString(other));
            assertTrue(Files.isSameFile(target, other));
        } finally {
            Files.setAttribute(directory, "unix:mode", mode);
        }
    }

    @Test void refusedBytesAndMtimeAreUntouched() throws IOException {
        Path target = input();
        byte[] original = {(byte) 0xff, 10};
        Files.write(target, original);
        FileTime old = FileTime.from(Instant.parse("2000-01-01T00:00:00Z"));
        Files.setLastModifiedTime(target, old);
        assertInstanceOf(Verdict.Refused.class, HoconFmt.formatFile(target));
        assertArrayEquals(original, Files.readAllBytes(target));
        assertEquals(old, Files.getLastModifiedTime(target));
    }

    @Test void readOnlyFileCannotBeReplacedViaWritableDirectory() throws IOException {
        unix();
        Path target = input();
        FileTime old = FileTime.from(Instant.parse("2000-01-01T00:00:00Z"));
        Files.setLastModifiedTime(target, old);
        Files.setAttribute(target, "unix:mode", 0400);
        try {
            assumeTrue(!Files.isWritable(target));
            assertThrows(IOException.class, () -> HoconFmt.formatFile(target));
            assertEquals(BEFORE, Files.readString(target));
            assertEquals(old, Files.getLastModifiedTime(target));
        } finally {
            Files.setAttribute(target, "unix:mode", 0600);
        }
    }
}
