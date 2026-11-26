package falcon;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class EditorTest {
    static int run() throws Exception {
        Path file = Files.createTempFile("falcon-editor-", ".fc");
        try {
            String disk = "func main() { say(1) }";
            Files.writeString(file, disk);
            var output = new ByteArrayOutputStream();
            var stream = new PrintStream(output, true, StandardCharsets.UTF_8);
            var input = new ByteArrayInputStream("func main() { say(missing) }".getBytes(StandardCharsets.UTF_8));
            int result = Main.cli(new String[]{"check", file.toString(), "--stdin"}, input, stream, stream);
            if (result != 1 || !output.toString(StandardCharsets.UTF_8).contains("Unknown variable 'missing'"))
                throw new AssertionError("Editor check did not use unsaved source: " + output);
            if (!Files.readString(file).equals(disk)) throw new AssertionError("Editor check changed the file");
            output.reset();
            input = new ByteArrayInputStream("func square(n: int) -> int { give n * n }".getBytes(StandardCharsets.UTF_8));
            result = Main.cli(new String[]{"check", file.toString(), "--stdin", "--library"}, input, stream, stream);
            if (result != 0) throw new AssertionError("Library should not require main: " + output);
            output.reset();
            input = new ByteArrayInputStream("func square(n: int) -> int { give true }".getBytes(StandardCharsets.UTF_8));
            result = Main.cli(new String[]{"check", file.toString(), "--library", "--stdin"}, input, stream, stream);
            if (result != 1 || !output.toString(StandardCharsets.UTF_8).contains("Expected int, got bool"))
                throw new AssertionError("Library types were not checked: " + output);
            if (Main.cli(new String[]{"run", file.toString(), "--stdin"}, stream, stream) != 2)
                throw new AssertionError("Run should not accept editor options");
            if (Main.cli(new String[]{"check", file.toString(), "--stdin", "--stdin"}, stream, stream) != 2)
                throw new AssertionError("Duplicate stdin flag should be rejected");
            Path standard = Files.createTempDirectory("falcon-stdlib-");
            String previous = System.getProperty("falcon.stdlib");
            try {
                System.setProperty("falcon.stdlib", standard.toString());
                Path math = standard.resolve("math.fc");
                Files.writeString(file, "use math\nfunc main() { say(math.square(5)) }");
                Files.writeString(math, "func square(value: int) -> int { give 42 }");
                output.reset();
                if (Main.cli(new String[]{"run", file.toString()}, stream, stream) != 0 || !TestOutput.withoutDuration(output.toString(StandardCharsets.UTF_8)).trim().equals("42"))
                    throw new AssertionError("Editable Falcon stdlib source not used: " + output);
                Files.writeString(math, "func square(value: int) -> int { give 43 }");
                output.reset();
                if (Main.cli(new String[]{"run", file.toString()}, stream, stream) != 0 || !TestOutput.withoutDuration(output.toString(StandardCharsets.UTF_8)).trim().equals("43"))
                    throw new AssertionError("Stdlib edits require no rebuild: " + output);
                Files.writeString(math, "func square(value: int) -> int { give true }");
                output.reset();
                if (Main.cli(new String[]{"check", file.toString()}, stream, stream) != 1 || !output.toString(StandardCharsets.UTF_8).contains("math.fc:1:34: error: Expected int, got bool"))
                    throw new AssertionError("Standard library must be type-checked with its source location: " + output);
                Files.delete(math);
            } finally {
                if (previous == null) System.clearProperty("falcon.stdlib"); else System.setProperty("falcon.stdlib", previous);
                Files.deleteIfExists(standard.resolve("math.fc"));
                Files.delete(standard);
            }
            return 9;
        } finally { Files.delete(file); }
    }
}
