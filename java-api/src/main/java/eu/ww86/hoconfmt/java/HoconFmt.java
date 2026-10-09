package eu.ww86.hoconfmt.java;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import eu.ww86.hoconfmt.FormatRefusedException;

/**
 * Static entry points for Java and Kotlin callers. Every method returns a {@link Verdict} —
 * including the refusals, which are values here rather than exceptions — and never accepts or
 * returns null.
 *
 * <p>A name is the name the caller knows the text or file by: a refusal reports it as the place a
 * parse tripped, and a name promising another format (a {@code .json} or {@code .properties} file)
 * is refused rather than rewritten as HOCON.
 */
public final class HoconFmt {

    private HoconFmt() {}

    /**
     * What the formatter makes of HOCON text.
     *
     * @param text the HOCON to judge
     * @return the verdict: already formatted, needs formatting, or refused
     */
    public static Verdict check(String text) {
        return mirror(eu.ww86.hoconfmt.Verdict.of(text));
    }

    /**
     * What the formatter makes of HOCON text the caller knows a name for.
     *
     * @param text the HOCON to judge
     * @param name the name the caller knows the text by
     * @return the verdict: already formatted, needs formatting, or refused
     */
    public static Verdict check(String text, String name) {
        return mirror(eu.ww86.hoconfmt.Verdict.of(text, name));
    }

    /**
     * What the formatter makes of UTF-8 bytes; bytes that are not valid UTF-8 are refused.
     *
     * @param content the UTF-8 bytes of the HOCON to judge
     * @return the verdict: already formatted, needs formatting, or refused
     */
    public static Verdict check(byte[] content) {
        return mirror(eu.ww86.hoconfmt.Verdict.of(content));
    }

    /**
     * What the formatter makes of UTF-8 bytes the caller knows a name for.
     *
     * @param content the UTF-8 bytes of the HOCON to judge
     * @param name the name the caller knows the bytes by
     * @return the verdict: already formatted, needs formatting, or refused
     */
    public static Verdict check(byte[] content, String name) {
        return mirror(eu.ww86.hoconfmt.Verdict.of(content, name));
    }

    /**
     * Reads the file as UTF-8 bytes and judges its content, without touching it.
     *
     * @param file the file to read
     * @return the verdict: already formatted, needs formatting, or refused
     * @throws IOException when the file cannot be read
     */
    public static Verdict checkFile(Path file) throws IOException {
        return check(Files.readAllBytes(file), file.toString());
    }

    /**
     * Rewrites the file when the formatted text differs, and leaves it byte for byte when it is
     * already formatted or refused, including its modification time. Symlinks are resolved first.
     * Complete output is staged beside the target and atomically renamed only when the target
     * and directory are writable and the staged owner, group and all mode bits are verified.
     * Otherwise it writes in place, retaining the inode but losing crash-atomicity. Other hard
     * links keep old content after replacement and share new content after an in-place write.
     * Calls on the same file must be serialised.
     *
     * @param file the file to read, and to rewrite when formatting changes it
     * @return the verdict, so a caller can tell a write from a pass without rereading
     * @throws IOException when the file cannot be read or written
     */
    public static Verdict formatFile(Path file) throws IOException {
        Path target = file.toRealPath();
        Verdict verdict = check(Files.readAllBytes(target), target.toString());
        if (verdict instanceof Verdict.NeedsFormatting needed) {
            AtomicFile.write(target, needed.formatted());
        }
        return verdict;
    }

    /**
     * The formatted text, or the exception the JVM integrations raise. Already formatted text
     * comes back as it is.
     *
     * @param text the HOCON to format
     * @return the text the input should become; the input itself when already formatted
     * @throws FormatRefusedException when the input must be left alone; the message says why
     */
    public static String formatOrThrow(String text) throws FormatRefusedException {
        return textOf(eu.ww86.hoconfmt.Verdict.of(text), text);
    }

    /**
     * As {@link #formatOrThrow(String)}, with the name the caller knows the text by.
     *
     * @param text the HOCON to format
     * @param name the name the caller knows the text by
     * @return the text the input should become; the input itself when already formatted
     * @throws FormatRefusedException when the input must be left alone; the message says why
     */
    public static String formatOrThrow(String text, String name) throws FormatRefusedException {
        return textOf(eu.ww86.hoconfmt.Verdict.of(text, name), text);
    }

