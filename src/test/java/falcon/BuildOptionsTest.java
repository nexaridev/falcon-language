package falcon;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** CLI coverage for falcon build flags without invoking slow native packaging. */
public final class BuildOptionsTest {
    private static int checks;
    private BuildOptionsTest() {}

    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("falcon-build-options-");
        try {
            Path project = Files.createDirectories(temp.resolve("project"));
            Files.createDirectories(project.resolve("stdlib"));
            Path source = project.resolve("hello.fc");
            Files.writeString(source, "func main() { say(\"hello\") }", StandardCharsets.UTF_8);
            Path output = temp.resolve("external-output");
            Path icon = temp.resolve("test.ico");
            Files.write(icon, new byte[] {0, 0, 1, 0});
            String[] argsAll = {"build", source.toString(), "--name=Renamed", "--out=" + output,
                    "--icon=" + icon, "--no-console", "--force", "--bruteforce", "--clean", "--dry-run", "--verbose", "--args", "hello world"};
            check("name/out/icon/no-console/force/bruteforce/clean/dry-run/verbose/args", argsAll, 0, "No build output was created.");
            check("dont-close", new String[] {"build", source.toString(), "--out", output.toString(), "--dont-close", "--dry-run"}, 0, "Files that would be bundled:");
            check("legacy output alias", new String[] {"build", source.toString(), "--output", output.toString(), "--dry-run"}, 0, "No build output was created.");
            check("one-file", new String[] {"build", source.toString(), "--out", output.toString(), "--one-file", "--dry-run"},
                    isWindows() ? 0 : 2, isWindows() ? "No build output was created." : "only works on Windows");
            check("req-admin", new String[] {"build", source.toString(), "--out", output.toString(), "--req-admin", "--dry-run"},
                    isWindows() ? 0 : 2, isWindows() ? "No build output was created." : "only works on Windows");
            check("no-console with dont-close", new String[] {"build", source.toString(), "--no-console", "--dont-close"}, 2, "cannot be used");
            check("default output in project build folder", new String[] {"build", source.toString(), "--dry-run"}, 0,
                    "output folder would be " + project.resolve("build").resolve("hello"));
            check("out inside project", new String[] {"build", source.toString(), "--out", project.resolve("output").toString(), "--dry-run"}, 0, "No build output was created.");
            check("outside rejects project path", new String[] {"build", source.toString(), "--outside", "--out", project.resolve("output").toString(), "--dry-run"}, 1, "outside the project");
            check("outside default", new String[] {"build", source.toString(), "--outside", "--dry-run"}, 0, "output folder would be");
            Path broken = project.resolve("broken.fc");
            Files.writeString(broken, "func main( {", StandardCharsets.UTF_8);
            check("checker errors stop by default", new String[] {"build", broken.toString(), "--out", output.toString(), "--dry-run"}, 1, "Falcon couldn't build");
            check("bruteforce packages past checker errors", new String[] {"build", broken.toString(), "--out", output.toString(), "--bruteforce", "--dry-run"}, 0, "No build output was created.");
            check("missing icon", new String[] {"build", source.toString(), "--icon=" + temp.resolve("missing.ico")}, 2, "Icon file not found");
            if (isWindows()) {
                Path wrongIcon = temp.resolve("test.png");
                Files.write(wrongIcon, new byte[] {1});
                check("Windows icon extension", new String[] {"build", source.toString(), "--icon=" + wrongIcon}, 2, "must be .ico");
            }
            System.out.println("Passed " + checks + " build option checks.");
        } finally {
            try (var paths = Files.walk(temp)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void check(String name, String[] args, int expectedCode, String expectedText) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = Main.cli(args, System.in,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        String output = stdout.toString(StandardCharsets.UTF_8) + stderr.toString(StandardCharsets.UTF_8);
        if (code != expectedCode || !output.contains(expectedText))
            throw new AssertionError(name + ": expected exit " + expectedCode + " and '" + expectedText + "', got " + code + " / " + output);
        checks++;
    }

    private static boolean isWindows() { return System.getProperty("os.name").toLowerCase().contains("win"); }
}
