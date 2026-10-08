package ww86.hocon_fmt.java;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ww86.hocon_fmt.FormatRefusedException;

class HoconFmtTest {

    static final String UNFORMATTED = "a = 1\n";
    static final String FORMATTED = "a: 1\n";
    static final String NOT_HOCON = "server {\n    listen 80;\n}\n";

    @Test
    void checkMarksUnformattedText() {
        Verdict verdict = HoconFmt.check(UNFORMATTED);
        Verdict.NeedsFormatting needed = assertInstanceOf(Verdict.NeedsFormatting.class, verdict);
        assertEquals(FORMATTED, needed.formatted());
    }

    @Test
    void checkRecognizesFormattedText() {
        assertEquals(new Verdict.AlreadyFormatted(), HoconFmt.check(FORMATTED));
    }

    @Test
    void checkRefusesBytesThatAreNotUtf8() {
        Verdict verdict = HoconFmt.check(new byte[] {(byte) 0xff, '\n'});
        Verdict.Refused refused = assertInstanceOf(Verdict.Refused.class, verdict);
        assertEquals(RefusalKind.NotUtf8, refused.kind());
        assertEquals("not valid UTF-8", refused.reason());
    }

    @Test
    void checkCarriesTheRefusalKindAndReason() {
        Verdict verdict = HoconFmt.check(NOT_HOCON);
        Verdict.Refused refused = assertInstanceOf(Verdict.Refused.class, verdict);
        assertEquals(RefusalKind.NotHocon, refused.kind());
        assertTrue(refused.reason().startsWith("not valid HOCON: "));
    }

    @Test
    void everyCoreRefusalCaseHasAKind() {
        // The core's companion holds one public static final field per case (typed with the case's
        // own companion), so a case the mirror does not know shows up here as a list mismatch.
        // Field order is unspecified, so both sides are sorted.
        List<String> coreCases = caseFields(ww86.hocon_fmt.Refusal$.class);
        List<String> kinds = Stream.of(RefusalKind.values()).map(Enum::name).sorted().toList();
        assertEquals(coreCases, kinds);
    }

    @Test
    void everyCoreVerdictCaseIsMirrored() {
        List<String> coreCases = caseFields(ww86.hocon_fmt.Verdict$.class);
        assertEquals(List.of("AlreadyFormatted", "NeedsFormatting", "Refused"), coreCases);
    }

    static List<String> caseFields(Class<?> companion) {
        return Stream.of(companion.getDeclaredFields())
                .filter(field -> Modifier.isPublic(field.getModifiers())
                        && Modifier.isStatic(field.getModifiers())
                        && Modifier.isFinal(field.getModifiers())
                        && !field.getName().equals("MODULE$"))
                .map(Field::getName)
                .sorted()
                .toList();
    }

    @Test
    void checkFileReadsBytes(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("app.conf");
        Files.writeString(file, UNFORMATTED);
        Verdict verdict = HoconFmt.checkFile(file);
        assertInstanceOf(Verdict.NeedsFormatting.class, verdict);
    }

    @Test
    void formatFileRewritesOnlyWhatNeedsIt(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("app.conf");
        Files.writeString(file, UNFORMATTED);
        assertEquals(new Verdict.NeedsFormatting(FORMATTED), HoconFmt.formatFile(file));
        assertEquals(FORMATTED, Files.readString(file));
        // Already formatted: another pass must not touch the file at all.
        long stamp = Files.getLastModifiedTime(file).toMillis();
        assertEquals(new Verdict.AlreadyFormatted(), HoconFmt.formatFile(file));
        assertEquals(stamp, Files.getLastModifiedTime(file).toMillis());
    }

    @Test
    void formatFileLeavesRefusedFilesAlone(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("nginx.conf");
        byte[] original = NOT_HOCON.getBytes(StandardCharsets.UTF_8);
        Files.write(file, original);
        Verdict verdict = HoconFmt.formatFile(file);
        Verdict.Refused refused = assertInstanceOf(Verdict.Refused.class, verdict);
        assertEquals(RefusalKind.NotHocon, refused.kind());
        assertArrayEquals(original, Files.readAllBytes(file));
    }

    @Test
    void formatOrThrowReturnsTheFormattedText() throws FormatRefusedException {
        assertEquals(FORMATTED, HoconFmt.formatOrThrow(UNFORMATTED));
        // Already formatted text comes back as it is.
        assertEquals(FORMATTED, HoconFmt.formatOrThrow(FORMATTED));
    }

    @Test
    void formatOrThrowRaisesTheCoreException() {
        FormatRefusedException exception =
                assertThrows(FormatRefusedException.class, () -> HoconFmt.formatOrThrow(NOT_HOCON));
        assertTrue(exception.getMessage().startsWith("not valid HOCON: "));
        assertInstanceOf(ww86.hocon_fmt.Refusal.NotHocon.class, exception.refusal());
    }
}
