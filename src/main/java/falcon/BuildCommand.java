package falcon;

import java.io.IOException;
import java.io.InputStream;
import java.io.BufferedInputStream;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.tools.ToolProvider;
import static falcon.Ast.*;

/** Packages a checked, self-contained Falcon source file as a jpackage app image. */
final class BuildCommand {
    private BuildCommand() {}

    record Options(String name, Path icon, Path output, boolean outside, boolean noConsole, boolean requireAdmin,
                   boolean oneFile, boolean dontClose, boolean force, boolean bruteForce, boolean clean,
                   boolean dryRun, boolean verbose) {}

    static int build(Path sourcePath, String defaults, Options options,
                     PrintStream out, PrintStream err) throws Exception {
        Path source = sourcePath.toAbsolutePath().normalize();
        long buildStarted = System.nanoTime();
        long checkStarted = System.nanoTime();
        List<String> warnings = new ArrayList<>();
        out.println("Falcon build: checking " + source.getFileName() + " and its modules...");
        if (!Files.isRegularFile(source)) throw new IOException("file not found: " + source);
        String programSource = Files.readString(source, StandardCharsets.UTF_8);
        ModuleLoader loader = new ModuleLoader();
        Program program;
        try { program = loader.load(source, programSource); }
        catch (Exception failure) {
            if (!options.bruteForce()) throw failure;
            warnings.add("Source/module loading failed; the executable may fail when run: " + failure.getMessage());
            try { program = new Parser(new Lexer(programSource, source.toString()).scan()).parse(); }
            catch (Exception parseFailure) {
                warnings.add("Parsing failed; the executable may fail when run: " + parseFailure.getMessage());
                program = new Program(List.of(), List.of(), List.of());
            }
        }
        Checker checker = new Checker();
        try { checker.check(program, true); }
        catch (Exception failure) {
            if (!options.bruteForce()) throw failure;
            warnings.add("The checker failed; the executable may fail when run: " + failure.getMessage());
        }
        if (!checker.errors.isEmpty()) {
            if (!options.bruteForce()) {
                for (FalconError error : checker.errors) err.println(loader.diagnostic(error, source.toString(), programSource));
                return 1;
            }
            for (FalconError error : checker.errors) warnings.add(loader.diagnostic(error, source.toString(), programSource));
            out.println("Falcon build warning: source has checker errors; --bruteforce will package it anyway.");
        }
        if (warnings.isEmpty()) out.println("Falcon build: source check passed.");
        else {
            out.println("Falcon build warning: source checks were bypassed; the output may not run correctly.");
            for (String warning : warnings) out.println("Falcon build warning: " + warning);
        }
        if (options.verbose()) out.println("Falcon build: source check completed in " + duration(System.nanoTime() - checkStarted) + ".");

        String name = options.name() == null ? source.getFileName().toString().replaceFirst("\\.[^.]+$", "") : options.name();
        if (!name.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) throw new IOException("choose a filename using letters, numbers, dots, dashes, or underscores.");
        Path projectRoot = projectRoot(source);
        Path localData = Path.of(System.getenv().getOrDefault("LOCALAPPDATA", System.getProperty("user.home")));
        Path defaultOutput = options.outside()
                ? localData.resolve("Falcon").resolve("builds").resolve(name)
                : projectRoot.resolve("build").resolve(name);
        Path outputDirectory = options.output() == null ? defaultOutput : options.output().toAbsolutePath().normalize();
        if (options.outside() && (outputDirectory.startsWith(projectRoot) || projectRoot.startsWith(outputDirectory)))
            throw new IOException("--outside requires --out to be outside the project folder.");
        if (options.outside() && outputDirectory.toString().toLowerCase(java.util.Locale.ROOT).contains("onedrive"))
            throw new IOException("--outside requires a folder outside OneDrive; try %LOCALAPPDATA%\\Falcon\\builds.");
        if (options.dryRun()) {
            out.println("Falcon build: output folder would be " + outputDirectory + ".");
            return dryRun(projectRoot, out);
        }
        out.println("Falcon build: preparing the app image in " + outputDirectory + "...");
        Files.createDirectories(outputDirectory);
        Path finalBin = outputDirectory.resolve("bin");
        if (options.clean()) {
            deleteTree(finalBin);
        }
        Path finalExecutable = finalBin.resolve(name + ".exe");
        if (!options.clean() && (Files.exists(finalBin) || Files.exists(finalExecutable)))
            throw new IOException("build output already exists at " + finalBin + "; use --clean to replace it.");
        Path work = Files.createTempDirectory("falcon-jpackage-");
        boolean buildSucceeded = false;
        try {
            Path appImageDirectory = Files.createDirectories(work.resolve("jpackage-output"));
            Path input = Files.createDirectories(work.resolve("input"));
            Path bootstrapSource = work.resolve("FalconProgramBootstrap.java");
            Path classes = Files.createDirectories(work.resolve("classes"));
            Path bootstrapJar = input.resolve("bootstrap.jar");
            Path falconJar = currentJar();
            Files.copy(falconJar, input.resolve("falcon.jar"));
            String relativeProgram = projectRoot.relativize(source).toString().replace('\\', '/');
            String bootstrap = bootstrapSource(defaults, relativeProgram, options.dontClose());
            Files.writeString(bootstrapSource, bootstrap, StandardCharsets.UTF_8);
            var compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null) throw new IOException("building an executable requires a JDK with javac.");
            long launcherStarted = System.nanoTime();
            out.println("Falcon build: compiling the small Java launcher...");
            int compile = compiler.run(null, null, null, "-encoding", "UTF-8", "-classpath", input.resolve("falcon.jar").toString(),
                    "-d", classes.toString(), bootstrapSource.toString());
            if (compile != 0) throw new IOException("couldn't compile the small executable launcher.");
            out.println("Falcon build: launcher compiled in " + duration(System.nanoTime() - launcherStarted) + ".");
            long bundleStarted = System.nanoTime();
            out.println("Falcon build: bundling project files with fast compression...");
            try (var jar = new JarOutputStream(Files.newOutputStream(bootstrapJar))) {
                Path classFile = classes.resolve("falcon").resolve("FalconProgramBootstrap.class");
                jar.putNextEntry(new JarEntry("falcon/FalconProgramBootstrap.class"));
                Files.copy(classFile, jar);
                jar.closeEntry();
                // The nested project ZIP is already compressed. Avoid deflating it again in the outer JAR.
                jar.setLevel(java.util.zip.Deflater.NO_COMPRESSION);
                jar.putNextEntry(new JarEntry("project.zip"));
                writeProjectZip(jar, projectRoot, outputDirectory, options.force() || options.bruteForce(),
                        options.bruteForce(), work.resolve("force-stage"), warnings, out);
                jar.closeEntry();
                jar.setLevel(java.util.zip.Deflater.BEST_SPEED);
                jar.putNextEntry(new JarEntry("META-INF/MANIFEST.MF"));
                jar.write(("Manifest-Version: 1.0\r\nMain-Class: falcon.FalconProgramBootstrap\r\nClass-Path: falcon.jar\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
                jar.closeEntry();
            }
            out.println("Falcon build: project bundle ready in " + duration(System.nanoTime() - bundleStarted) + ".");
            Path jpackage = findTool("jpackage");
            var builder = new ArrayList<>(List.of(jpackage.toString(), "--type", "app-image", "--name", name,
                    "--input", input.toString(), "--main-jar", "bootstrap.jar", "--main-class", "falcon.FalconProgramBootstrap",
                    "--dest", appImageDirectory.toString()));
            if (options.icon() != null) { builder.add("--icon"); builder.add(options.icon().toAbsolutePath().normalize().toString()); }
            if (isWindows() && !options.noConsole()) builder.add("--win-console");
            if (Runtime.version().feature() >= 24) {
                builder.add("--java-options");
                builder.add("--enable-native-access=ALL-UNNAMED");
            }
            long packageStarted = System.nanoTime();
            out.println("Falcon build: creating the app image with its bundled JVM; this can take a little while...");
            Process process = new ProcessBuilder(builder).inheritIO().start();
            int status = process.waitFor();
            if (status != 0) throw new IOException("jpackage exited with code " + status + ".");
            Path appImage = appImageDirectory.resolve(name);
            Path launcher = appImage.resolve(name + (isWindows() ? ".exe" : ""));
            if (options.requireAdmin() && !options.oneFile()) {
                if (!isWindows()) throw new IOException("--req-admin only works on Windows.");
                embedAdminManifest(launcher, work);
            }
            Path executable = launcher;
            if (options.oneFile()) {
                if (!isWindows()) throw new IOException("--one-file only works on Windows.");
                out.println("Falcon build: creating the self-extracting executable; startup will be slower while it unpacks...");
                Files.createDirectories(finalBin);
                long selfExtractStarted = System.nanoTime();
                executable = createSelfExtractor(name, appImage, finalBin, work, options.icon());
                if (options.requireAdmin()) embedAdminManifest(executable, work);
                if (options.verbose()) out.println("Falcon build: self-extractor completed in " + duration(System.nanoTime() - selfExtractStarted) + ".");
            } else {
                Files.createDirectories(finalBin);
                try (var entries = Files.list(appImage)) {
                    for (Path entry : entries.toList()) Files.move(entry, finalBin.resolve(entry.getFileName()));
                }
                Files.delete(appImage);
                executable = finalBin.resolve(name + (isWindows() ? ".exe" : ""));
            }
            if (options.verbose()) out.println("Falcon build: jpackage completed in " + duration(System.nanoTime() - packageStarted) + ".");
            out.println("Falcon build: finished in " + duration(System.nanoTime() - buildStarted) + ".");
            if (!warnings.isEmpty()) {
                Path report = executable.getParent().resolve("BUILD-WITH-ERRORS.txt");
                writeWarnings(report, warnings);
                out.println("Falcon build warning report: " + report);
            }
            out.println("Built executable: " + executable);
            if (options.oneFile()) out.println("Startup is slower because this file unpacks the app into a temporary folder first.");
            else out.println("The app image includes its JVM and Falcon runtime. Move the whole 'bin' folder to distribute it.");
            buildSucceeded = true;
            return 0;
        } catch (Exception failure) {
            deleteTree(finalBin);
            throw failure;
        } finally {
            try { deleteTree(work); }
            catch (IOException cleanupFailure) {
                boolean closed = closeBuildProcesses(work, out);
                boolean removed = false;
                if (closed) {
                    try { deleteTree(work); removed = true; }
                    catch (IOException retryFailure) { cleanupFailure = retryFailure; }
                }
                if (!removed) {
                    String message = "Temporary build files could not be removed from " + work + ": " + cleanupFailure.getMessage();
                    if (buildSucceeded) out.println("Falcon build warning: executable built successfully, but " + message);
                    else err.println("Falcon build warning: " + message);
                }
            }
        }
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

    private static String bootstrapSource(String defaults, String relativeProgram, boolean dontClose) {
        List<String> arguments = splitArguments(defaults);
        String literals = arguments.stream().map(BuildCommand::javaString).collect(java.util.stream.Collectors.joining(", "));
        return "package falcon; public final class FalconProgramBootstrap { public static void main(String[] args) throws Exception {"
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
                + " command.addAll(java.util.List.of(args)); int status = Main.cli(command.toArray(String[]::new), System.in, System.out, System.err);"
                + (dontClose ? " System.out.println(\"\\nFinished. Press Enter to close...\"); System.in.read();" : "")
                + " if (status != 0) System.exit(status);"
                + " } }";
    }

    private static void writeProjectZip(JarOutputStream jar, Path projectRoot, Path outputDirectory,
            boolean force, boolean bruteForce, Path staging, List<String> warnings, PrintStream out) throws IOException {
        var sink = new java.io.FilterOutputStream(jar) {
            @Override public void close() throws IOException { flush(); }
        };
        try (var zip = new ZipOutputStream(sink)) {
            zip.setLevel(java.util.zip.Deflater.BEST_SPEED);
            final Set<String> generatedDirectories = Set.of(".git", "build", "dist", "target", "node_modules",
                    ".idea", ".vscode", ".falcon-cache", ".venv", "venv", "__pycache__",
                    ".pytest_cache", ".mypy_cache", ".ruff_cache");
            class ProjectZipper extends SimpleFileVisitor<Path> {
                private int packagedFiles;

                @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    if (!directory.equals(projectRoot)) {
                        if (directory.startsWith(outputDirectory)) return FileVisitResult.SKIP_SUBTREE;
                        String folderName = directory.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                        if (generatedDirectories.contains(folderName) || folderName.endsWith("_extracted"))
                            return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException {
                    if (!attributes.isRegularFile() || path.startsWith(outputDirectory)) return FileVisitResult.CONTINUE;
                    Path relative = projectRoot.relativize(path);
                    Path archivedPath = path;
                    if (force) {
                        archivedPath = staging.resolve(relative).normalize();
                        try {
                            Files.createDirectories(archivedPath.getParent());
                            Files.copy(path, archivedPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        } catch (IOException failure) {
                            if (!bruteForce && relative.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".fc")) throw failure;
                            String warning = "Skipped unreadable project file '" + relative + "': " + failure.getMessage();
                            warnings.add(warning);
                            out.println("Falcon build warning: " + warning);
                            return FileVisitResult.CONTINUE;
                        }
                    }
                    ZipEntry entry = new ZipEntry(relative.toString().replace('\\', '/'));
                    long fileSize = Files.size(archivedPath);
                    if (isPrecompressed(relative, fileSize)) {
                        out.println("Falcon build: including large precompressed file " + relative + " without recompressing it...");
                        out.flush();
                        entry.setMethod(ZipEntry.STORED);
                        entry.setSize(fileSize);
                        entry.setCompressedSize(fileSize);
                        entry.setCrc(crc32(archivedPath));
                    }
                    zip.putNextEntry(entry);
                    Files.copy(archivedPath, zip);
                    zip.closeEntry();
                    packagedFiles++;
                    if (fileSize >= 10L * 1024 * 1024) {
                        out.printf("Falcon build: included large file %s (%.1f MiB).%n", relative,
                                fileSize / (1024.0 * 1024.0));
                        out.flush();
                    }
                    if (packagedFiles % 100 == 0) {
                        out.println("Falcon build: bundled " + packagedFiles + " project files...");
                        out.flush();
                    }
                    return FileVisitResult.CONTINUE;
                }

                int packagedFiles() { return packagedFiles; }
                @Override public FileVisitResult visitFileFailed(Path file, IOException failure) throws IOException {
                    Path relative = projectRoot.relativize(file);
                    if (!force || (!bruteForce && relative.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".fc"))) throw failure;
                    String warning = "Skipped inaccessible project path '" + relative + "': " + failure.getMessage();
                    warnings.add(warning);
                    out.println("Falcon build warning: " + warning);
                    return FileVisitResult.CONTINUE;
                }
            }
            ProjectZipper visitor = new ProjectZipper();
            Files.walkFileTree(projectRoot, visitor);
            out.println("Falcon build: bundled " + visitor.packagedFiles() + " project files.");
        }
    }

    private static boolean isPrecompressed(Path path, long size) {
        if (size < 1024L * 1024L) return false;
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return List.of(".exe", ".dll", ".jar", ".zip", ".7z", ".gz", ".png", ".jpg", ".jpeg",
                ".webp", ".mp3", ".mp4", ".pdf", ".ico").stream().anyMatch(name::endsWith);
    }

    private static int dryRun(Path projectRoot, PrintStream out) throws IOException {
        Set<String> excluded = Set.of(".git", "build", "dist", "target", "node_modules", ".idea", ".vscode",
                ".falcon-cache", ".venv", "venv", "__pycache__", ".pytest_cache", ".mypy_cache", ".ruff_cache");
        long[] total = {0};
        int[] count = {0};
        out.println("Files that would be bundled:");
        Files.walkFileTree(projectRoot, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(projectRoot)) {
                    String n = dir.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                    if (excluded.contains(n) || n.endsWith("_extracted")) return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile()) {
                    out.printf("  %s (%d bytes)%n", projectRoot.relativize(file), attrs.size());
                    total[0] += attrs.size(); count[0]++;
                }
                return FileVisitResult.CONTINUE;
            }
        });
        out.printf("Total: %d files, %d bytes (%.2f MiB). No build output was created.%n",
                count[0], total[0], total[0] / (1024.0 * 1024.0));
        return 0;
    }

    private static void embedAdminManifest(Path launcher, Path work) throws IOException, InterruptedException {
        Path mt = findMt();
        Path manifest = work.resolve("require-admin.manifest");
        Path editableLauncher = work.resolve("falcon-admin-launcher.exe");
        Files.copy(launcher, editableLauncher, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.setAttribute(editableLauncher, "dos:readonly", false);
        Files.writeString(manifest, "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<assembly xmlns=\"urn:schemas-microsoft-com:asm.v1\" manifestVersion=\"1.0\"><assemblyIdentity version=\"1.0.0.0\" processorArchitecture=\"*\" name=\"FalconProgram\" type=\"win32\"/><trustInfo xmlns=\"urn:schemas-microsoft-com:asm.v3\"><security><requestedPrivileges><requestedExecutionLevel level=\"requireAdministrator\" uiAccess=\"false\"/></requestedPrivileges></security></trustInfo></assembly>", StandardCharsets.UTF_8);
        Process process = new ProcessBuilder(mt.toString(), "-manifest", manifest.toString(),
                "-outputresource:" + editableLauncher + ";#1").inheritIO().start();
        if (process.waitFor() != 0) throw new IOException("mt.exe could not embed the administrator manifest in " + launcher);
        Files.setAttribute(launcher, "dos:readonly", false);
        Files.copy(editableLauncher, launcher, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static Path findMt() throws IOException {
        String sdk = System.getenv("WindowsSdkDir");
        if (sdk != null) {
            Path candidate = Path.of(sdk, "bin", "x64", "mt.exe");
            if (Files.isRegularFile(candidate)) return candidate;
        }
        Path kits = Path.of(System.getenv().getOrDefault("ProgramFiles(x86)", "C:\\Program Files (x86)"), "Windows Kits", "10", "bin");
        if (Files.isDirectory(kits)) try (var versions = Files.list(kits)) {
            for (Path version : versions.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Path candidate = version.resolve("x64").resolve("mt.exe");
                if (Files.isRegularFile(candidate)) return candidate;
            }
        }
        throw new IOException("--req-admin needs mt.exe from the Windows SDK. Install the Windows 10/11 SDK and try again.");
    }

    private static Path createSelfExtractor(String name, Path appImage, Path outputDirectory, Path work, Path icon)
            throws IOException, InterruptedException {
        Path payload = work.resolve("payload.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(payload))) {
            zip.setLevel(java.util.zip.Deflater.BEST_SPEED);
            try (var paths = Files.walk(appImage)) {
                for (Path path : paths.filter(Files::isRegularFile).toList()) {
                    Path relative = appImage.getParent().relativize(path);
                    zip.putNextEntry(new ZipEntry(relative.toString().replace('\\', '/')));
                    Files.copy(path, zip);
                    zip.closeEntry();
                }
            }
        }
        Path launcherScript = work.resolve("launch.cmd");
        String tempFolder = "%TEMP%\\Falcon-" + java.util.UUID.randomUUID().toString().replace("-", "");
        Files.writeString(launcherScript, "@echo off\r\nsetlocal\r\nset \"FALCON_TMP=" + tempFolder + "\"\r\n"
                + "mkdir \"%FALCON_TMP%\"\r\n"
                + "powershell.exe -NoProfile -ExecutionPolicy Bypass -Command \"Expand-Archive -LiteralPath '%~dp0payload.zip' -DestinationPath '%FALCON_TMP%' -Force\"\r\n"
                + "if errorlevel 1 exit /b 1\r\n"
                + "\"%FALCON_TMP%\\" + name + "\\" + name + ".exe\" %*\r\n"
                + "set FALCON_STATUS=%ERRORLEVEL%\r\nrd /s /q \"%FALCON_TMP%\"\r\nexit /b %FALCON_STATUS%\r\n", StandardCharsets.UTF_8);
        Path sed = work.resolve("falcon.sed");
        Path target = outputDirectory.resolve(name + ".exe");
        String iconLine = icon == null ? "" : "ExtractTitle=" + name + "\r\nExtractIcon=" + icon.toAbsolutePath().normalize() + "\r\n";
        Files.writeString(sed, "[Version]\r\nClass=IEXPRESS\r\nSEDVersion=3\r\n[Options]\r\nPackagePurpose=InstallApp\r\n"
                + "ShowInstallProgramWindow=0\r\nHideExtractAnimation=1\r\nUseLongFileName=1\r\nInsideCompressed=0\r\n"
                + "CAB_FixedSize=0\r\nCAB_ResvCodeSigning=0\r\nRebootMode=N\r\nInstallPrompt=\r\nDisplayLicense=\r\n"
                + "FinishMessage=\r\nTargetName=" + target + "\r\nFriendlyName=" + name + "\r\nAppLaunched=launch.cmd\r\n"
                + "PostInstallCmd=<None>\r\nAdminQuietInstCmd=\r\nUserQuietInstCmd=\r\nSourceFiles=SourceFiles\r\n" + iconLine
                + "[Strings]\r\nFILE0=\"payload.zip\"\r\nFILE1=\"launch.cmd\"\r\n[SourceFiles]\r\nSourceFiles0=" + work + "\\\r\n"
                + "[SourceFiles0]\r\n%FILE0%=\r\n%FILE1%=\r\n", StandardCharsets.UTF_8);
        Path iexpress = Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"), "System32", "iexpress.exe");
        if (!Files.isRegularFile(iexpress)) throw new IOException("IExpress was not found; --one-file requires the Windows IExpress utility.");
        Process process = new ProcessBuilder(iexpress.toString(), "/N", "/Q", sed.toString()).inheritIO().start();
        if (process.waitFor() != 0 || !Files.isRegularFile(target)) throw new IOException("IExpress failed to create the one-file executable.");
        return target;
    }

    private static void writeWarnings(Path report, List<String> warnings) throws IOException {
        Files.writeString(report, "Falcon build completed with warnings. Review them; source or asset problems can prevent the program from running correctly.\n\n"
                + String.join("\n", warnings) + "\n", StandardCharsets.UTF_8);
    }

    private static boolean closeBuildProcesses(Path work, PrintStream out) {
        if (!isWindows()) return false;
        String script = "$root=[IO.Path]::GetFullPath($env:FALCON_CLEANUP_ROOT); "
                + "Get-CimInstance Win32_Process | Where-Object { $_.ExecutablePath -and "
                + "$_.ExecutablePath.StartsWith($root,[System.StringComparison]::OrdinalIgnoreCase) } | "
                + "ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue; "
                + "Write-Output ('closed=' + $_.ProcessId) }";
        try {
            ProcessBuilder builder = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-Command", script).redirectErrorStream(true);
            builder.environment().put("FALCON_CLEANUP_ROOT", work.toAbsolutePath().normalize().toString());
            Process process = builder.start();
            String result;
            try (InputStream output = process.getInputStream()) {
                result = new String(output.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            if (process.exitValue() != 0 || !result.contains("closed=")) return false;
            out.println("Falcon build: closed the launcher process that was still running from its temporary build folder.");
            try { Thread.sleep(250); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            return true;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static long crc32(Path path) throws IOException {
        CRC32 crc = new CRC32();
        try (InputStream input = new BufferedInputStream(Files.newInputStream(path))) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) crc.update(buffer, 0, read);
        }
        return crc.getValue();
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
    private static String duration(long nanoseconds) {
        long milliseconds = nanoseconds / 1_000_000;
        return milliseconds < 1_000 ? milliseconds + "ms" : String.format(java.util.Locale.ROOT, "%.2fs", milliseconds / 1000.0);
    }
    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var entries = Files.walk(path)) {
            for (Path item : entries.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(item);
        }
    }
}
