package falcon;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class Main {
    private Main() {}
    public static void main(String[] args) { System.exit(cli(args, System.out, System.err)); }

    static int cli(String[] args, PrintStream out, PrintStream err) {
        return cli(args, System.in, out, err);
    }

    static int cli(String[] args, InputStream in, PrintStream out, PrintStream err) {
        if (args.length == 0 || args[0].equals("--help") || args[0].equals("help")) {
            out.println("Falcon 0.11.0 — Java reference interpreter\n"
                    + "Usage: falcon <run|check|tokens> <file.fc> [--max-steps N]\n"
                    + "       falcon install <github-owner/repository>\n"
                    + "       falcon setup\n"
                    + "       falcon cmd <command>\n"
                    + "       falcon --version\n"
                    + "       falcon check <file.fc> [--stdin] [--library]\n"
                    + "       falcon run <file.fc> [--max-steps N] [-- program arguments...]\n"
                    + "run    Check and execute main() (default limit: 100,000,000 steps)\n"
                    + "check  Check syntax and types without execution\n"
                    + "tokens Print lexer tokens");
            return 0;
        }
        if (args.length == 1 && args[0].equals("--version")) { out.println("Falcon 0.11.0"); return 0; }
        if (args[0].equals("setup")) {
            if (args.length != 1) { err.println("Usage: falcon setup"); return 2; }
            return setup(Path.of("").toAbsolutePath().normalize(), out, err);
        }
        if (args[0].equals("cmd")) {
            if (args.length != 2) { err.println("Usage: falcon cmd <command>"); return 2; }
            boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
            try {
                Process process = (windows ? new ProcessBuilder("cmd.exe", "/d", "/s", "/c", args[1])
                        : new ProcessBuilder("/bin/sh", "-c", args[1])).inheritIO().start();
                return process.waitFor();
            } catch (IOException e) { err.println("Could not start that command: " + e.getMessage()); return 2; }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); err.println("Command interrupted."); return 1; }
        }
        if (args[0].equals("install")) {
            if (args.length != 2) { err.println("Usage: falcon install owner/repository"); return 2; }
            Path script = Path.of("modules", "install.ps1").toAbsolutePath().normalize();
            if (!Files.isRegularFile(script)) { err.println("modules/install.ps1 is missing; restore it from the Falcon workspace."); return 2; }
            try {
                ProcessBuilder builder = System.getProperty("os.name").toLowerCase().contains("win")
                        ? new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script.toString(), args[1])
                        : new ProcessBuilder("bash", script.resolveSibling("install.sh").toString(), args[1]);
                builder.inheritIO(); return builder.start().waitFor();
            } catch (IOException e) { err.println("Cannot start module installer: " + e.getMessage()); return 2; }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); err.println("Module installation interrupted."); return 1; }
        }
        if (args.length < 2 || !List.of("run", "check", "tokens").contains(args[0])) {
            err.println("Usage: falcon <run|check|tokens> <file.fc> [--max-steps N]"); return 2;
        }
        long limit = 100_000_000;
        List<String> arguments = List.of();
        if (args[0].equals("run")) {
            for (int i = 2; i < args.length; i++) if (args[i].equals("--")) {
                arguments = List.of(java.util.Arrays.copyOfRange(args, i + 1, args.length));
                args = java.util.Arrays.copyOf(args, i); break;
            }

        }
        boolean stdin = false, library = false;
        if (args[0].equals("check")) {
            for (int i = 2; i < args.length; i++) {
                if (args[i].equals("--stdin") && !stdin) stdin = true;
                else if (args[i].equals("--library") && !library) library = true;
                else { err.println("Check accepts --stdin and --library once each."); return 2; }
            }
        } else if (args.length != 2) {
            try {
                if (args.length != 4 || !args[0].equals("run") || !args[2].equals("--max-steps")) throw new NumberFormatException();
                limit = Long.parseLong(args[3]); if (limit <= 0) throw new NumberFormatException();
            } catch (NumberFormatException e) { err.println("Use --max-steps with a positive integer and the run command."); return 2; }
        }
        String source;
        try { source = stdin ? new String(in.readAllBytes(), StandardCharsets.UTF_8)
                : Files.readString(Path.of(args[1]), StandardCharsets.UTF_8); }
        catch (IOException | InvalidPathException e) { err.println("Cannot read '" + args[1] + "': " + e.getMessage()); return 2; }
        ModuleLoader loader = new ModuleLoader();
        try {
            if (args[0].equals("tokens")) {
                List<Token> tokens = new Lexer(source).scan();
                for (Token token : tokens)
                    out.printf("%d:%d %-8s %s%n", token.line(), token.column(), token.kind(),
                            token.text().replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t"));
                return 0;
            }
            Ast.Program program = loader.load(Path.of(args[1]), source);
            Checker checker = new Checker(); checker.check(program, !library);
            if (args[0].equals("check")) out.println("OK: " + args[1]);
            else new Interpreter(checker, out, limit, in, arguments).run();
            return 0;
        } catch (FalconError e) { err.println(loader.diagnostic(e, args[1], source)); return 1; }
        catch (IOException e) { err.println("Cannot read '" + args[1] + "': " + e.getMessage()); return 2; }
        catch (StackOverflowError e) { err.println("Error: program nesting exceeds the implementation limit."); return 1; }
    }

    private static int setup(Path root, PrintStream out, PrintStream err) {
        try {
            for (String folder : List.of("src", "examples", "modules", "stdlib", ".vscode"))
                Files.createDirectories(root.resolve(folder));
            Path installed = Path.of(System.getProperty("falcon.stdlib", "stdlib")).toAbsolutePath().normalize();
            try (var files = Files.list(installed)) {
                for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".fc")).toList()) {
                    Path target = root.resolve("stdlib").resolve(file.getFileName());
                    if (!Files.exists(target)) Files.copy(file, target);
                }
            }
            Path installedModules = installed.getParent().resolve("modules");
            for (String helper : List.of("install.ps1", "install.sh", "module-template.json")) {
                Path source = installedModules.resolve(helper), target = root.resolve("modules").resolve(helper);
                if (Files.isRegularFile(source) && !Files.exists(target)) Files.copy(source, target);
            }
            Path settingsFile = root.resolve(".vscode/settings.json");
            Map<String,Object> settings = new java.util.LinkedHashMap<>();
            if (Files.exists(settingsFile)) {
                Object parsed = Json.read(Files.readString(settingsFile, StandardCharsets.UTF_8));
                if (!(parsed instanceof Map<?,?> existing)) throw new IOException(".vscode/settings.json must contain a JSON object.");
                for (var entry : existing.entrySet()) if (entry.getKey() instanceof String key) settings.put(key, entry.getValue());
            }
            Object configuredStdlib = settings.get("falcon.stdlibPath");
            if (!(configuredStdlib instanceof String value) || value.isBlank())
                settings.put("falcon.stdlibPath", root.resolve("stdlib").toString());
            Files.writeString(settingsFile, Json.write(settings) + System.lineSeparator(), StandardCharsets.UTF_8);
            Path starter = root.resolve("main.falcon");
            if (!Files.exists(starter)) Files.writeString(starter,
                    "func main() {\n    say(\"Hello from Falcon!\")\n}\n", StandardCharsets.UTF_8);
            out.println("Falcon project is ready at " + root);
            out.println("Created src, examples, modules, stdlib, and .vscode; copied missing standard-library files and VS Code settings.");
            out.println("Try: falcon run main.falcon");
            out.println("Install a module with: falcon install owner/repository");
            return 0;
        } catch (IOException | IllegalArgumentException | SecurityException e) {
            err.println("Falcon couldn't finish setting up this folder: " + e.getMessage());
            return 1;
        }
    }
}
