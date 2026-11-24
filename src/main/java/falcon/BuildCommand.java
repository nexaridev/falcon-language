package falcon;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.tools.ToolProvider;
import static falcon.Ast.*;

/** Packages a checked, self-contained Falcon source file as a jpackage app image. */
final class BuildCommand {
    private BuildCommand() {}

    static int build(Path sourcePath, Path destination, String defaults, PrintStream out, PrintStream err) throws Exception {
        Path source = sourcePath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(source)) throw new IOException("file not found: " + source);
        String programSource = Files.readString(source, StandardCharsets.UTF_8);
        ModuleLoader loader = new ModuleLoader();
        Program program = loader.load(source, programSource);
        Checker checker = new Checker();
        checker.check(program, true);
        if (!checker.errors.isEmpty()) {
            for (FalconError error : checker.errors) err.println(loader.diagnostic(error, source.toString(), programSource));
            return 1;
        }

        String name = source.getFileName().toString().replaceFirst("\\.[^.]+$", "");
        if (!name.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) throw new IOException("choose a filename using letters, numbers, dots, dashes, or underscores.");
        Path outputDirectory = destination == null
                ? source.getParent().resolve("build").resolve(name + "-app")
                : destination.toAbsolutePath().normalize();
        Files.createDirectories(outputDirectory);
        deleteTree(outputDirectory.resolve(name));
        Path work = Files.createTempDirectory("falcon-jpackage-");
        try {
            Path input = Files.createDirectories(work.resolve("input"));
            Path bootstrapSource = work.resolve("FalconProgramBootstrap.java");
            Path classes = Files.createDirectories(work.resolve("classes"));
            Path bootstrapJar = input.resolve("bootstrap.jar");
            Path falconJar = currentJar();
            Files.copy(falconJar, input.resolve("falcon.jar"));
            Path projectRoot = projectRoot(source);
            String relativeProgram = projectRoot.relativize(source).toString().replace('\\', '/');
            String bootstrap = bootstrapSource(defaults, relativeProgram);
            Files.writeString(bootstrapSource, bootstrap, StandardCharsets.UTF_8);
            var compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null) throw new IOException("building an executable requires a JDK with javac.");
            int compile = compiler.run(null, null, null, "-encoding", "UTF-8", "-classpath", input.resolve("falcon.jar").toString(),
                    "-d", classes.toString(), bootstrapSource.toString());
            if (compile != 0) throw new IOException("couldn't compile the small executable launcher.");
            try (var jar = new JarOutputStream(Files.newOutputStream(bootstrapJar))) {
                Path classFile = classes.resolve("FalconProgramBootstrap.class");
                jar.putNextEntry(new JarEntry("FalconProgramBootstrap.class"));
                Files.copy(classFile, jar);
                jar.closeEntry();
                jar.putNextEntry(new JarEntry("project.zip"));
                writeProjectZip(jar, projectRoot, outputDirectory);
                jar.closeEntry();
                jar.putNextEntry(new JarEntry("META-INF/MANIFEST.MF"));
                jar.write(("Manifest-Version: 1.0\r\nMain-Class: FalconProgramBootstrap\r\nClass-Path: falcon.jar\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
                jar.closeEntry();
            }
            Path jpackage = findTool("jpackage");
            List<String> command = List.of(jpackage.toString(), "--type", "app-image", "--name", name,
                    "--input", input.toString(), "--main-jar", "bootstrap.jar", "--main-class", "FalconProgramBootstrap",
                    "--dest", outputDirectory.toString());
            var builder = new ArrayList<>(command);
            if (isWindows()) builder.add("--win-console");
            if (Runtime.version().feature() >= 24) {
                builder.add("--java-options");
                builder.add("--enable-native-access=ALL-UNNAMED");
            }
            Process process = new ProcessBuilder(builder).inheritIO().start();
            int status = process.waitFor();
            if (status != 0) throw new IOException("jpackage exited with code " + status + ".");
            out.println("Built executable app image: " + outputDirectory.resolve(name).resolve(name + (isWindows() ? ".exe" : "")));
            out.println("The app image includes its JVM and Falcon runtime. Move the whole '" + name + "' folder to distribute it.");
            return 0;
        } finally { deleteTree(work); }
    }

    private static Path projectRoot(Path source) {
        Path parent = source.getParent();
        for (Path current = parent; current != null; current = current.getParent()) {
            if (current.getFileName() != null && current.getFileName().toString().equalsIgnoreCase("build"))
                return parent;
            if (Files.isDirectory(current.resolve(".git")) || Files.isDirectory(current.resolve("stdlib"))
                    || Files.isDirectory(current.resolve("modules")) || Files.isRegularFile(current.resolve("falcon.toml")))
                return current;
        }
        return parent;
    }