    /**
     * Judges text with explicit options.
     * @param text source or config text
     * @param name origin used in diagnostics
     * @param options explicit renderer options
     * @return the result of the operation
     */
    public static Verdict check(String text, String name, FormatOptions options) {
        return mirror(eu.ww86.hoconfmt.Verdict.of(text, name, options.core()));
    }

    /**
     * Judges original bytes with explicit options.
     * @param content original UTF-8 bytes
     * @param name origin used in diagnostics
     * @param options explicit renderer options
     * @return the result of the operation
     */
    public static Verdict check(byte[] content, String name, FormatOptions options) {
        return mirror(eu.ww86.hoconfmt.Verdict.of(content, name, options.core()));
    }

    /**
     * Parses repository options with the core's strict parser.
     * @param text source or config text
     * @param name origin used in diagnostics
     * @return the result of the operation
     */
    public static FormatOptions parseOptions(String text, String name) {
        var parsed = eu.ww86.hoconfmt.FormatOptions.parse(text, name);
        if (parsed.isLeft()) {
            throw new IllegalArgumentException(parsed.swap().toOption().get());
        }
        return FormatOptions.of(parsed.toOption().get());
    }

    /**
     * Finds the nearest config up to and including the first .git directory or file.
     * @param file source file
     * @return the result of the operation
     */
    public static java.util.Optional<Path> discoverConfig(Path file) {
        for (Path dir = file.toAbsolutePath().normalize().getParent(); dir != null; dir = dir.getParent()) {
            Path config = dir.resolve(".hocon-fmt.conf");
            var decision = eu.ww86.hoconfmt.ConfigLookup.decide(Files.exists(config), Files.exists(dir.resolve(".git")));
            if (decision.found()) return java.util.Optional.of(config);
            if (decision.stops()) return java.util.Optional.empty();
        }
        return java.util.Optional.empty();
    }

    /**
     * Reads config strictly as UTF-8, then applies only explicitly supplied CLI-named keys.
     * @param file source file
     * @param overrides explicit CLI-named keys and values
     * @return the result of the operation
     * @throws IOException when file access or UTF-8 decoding fails
     */
    public static FormatOptions optionsFor(Path file, java.util.Map<String, String> overrides) throws IOException {
        var config = discoverConfig(file);
        FormatOptions base = config.isPresent()
            ? readOptions(config.get()) : FormatOptions.DEFAULT;
        // Validate every supplied key and value through the same parser, including overridden values.
        String text = overrides.entrySet().stream()
            .map(entry -> entry.getKey() + " = \"" + hoconString(entry.getValue()) + "\"")
            .collect(java.util.stream.Collectors.joining("\n"));
        FormatOptions explicit = parseOptions(text, "plugin settings");
        return new FormatOptions(
            overrides.containsKey("separator") ? explicit.separator() : base.separator(),
            overrides.containsKey("double-indent") ? explicit.doubleIndent() : base.doubleIndent(),
            overrides.containsKey("simplify-nested-objects") ? explicit.simplifyNestedObjects() : base.simplifyNestedObjects(),
            overrides.containsKey("fail-on-duplicates") ? explicit.failOnDuplicates() : base.failOnDuplicates());
    }

