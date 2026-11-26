package falcon;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class Main {
    static final String VERSION = "0.12.0";
    private Main() {}
    public static void main(String[] args) { System.exit(cli(args, System.out, System.err)); }

    static int cli(String[] args, PrintStream out, PrintStream err) {
        return cli(args, System.in, out, err, null);
    }

    static int cli(String[] args, InputStream in, PrintStream out, PrintStream err) {
        return cli(args, in, out, err, null);
    }

    private static int cli(String[] args, InputStream in, PrintStream out, PrintStream err, CompilationCache compilationCache) {
        try { return dispatch(args, in, out, err, compilationCache); }
        finally { UpdateNotifier.afterCommand(out); }
    }

    private static int dispatch(String[] args, InputStream in, PrintStream out, PrintStream err, CompilationCache compilationCache) {
        if (args.length == 0 || args[0].equals("--help") || args[0].equals("help")) {
            out.println("Falcon " + VERSION + " — Java reference interpreter\n"
                    + "Usage: falcon <run|check|tokens> <file.fc> [--max-steps N]\n"
                    + "       falcon deploy [module-dir] [--repo owner/repository]\n"
                    + "       falcon install <github-owner/repository>\n"
                    + "       falcon install <owner/repository> [--path <folder>] [--ref <branch-or-tag>] [--name <module>]\n"
                    + "       falcon install <owner/repository> --release <tag> [--name <module>]\n"
                    + "       falcon install <local-module-folder-or-.falconpkg> [--name <module>]\n"
                    + "GitHub deploy token: FALCON_GITHUB_TOKEN, GITHUB_TOKEN, or github.token in ~/.falcon/config.properties\n"
                    + "       falcon --token=<github-token>  (save token for deploys)\n"
                    + "       falcon --token-clear  (remove saved token)\n"
                    + "       falcon install -u  (update Falcon)\n"
                    + "       falcon --upd-quiet  (silence this update reminder)\n"
                    + "       falcon setup\n"
                    + "       falcon repl\n"
                    + "       falcon jvm  (persistent JVM with an in-memory JIT cache)\n"
                    + "       falcon build <file.fc> [--name=<name>] [--out=<directory>] [build flags] [--args <defaults>]\n"
                    + "         --icon=<file.ico> --no-console --dont-close --req-admin --one-file\n"
                    + "         --outside --clean --dry-run --verbose --force --bruteforce\n"
                    + "       falcon cmd <command>\n"
                    + "       falcon --version\n"
                    + "       falcon check <file.fc> [--stdin] [--library]\n"
                    + "       falcon run <file.fc> [--max-steps N] [-- program arguments...]\n"
                    + "run    Check and execute main() (default limit: 100,000,000 steps)\n"
                    + "check  Check syntax and types without execution\n"
                    + "tokens Print lexer tokens");
            return 0;
        }
        if (args.length == 1 && args[0].equals("--version")) { out.println("Falcon " + VERSION); return 0; }
        if (args.length == 1 && args[0].equals("--upd-quiet")) return UpdateNotifier.quiet(out, err);
        if (args[0].equals("--token-clear")) {
            if (args.length != 1) { err.println("Usage: falcon --token-clear"); return 2; }
            try { GitHubReleaseClient.clearToken(GitHubReleaseClient.configFile()); out.println("Saved GitHub token cleared successfully."); return 0; }
            catch (IOException e) { err.println("Could not clear the saved token: " + e.getMessage()); return 1; }
        }
        if (args[0].equals("--token") || args[0].startsWith("--token=")) {
            if (args.length > 2 || (args.length == 2 && !args[0].equals("--token"))) {
                err.println("Usage: falcon --token=<github-token> (or falcon --token to enter it privately)"); return 2;
            }
            String token;
            if (args[0].equals("--token") && args.length == 2) token = args[1];
            else if (args[0].equals("--token")) {
                java.io.Console console = System.console();
                if (console == null) { err.println("No terminal is available. Use falcon --token=<github-token>."); return 2; }
                char[] secret = console.readPassword("GitHub token (input hidden): ");
                token = secret == null ? "" : new String(secret);
                java.util.Arrays.fill(secret == null ? new char[0] : secret, '\0');
            } else {
                token = args[0].substring("--token=".length());
                args[0] = "--token=<redacted>";
            }
            try { GitHubReleaseClient.saveToken(token, GitHubReleaseClient.configFile()); out.println("GitHub token saved successfully."); return 0; }
            catch (IOException e) { err.println("Could not save the GitHub token: " + e.getMessage()); return 1; }
        }
        if (args[0].equals("jvm")) {
            if (args.length != 1) { err.println("Usage: falcon jvm"); return 2; }
            return jvm(in, out, err);
        }
        if (args[0].equals("repl")) {
            if (args.length != 1) { err.println("Usage: falcon repl"); return 2; }
            return repl(in, out, err);
        }
        if (args[0].equals("build") || args[0].equals("bu")) {
            if (args.length < 2) { err.println("Usage: falcon build <file.fc> [build flags] [--args <defaults>]"); return 2; }
            Path source = Path.of(args[1].endsWith(".") ? args[1] + "fc" : args[1]);
            String defaults = "";
            String name = null;
            Path icon = null, output = null;
            boolean outside = false, noConsole = false, requireAdmin = false, oneFile = false, dontClose = false;
            boolean force = false, bruteForce = false, clean = false, dryRun = false, verbose = false;
            for (int i = 2; i < args.length; i++) {
                if (args[i].equals("--args") && i + 1 < args.length) defaults = args[++i];
                else if (args[i].startsWith("--name=")) name = args[i].substring(7);
                else if (args[i].equals("--name") && i + 1 < args.length) name = args[++i];
                else if (args[i].startsWith("--out=")) output = Path.of(args[i].substring(6));
                else if (args[i].equals("--out") && i + 1 < args.length) output = Path.of(args[++i]);
                else if (args[i].startsWith("--output=")) output = Path.of(args[i].substring(9));
                else if (args[i].equals("--output") && i + 1 < args.length) output = Path.of(args[++i]);
                else if (args[i].equals("--outside")) outside = true;
                else if (args[i].equals("--icon") && i + 1 < args.length) icon = Path.of(args[++i]);
                else if (args[i].startsWith("--icon=")) icon = Path.of(args[i].substring("--icon=".length()));
                else if (args[i].equals("--no-console")) noConsole = true;
                else if (args[i].equals("--req-admin")) requireAdmin = true;
                else if (args[i].equals("--one-file")) oneFile = true;
                else if (args[i].equals("--dont-close")) dontClose = true;
                else if (args[i].equals("--force")) force = true;
                else if (args[i].equals("--bruteforce")) bruteForce = true;
                else if (args[i].equals("--clean")) clean = true;
                else if (args[i].equals("--dry-run")) dryRun = true;
                else if (args[i].equals("--verbose")) verbose = true;
                else { err.println("Unknown build flag: " + args[i]); return 2; }
            }
            if (noConsole && dontClose) { err.println("--dont-close needs a console and cannot be used with --no-console."); return 2; }
            if (requireAdmin && !System.getProperty("os.name").toLowerCase().contains("win")) { err.println("--req-admin only works on Windows."); return 2; }
            if (oneFile && !System.getProperty("os.name").toLowerCase().contains("win")) { err.println("--one-file currently only works on Windows."); return 2; }
            if (icon != null && !Files.isRegularFile(icon)) { err.println("Icon file not found: " + icon); return 2; }
            if (icon != null && System.getProperty("os.name").toLowerCase().contains("win")
                    && !icon.getFileName().toString().toLowerCase().endsWith(".ico")) {
                err.println("Windows icons must be .ico files. Choose a .ico file for --icon."); return 2;
            }
            try { return BuildCommand.build(source, defaults,
                    new BuildCommand.Options(name, icon, output, outside, noConsole, requireAdmin, oneFile, dontClose, force, bruteForce, clean, dryRun, verbose), out, err); }
            catch (Exception e) { err.println("Falcon couldn't build that executable: " + e.getMessage()); return 1; }
        }
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
        if (args[0].equals("deploy")) {
            Path moduleDirectory = Path.of(".");
            String repository = null;
            for (int i = 1; i < args.length; i++) {
                if (i == 1 && !args[i].startsWith("--")) moduleDirectory = Path.of(args[i]);
                else if (args[i].startsWith("--repo=")) repository = args[i].substring("--repo=".length());
                else if (args[i].equals("--repo") && i + 1 < args.length) repository = args[++i];
                else { err.println("Usage: falcon deploy [module-dir] [--repo owner/repository]"); return 2; }
            }
            try {
                if (repository == null) repository = PackageCommands.repositoryFromModule(moduleDirectory, Path.of(".").toAbsolutePath().normalize());
                return PackageCommands.deploy(moduleDirectory, repository, GitHubReleaseClient.token(), GitHubReleaseClient.DEFAULT_API, out, err);
            } catch (Exception e) { err.println("Falcon deploy failed: " + e.getMessage()); return 1; }
        }
        if (args[0].equals("install")) {
            if (args.length == 2 && args[1].equals("-u")) return updateInstalledFalcon(out, err);
            if (args.length < 2) { err.println("Usage: falcon install owner/repository [--path folder] [--ref branch-or-tag] [--name module]"); return 2; }
            String releaseTag = null, localName = null;
            boolean releaseInstall = false;
            for (int i = 2; i < args.length; i++) {
                if (args[i].equals("--release") && i + 1 < args.length) { releaseTag = args[++i]; releaseInstall = true; }
                else if (args[i].startsWith("--release=")) { releaseTag = args[i].substring("--release=".length()); releaseInstall = true; }
                else if (args[i].equals("--name") && i + 1 < args.length) localName = args[++i];
                else if (args[i].startsWith("--name=")) localName = args[i].substring("--name=".length());
                else if (releaseInstall) { err.println("Release install accepts --release <tag> and --name <module>."); return 2; }
            }
            boolean githubRepository = args[1].matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");
            if (releaseInstall) {
                if (!githubRepository || releaseTag == null || releaseTag.isBlank()) {
                    err.println("Usage: falcon install owner/repository --release <tag> [--name <module>]"); return 2;
                }
                try { return PackageCommands.installRelease(args[1], releaseTag, localName, GitHubReleaseClient.token(), GitHubReleaseClient.DEFAULT_API, Path.of(".").toAbsolutePath().normalize(), out, err); }
                catch (Exception e) { err.println("Falcon install failed: " + e.getMessage()); return 1; }
            }
            if (!githubRepository) {
                Path localPath;
                try { localPath = Path.of(args[1]); }
                catch (InvalidPathException e) { err.println("Invalid local module path: " + e.getMessage()); return 2; }
                if (Files.exists(localPath)) {
                    for (int i = 2; i < args.length; i++) {
                        if (args[i].equals("--name") && i + 1 < args.length) { localName = args[++i]; continue; }
                        if (args[i].startsWith("--name=")) { localName = args[i].substring("--name=".length()); continue; }
                        err.println("Local install accepts only --name <module>."); return 2;
                    }
                    return PackageCommands.installLocal(localPath, localName, Path.of(".").toAbsolutePath().normalize(), out, err);
                }
            }
            Path script = Path.of("modules", "install.ps1").toAbsolutePath().normalize();
            if (!Files.isRegularFile(script)) { err.println("modules/install.ps1 is missing; restore it from the Falcon workspace."); return 2; }
            try {
                boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
                List<String> command = windows
                        ? new java.util.ArrayList<>(List.of("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script.toString()))
                        : new java.util.ArrayList<>(List.of("bash", script.resolveSibling("install.sh").toString()));
                for (int i = 1; i < args.length; i++)
                    command.add(windows && args[i].startsWith("--") ? "-" + args[i].substring(2) : args[i]);
                ProcessBuilder builder = new ProcessBuilder(command);
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
        PreparedProgram preparedForExecution = null;
        try {
            if (args[0].equals("tokens")) {
                List<Token> tokens = new Lexer(source).scan();
                for (Token token : tokens)
                    out.printf("%d:%d %-8s %s%n", token.line(), token.column(), token.kind(),
                            token.text().replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t"));
                return 0;
            }

            Ast.Program program = loader.load(Path.of(args[1]), source);
            Checker checker;
            if (compilationCache != null) {
                String key = compilationKey(loader.sources(), !library);
                PreparedProgram prepared = compilationCache.get(key);
                if (prepared == null) {
                    checker = new Checker(); checker.check(program, !library);
                    if (checker.errors.isEmpty()) {
                        prepared = new PreparedProgram(program, checker);
                        compilationCache.put(key, prepared);
                    }
                } else {
                    program = prepared.program();
                    checker = prepared.checker();
                }
                preparedForExecution = prepared;
            } else {
                checker = new Checker(); checker.check(program, !library);
            }
            if (!checker.errors.isEmpty()) {
                for (FalconError diagnostic : checker.errors) err.println(loader.diagnostic(diagnostic, args[1], source));
                return 1;
            }
            if (args[0].equals("check")) out.println("OK: " + args[1]);
            else {
                long executionLimit = limit;
                List<String> programArguments = arguments;
                FastJitCompiler.CompiledProgram compiled = preparedForExecution == null ? null : preparedForExecution.compiled();
                if (compiled != null) runTimed(() -> compiled.run(out, executionLimit), out);
                else runTimed(() -> new Interpreter(checker, out, executionLimit, in, programArguments).run(), out);
            }
            return 0;
        } catch (FalconError e) { err.println(loader.diagnostic(e, args[1], source)); return 1; }
        catch (IOException e) { err.println("Cannot read '" + args[1] + "': " + e.getMessage()); return 2; }
        catch (StackOverflowError e) { err.println("Error: program nesting exceeds the implementation limit."); return 1; }
    }

    private static int updateInstalledFalcon(PrintStream out, PrintStream err) {
        if (!System.getProperty("os.name").toLowerCase().contains("win")) {
            err.println("Automatic self-update is supported by FalconSetup on Windows. Download the latest release for other systems.");
            return 2;
        }
        try {
            Path installRoot = Path.of(System.getenv().getOrDefault("LOCALAPPDATA", ""), "Falcon").toAbsolutePath().normalize();
            Path installedJar = installRoot.resolve("falcon.jar");
            Path runningJar = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
            if (!Files.isRegularFile(installedJar) || !runningJar.equals(installedJar)) {
                err.println("This Falcon wasn't installed by FalconSetup. Run FalconSetup.exe from the latest GitHub release to update it.");
                return 2;
            }
            Path setup = installRoot.resolve("FalconSetup.exe");
            if (!Files.isRegularFile(setup)) {
                err.println("FalconSetup.exe is missing. Download it from https://github.com/nexaridev/falcon-language/releases and run it to update Falcon.");
                return 2;
            }
            new ProcessBuilder(setup.toString(), "--update", "--wait-pid", Long.toString(ProcessHandle.current().pid()))
                    .inheritIO().start();
            out.println("Falcon updater started. It will replace the JAR after this command exits.");
            return 0;
        } catch (Exception e) {
            err.println("Could not start the Falcon updater: " + e.getMessage());
            return 1;
        }
    }

    private static void runTimed(Runnable program, PrintStream out) {
        long started = System.nanoTime();
        java.util.concurrent.atomic.AtomicBoolean reported = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable report = () -> {
            if (reported.compareAndSet(false, true)) out.println("Ended in " + elapsed(System.nanoTime() - started) + ".");
        };
        Thread shutdown = new Thread(report, "falcon-run-duration");
        Runtime.getRuntime().addShutdownHook(shutdown);
        try { program.run(); }
        finally {
            report.run();
            try { Runtime.getRuntime().removeShutdownHook(shutdown); }
            catch (IllegalStateException ignored) { }
        }
    }

    private static String elapsed(long nanoseconds) {
        long milliseconds = nanoseconds / 1_000_000;
        String precise = String.format(java.util.Locale.ROOT, "%,dms", milliseconds);
        if (milliseconds < 1000) return precise;
        String seconds = String.format(java.util.Locale.ROOT, "%.3fs", milliseconds / 1000.0);
        if (milliseconds < 60_000) return precise + " (" + seconds + ")";
        String minutesAndSeconds = (milliseconds / 60_000) + "m, "
                + String.format(java.util.Locale.ROOT, "%02d.%03ds", (milliseconds / 1000) % 60, milliseconds % 1000);
        return precise + " (" + minutesAndSeconds + ")";
    }

    private static final class PreparedProgram {
        private final Ast.Program program;
        private final Checker checker;
        private FastJitCompiler.CompiledProgram compiled;
        private boolean compilationAttempted;
        PreparedProgram(Ast.Program program, Checker checker) { this.program = program; this.checker = checker; }
        Ast.Program program() { return program; }
        Checker checker() { return checker; }
        synchronized FastJitCompiler.CompiledProgram compiled() {
            if (!compilationAttempted) {
                compilationAttempted = true;
                compiled = FastJitCompiler.compile(program, checker);
            }
            return compiled;
        }
    }

    private static final class CompilationCache {
        private static final int LIMIT = 64;
        private final java.util.LinkedHashMap<String, PreparedProgram> entries = new java.util.LinkedHashMap<>(16, 0.75f, true);
        synchronized PreparedProgram get(String key) { return entries.get(key); }
        synchronized void put(String key, PreparedProgram program) {
            entries.put(key, program);
            while (entries.size() > LIMIT) entries.remove(entries.keySet().iterator().next());
        }
    }

    private static String compilationKey(Map<String, String> sources, boolean requireMain) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            digest.update((byte) (requireMain ? 1 : 0));
            for (var entry : new java.util.TreeMap<>(sources).entrySet()) {
                digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(entry.getValue().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0xff);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
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

    private static int repl(InputStream in, PrintStream out, PrintStream err) {
        out.println("Falcon interactive mode. Enter Falcon statements; use :quit to exit.");
        var history = new java.util.ArrayList<String>();
        Interpreter interpreter = null;
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        while (true) {
            try {
                out.print("fc> "); out.flush();
                String line = reader.readLine();
                if (line == null || line.strip().equals(":quit") || line.strip().equals(".exit")) return 0;
                if (line.isBlank()) continue;
                String statement = line;
                if (!line.stripLeading().matches("(?s)(var|fix|if|when|while|each|give|try|\\{).*")
                        && !line.stripLeading().matches("[A-Za-z_][A-Za-z_0-9]*(?:\\s*\\[[^]]+\\])?\\s*=.*")
                        && !line.stripLeading().matches("[A-Za-z_][A-Za-z_0-9.]*\\s*\\(.*"))
                    statement = "say(" + line + ")";
                history.add(statement);
                String source = "func main() {\n" + String.join("\n", history) + "\n}\n";
                try {
                    Ast.Program program = new Parser(new Lexer(source, "<repl>").scan()).parse();
                    Checker checker = new Checker(); checker.check(program, true);
                    if (!checker.errors.isEmpty()) {
                        for (FalconError error : checker.errors) err.println(error.diagnostic("<repl>", source));
                        history.remove(history.size() - 1); continue;
                    }
                    if (interpreter == null) interpreter = new Interpreter(checker, out, 100_000_000, in, List.of());
                    else interpreter.updateChecker(checker);
                    List<Ast.Stmt> statements = program.functions().get(0).body().statements();
                    if (!statements.isEmpty()) interpreter.runInteractive(statements.get(statements.size() - 1));
                } catch (FalconError error) {
                    err.println(error.diagnostic("<repl>", source));
                    history.remove(history.size() - 1);
                }
            } catch (IOException error) {
                err.println("Couldn't read input: " + error.getMessage()); return 1;
            }
        }
    }

    private static int jvm(InputStream in, PrintStream out, PrintStream err) {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        java.io.Console console = in == System.in ? System.console() : null;
        CompilationCache compilationCache = new CompilationCache();
        out.println("Falcon JVM console. Parsed and checked programs stay cached; supported numeric code is JIT-compiled in memory. Use :quit to exit.");
        while (true) {
            try {
                String line;
                if (console != null) line = console.readLine(">>> ");
                else { out.print(">>> "); out.flush(); line = reader.readLine(); }
                if (line == null || line.strip().equals(":quit") || line.strip().equals(".exit")) return 0;
                String command = line.strip();
                if (command.startsWith(">>>")) command = command.substring(3).stripLeading();
                if (command.startsWith("falcon ")) command = command.substring(7).stripLeading();
                if (command.isBlank()) continue;
                List<String> words;
                try { words = splitCommand(command); }
                catch (IllegalArgumentException error) { err.println("Falcon console: " + error.getMessage()); continue; }
                if (words.isEmpty()) continue;
                String first = words.get(0);
                if (first.length() >= 2 && "build".startsWith(first)) words.set(0, "build");
                if (words.get(0).equals("build") && words.size() > 1 && words.get(1).endsWith("."))
                    words.set(1, words.get(1) + "fc");
                int status = cli(words.toArray(String[]::new), in, out, err, compilationCache);
                if (status != 0) out.println("Falcon command finished with code " + status + ".");
            } catch (IOException error) {
                err.println("Couldn't read console input: " + error.getMessage()); return 1;
            }
        }
    }

    private static List<String> splitCommand(String command) {
        var words = new java.util.ArrayList<String>();
        var current = new StringBuilder();
        boolean quoted = false; char quote = 0;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if ((c == '"' || c == '\'') && (!quoted || c == quote)) {
                if (quoted) quoted = false; else { quoted = true; quote = c; }
            } else if (Character.isWhitespace(c) && !quoted) {
                if (!current.isEmpty()) { words.add(current.toString()); current.setLength(0); }
            } else current.append(c);
        }
        if (quoted) throw new IllegalArgumentException("close the quote in the command.");
        if (!current.isEmpty()) words.add(current.toString());
        return words;
    }
}