    private static String bootstrapSource(String defaults, String relativeProgram) {
        List<String> arguments = splitArguments(defaults);
        String literals = arguments.stream().map(BuildCommand::javaString).collect(java.util.stream.Collectors.joining(", "));
        return "public final class FalconProgramBootstrap { public static void main(String[] args) throws Exception {"
                + " java.nio.file.Path root = java.nio.file.Files.createTempDirectory(\"falcon-program-\");"
                + " try (java.io.InputStream raw = FalconProgramBootstrap.class.getResourceAsStream(\"/project.zip\");"
                + " java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(raw)) {"
                + " if (raw == null) throw new java.io.IOException(\"Packaged Falcon project is missing.\");"
                + " java.util.zip.ZipEntry entry; while ((entry = zip.getNextEntry()) != null) {"
                + " java.nio.file.Path output = root.resolve(entry.getName()).normalize();"
                + " if (!output.startsWith(root)) throw new java.io.IOException(\"Invalid project archive entry.\");"
                + " if (entry.isDirectory()) java.nio.file.Files.createDirectories(output); else {"
                + " java.nio.file.Files.createDirectories(output.getParent());"
                + " java.nio.file.Files.copy(zip, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }"
                + " zip.closeEntry(); } }"
                + " java.nio.file.Path source = root.resolve(" + javaString(relativeProgram) + ");"
                + " java.util.ArrayList<String> command = new java.util.ArrayList<>(java.util.List.of(\"run\", source.toString()));"
                + " command.add(\"--\"); command.addAll(java.util.List.of(" + literals + "));"
                + " command.addAll(java.util.List.of(args)); falcon.Main.main(command.toArray(String[]::new));"
                + " } }";
    }

    private static void writeProjectZip(JarOutputStream jar, Path projectRoot, Path outputDirectory) throws IOException {
        var sink = new java.io.FilterOutputStream(jar) {
            @Override public void close() throws IOException { flush(); }
        };
        try (var zip = new ZipOutputStream(sink)) {
            try (var paths = Files.walk(projectRoot)) {
                for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                    if (path.startsWith(outputDirectory)) continue;
                    Path relative = projectRoot.relativize(path);
                    if (relative.getNameCount() > 0 && List.of(".git", "build", "node_modules", ".idea", ".vscode", ".falcon-cache")
                            .contains(relative.getName(0).toString())) continue;
                    zip.putNextEntry(new ZipEntry(relative.toString().replace('\\', '/')));
                    Files.copy(path, zip);
                    zip.closeEntry();
                }
            }
        }
    }

    private static List<String> splitArguments(String value) {
        if (value.isBlank()) return List.of();
        var result = new ArrayList<String>();
        var current = new StringBuilder();
        boolean quoted = false;
        char quote = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c == '"' || c == '\'') && (!quoted || c == quote)) {
                if (quoted) quoted = false; else { quoted = true; quote = c; }
            } else if (Character.isWhitespace(c) && !quoted) {
                if (!current.isEmpty()) { result.add(current.toString()); current.setLength(0); }
            } else current.append(c);
        }
        if (quoted) throw new IllegalArgumentException("close the quote in --args.");
        if (!current.isEmpty()) result.add(current.toString());
        return result;
    }

    private static String javaString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n") + "\"";
    }

    private static Path currentJar() throws URISyntaxException, IOException {
        Path path = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath();
        if (!Files.isRegularFile(path)) throw new IOException("run 'falcon build' from the installed Falcon JAR launcher.");
        return path;
    }

    private static Path findTool(String tool) throws IOException {
        String exe = tool + (isWindows() ? ".exe" : "");
        Path javaHome = Path.of(System.getProperty("java.home"));
        List<Path> candidates = List.of(javaHome.resolve("bin").resolve(exe),
                javaHome.getParent() == null ? javaHome.resolve(exe) : javaHome.getParent().resolve("bin").resolve(exe));
        for (Path candidate : candidates) if (Files.isExecutable(candidate)) return candidate;
        throw new IOException(tool + " wasn't found beside Java. Install a JDK 17+ and set JAVA_HOME.");
    }

    private static boolean isWindows() { return System.getProperty("os.name").toLowerCase().contains("win"); }
    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var entries = Files.walk(path)) {
            for (Path item : entries.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(item);
        }
    }
}
