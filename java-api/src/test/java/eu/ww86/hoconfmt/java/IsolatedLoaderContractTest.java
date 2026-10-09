package eu.ww86.hoconfmt.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The contract the sbt 1.x plugin builds on: this artifact, as sbt publishes it, works when loaded
 * apart from its consumer — a {@link URLClassLoader} over the platform loader, holding the
 * java-api jar, the core, its Scala library and sconfig, and nothing reachable by name. That is
 * the shape of {@code IsolatedFormatter.using} in the sbt plugin, and it carries the reflective
 * promise the sbt plugin requires: the entry point must answer
 * reflectively, the verdict's record accessors must read across the loader, and the refusal kind
 * must come back as an enum whose {@code name()} the caller compares. The two loaders hold
 * distinct classes under equal names, so what crosses the boundary is values, never types.
 *
 * <p>The jar under test comes from Maven Local, where {@code sbt javaApi/publishM2} puts it: the
 * published packaging is the contract, not the Gradle build's own classes. Without it the suite
 * fails with the command that fills the gap.
 */
class IsolatedLoaderContractTest {

    /** A call into the isolated loader; reflection reports failures as exceptions. */
    @FunctionalInterface
    interface Loaded {
        void use(ClassLoader loader) throws Exception;
    }

    private static void withIsolatedLoader(Loaded loaded) throws Exception {
        URLClassLoader loader = new URLClassLoader(classpath(), ClassLoader.getPlatformClassLoader());
        try {
            loaded.use(loader);
        } finally {
            loader.close();
        }
    }

    private static URL[] classpath() throws Exception {
        return new URL[] {
            publishedJar(),
            codeSource("the core", eu.ww86.hoconfmt.Verdict.class),
            codeSource("the Scala library", scala.Predef$.class),
            codeSource("sconfig", org.ekrich.config.ConfigFactory.class),
            codeSource("jspecify", org.jspecify.annotations.NullMarked.class),
        };
    }

    private static URL codeSource(String what, Class<?> known) {
        URL url = known.getProtectionDomain().getCodeSource().getLocation();
        if (url == null) {
            fail(what + " has no code source to load across the boundary");
        }
        return url;
    }

    /**
     * This build's {@code hocon-fmt-java-api} jar in Maven Local, not its sources or javadoc. The
     * Gradle build passes the project version, and exactly that jar is required: a newer or older
     * one left in the repository must not stand in for the packaging under test.
     */
    private static URL publishedJar() {
        String version = System.getProperty("hocon-fmt-java-api.version");
        if (version == null || version.isBlank()) {
            fail("no hocon-fmt-java-api version — run the suite through the Gradle build, which "
                    + "passes it, and run `sbt javaApi/publishM2` to publish the API first");
        }
        Path repository = Path.of(System.getProperty("maven.repo.local",
                Path.of(System.getProperty("user.home"), ".m2", "repository").toString()));
        Path jar = repository.resolve(Path.of("eu", "ww86",
                "hocon-fmt-java-api", version, "hocon-fmt-java-api-" + version + ".jar"));
        if (!Files.isRegularFile(jar)) {
            fail("no " + jar + " — run `sbt javaApi/publishM2` to publish the API first");
        }
        return toUrl(jar);
    }

    private static URL toUrl(Path jar) {
        try {
            return jar.toUri().toURL();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Class<?> hoconFmt(ClassLoader loader) throws ClassNotFoundException {
        return Class.forName("eu.ww86.hoconfmt.java.HoconFmt", true, loader);
    }

    private static Object check(ClassLoader loader, byte[] content) throws Exception {
        Method entry = hoconFmt(loader).getMethod("check", byte[].class);
        return entry.invoke(null, (Object) content);
    }

    private static Object read(Object verdict, String accessor) throws Exception {
        return verdict.getClass().getMethod(accessor).invoke(verdict);
    }

    @Test
    void theEntryIsReachableOnlyAcrossTheLoader() throws Exception {
        withIsolatedLoader(loader -> {
            Class<?> loaded = hoconFmt(loader);
            assertEquals("eu.ww86.hoconfmt.java.HoconFmt", loaded.getName());
            // The platform-parented loader shares the JDK with the test and nothing else: the
            // equal-named classes here and there are distinct objects.
            assertNotEquals(HoconFmt.class, loaded);
        });
    }

    @Test
    void alreadyFormattedCrossesAsAValuelessRecord() throws Exception {
        withIsolatedLoader(loader -> {
            Object verdict = check(loader, "a = 1\n".getBytes(StandardCharsets.UTF_8));
            assertEquals("AlreadyFormatted", verdict.getClass().getSimpleName());
            assertTrue(verdict.getClass().isRecord(), verdict.getClass() + " is not a record");
            assertEquals(0, verdict.getClass().getRecordComponents().length);
            assertNotEquals(Verdict.AlreadyFormatted.class, verdict.getClass());
        });
    }

    @Test
    void theFormattedTextReadsThroughTheRecordAccessor() throws Exception {
        withIsolatedLoader(loader -> {
            Object verdict = check(loader, "a   :   1".getBytes(StandardCharsets.UTF_8));
            assertEquals("NeedsFormatting", verdict.getClass().getSimpleName());
            assertEquals("a = 1\n", read(verdict, "formatted"));
        });
    }

    @Test
    void aRefusalCarriesItsKindAsAnEnumAndItsReason() throws Exception {
        withIsolatedLoader(loader -> {
            Object verdict = check(loader, new byte[] {(byte) 0xff, '\n'});
            assertEquals("Refused", verdict.getClass().getSimpleName());
            Object kind = read(verdict, "kind");
            assertTrue(kind instanceof Enum, "the kind is not an enum: " + kind.getClass());
            assertEquals("NotUtf8", ((Enum<?>) kind).name());
            assertEquals("not valid UTF-8", read(verdict, "reason"));
        });
    }

    @Test
    void aNamedByteEntryReportsTheOrigin() throws Exception {
        withIsolatedLoader(loader -> {
            Method entry = hoconFmt(loader).getMethod("check", byte[].class, String.class);
            Object verdict = entry.invoke(null, "a : ${".getBytes(StandardCharsets.UTF_8),
                    "conf/application.conf");
            assertEquals("Refused", verdict.getClass().getSimpleName());
            assertEquals("NotHocon", ((Enum<?>) read(verdict, "kind")).name());
            assertTrue(((String) read(verdict, "reason"))
                    .startsWith("not valid HOCON: conf/application.conf:"));
        });
    }

    @Test
    void aNamePromisingAnotherFormatIsRefused() throws Exception {
        withIsolatedLoader(loader -> {
            Method entry = hoconFmt(loader).getMethod("check", byte[].class, String.class);
            Object verdict = entry.invoke(null, "{\"a\": 1}".getBytes(StandardCharsets.UTF_8),
                    "application.json");
            assertEquals("Refused", verdict.getClass().getSimpleName());
            assertEquals("OtherFormat", ((Enum<?>) read(verdict, "kind")).name());
            assertTrue(((String) read(verdict, "reason")).startsWith("a JSON file"));
        });
    }

}
