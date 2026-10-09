import java.nio.file.*;
import org.ekrich.config.*;

// An independent runner over bare released sconfig, with no formatter calls.
public final class Oracle {
    public static void main(String[] args) throws Exception {
        var source = Path.of(args[0]);
        var output = Path.of(args[1]);
        var render = ConfigRenderOptions.defaults().setJson(false).setOriginComments(false)
            .setComments(true).setFormatted(true).setConfigFormatOptions(
                ConfigFormatOptions.defaults().setKeepOriginOrder(true).setDoubleIndent(args.length > 5 && args[5].equals("true"))
                    .setColonAssign(args[2].equals(":"))
                    .setSimplifyNestedObjects(args.length < 5 || args[4].equals("true")).setNewLineAtEnd(true));
        try {
            var before = ConfigFactory.parseFile(source.toFile());
            var literal = java.util.regex.Pattern.compile("(?s)^\\s*s\\s*=\\s*\"\"\"(.*?)\"\"\"\\s*$")
                .matcher(Files.readString(source));
            if (literal.matches() && !literal.group(1).contains("\"\"\"")) {
                var expected = literal.group(1);
                var parsed = before.getString("s");
                var formatted = ConfigFactory.parseFile(output.toFile()).getString("s");
                if (!expected.equals(parsed) || !expected.equals(formatted)) {
                    System.out.println("LITERAL-CHANGED: expected=" + expected.replace("\r", "<CR>").replace("\n", "<LF>")
                        + " parsed=" + parsed.replace("\r", "<CR>").replace("\n", "<LF>")
                        + " formatted=" + formatted.replace("\r", "<CR>").replace("\n", "<LF>"));
                }
            }
            Files.writeString(Path.of(args[3]), before.root().render(render));
            try {
                var resolve = ConfigResolveOptions.noSystem();
                var a = before.resolve(resolve).root().unwrapped();
                var b = ConfigFactory.parseFile(output.toFile()).resolve(resolve).root().unwrapped();
                System.out.println(a.equals(b) ? "meaning-equal" : "MEANING-CHANGED\nsource=" + a + "\noutput=" + b);
            } catch (ConfigException e) {
                System.out.println("oracle-unresolved: " + e.getClass().getSimpleName());
            }
        } catch (ConfigException e) {
            System.out.println("oracle-input: " + e.getClass().getSimpleName());
        } catch (java.nio.charset.MalformedInputException e) {
            System.out.println("oracle-input: NotUtf8");
        }
    }
}