    /**
     * A value as a HOCON string literal: a quote, a backslash or a control character in a value
     * must not change the document the settings are read from. A value that does break it would
     * be reported as a position in a synthetic config, not as the setting that carries it.
     */
    private static String hoconString(String value) {
        var escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private static FormatOptions readOptions(Path config) throws IOException {
        try {
            return parseOptions(Files.readString(config), config.toString());
        } catch (IOException e) {
            throw new IOException("cannot read " + config + ": " + e.getMessage(), e);
        }
    }

    /**
     * Reports definitions from the source, before formatting can remove them.
     * @param text source or config text
     * @param name origin used in diagnostics
     * @return the result of the operation
     */
    public static DuplicateReport report(String text, String name) {
        var result = eu.ww86.hoconfmt.DuplicateReport.findings(text, name);
        if (result.isLeft()) {
            return new DuplicateReport(java.util.List.of(), java.util.Optional.of(result.swap().toOption().get().reason()));
        }
        var findings = new java.util.ArrayList<DuplicateReport.Finding>();
        var iterator = result.toOption().get().iterator();
        while (iterator.hasNext()) {
            var finding = iterator.next();
            if (finding instanceof eu.ww86.hoconfmt.Finding.KeyDefinedAgain again) {
                findings.add(new DuplicateReport.Finding(again.keyPath().rendered(), again.earlierLine(), again.laterLine()));
            } else {
                throw new IllegalStateException("unknown finding: " + finding);
            }
        }
        return new DuplicateReport(findings, java.util.Optional.empty());
    }

    /**
     * Inspects one read; optionally writes using the identity-preserving file operation.
     * @param file source file
     * @param overrides explicit CLI-named keys and values
     * @param write whether to write a NeedsFormatting verdict
     * @return the result of the operation
     * @throws IOException when file access or UTF-8 decoding fails
     */
    public static Inspection inspectFile(Path file, java.util.Map<String, String> overrides, boolean write) throws IOException {
        FormatOptions options = optionsFor(file, overrides);
        Path target = file.toRealPath();
        byte[] content = Files.readAllBytes(target);
        Verdict verdict = check(content, file.toString(), options);
        DuplicateReport report;
        try {
            String text = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                .decode(java.nio.ByteBuffer.wrap(content)).toString();
            report = verdict instanceof Verdict.Refused refused && refused.kind() == RefusalKind.OtherFormat
                ? new DuplicateReport(java.util.List.of(), java.util.Optional.empty()) : report(text, file.toString());
        } catch (java.nio.charset.CharacterCodingException e) {
            report = new DuplicateReport(java.util.List.of(), java.util.Optional.empty());
        }
        if (write && verdict instanceof Verdict.NeedsFormatting needed) {
            AtomicFile.write(target, needed.formatted());
        }
        return new Inspection(verdict, report, options);
    }

    /**
     * Checks a file with explicit options, without repository lookup.
     * @param file source file
     * @param options explicit renderer options
     * @return the result of the operation
     * @throws IOException when file access or UTF-8 decoding fails
     */
    public static Verdict checkFile(Path file, FormatOptions options) throws IOException {
        return check(Files.readAllBytes(file), file.toString(), options);
    }

    /**
     * Formats a file with explicit options, without repository lookup.
     * @param file source file
     * @param options explicit renderer options
     * @return the result of the operation
     * @throws IOException when file access or UTF-8 decoding fails
     */
    public static Verdict formatFile(Path file, FormatOptions options) throws IOException {
        Path target = file.toRealPath();
        Verdict verdict = check(Files.readAllBytes(target), target.toString(), options);
        if (verdict instanceof Verdict.NeedsFormatting needed) AtomicFile.write(target, needed.formatted());
        return verdict;
    }

    private static String textOf(eu.ww86.hoconfmt.Verdict verdict, String text) throws FormatRefusedException {
        if (verdict instanceof eu.ww86.hoconfmt.Verdict.NeedsFormatting needed) {
            return needed.formatted();
        }
        if (verdict instanceof eu.ww86.hoconfmt.Verdict.Refused refused) {
            throw new FormatRefusedException(refused.refusal());
        }
        return text;
    }

    /**
     * The core's Scala 3 enum has no JVM-level type for its parameterless case and no
     * {@code PermittedSubclasses}, so the named case classes are read with {@code instanceof} and
     * the singleton by the case's own name — pinned by the parity test. A verdict the mirror does
     * not know fails loudly instead of passing as one of the three.
     */
    private static Verdict mirror(eu.ww86.hoconfmt.Verdict verdict) {
        if (verdict instanceof eu.ww86.hoconfmt.Verdict.NeedsFormatting needed) {
            return new Verdict.NeedsFormatting(needed.formatted());
        }
        if (verdict instanceof eu.ww86.hoconfmt.Verdict.Refused refused) {
            eu.ww86.hoconfmt.Refusal refusal = refused.refusal();
            return new Verdict.Refused(RefusalKind.of(refusal), refusal.reason());
        }
        if ("AlreadyFormatted".equals(verdict.productPrefix())) {
            return new Verdict.AlreadyFormatted();
        }
        throw new IllegalStateException("the core grew a verdict the mirror does not know: "
                + verdict.productPrefix());
    }
}
