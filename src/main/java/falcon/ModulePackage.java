package falcon;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Module archive validation, creation, and safe installation. */
final class ModulePackage {
    private static final int MAX_FILES = 10_000;
    private static final long MAX_UNPACKED_BYTES = 128L * 1024 * 1024;
    private static final Set<String> EXCLUDED_DIRECTORIES = Set.of(".git", ".idea", ".vscode", "build", "target", "node_modules");

    record Metadata(String name, String version, String main) {}

    private ModulePackage() {}

    static Metadata inspect(Path directory, boolean requireVersion) throws IOException {
        Path root = directory.toRealPath();
        if (!Files.isDirectory(root)) throw new IOException("Module source must be a directory.");
        Path manifest = root.resolve("module.json");
        String name = root.getFileName() == null ? "" : root.getFileName().toString();
        String version = "";
        String main = null;
        if (Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
            Object parsed;
            try { parsed = Json.read(Files.readString(manifest, StandardCharsets.UTF_8)); }
            catch (RuntimeException e) { throw new IOException("Invalid module.json: " + e.getMessage(), e); }
            if (!(parsed instanceof Map<?, ?> values)) throw new IOException("module.json must contain a JSON object.");
            if (values.get("name") instanceof String value && !value.isBlank()) name = value.trim();
            if (values.get("version") instanceof String value) version = value.trim();
            if (values.get("main") instanceof String value) main = value;
        }
        if (!name.matches("[A-Za-z0-9_.-]+")) throw new IOException("Module name may contain only letters, numbers, dots, underscores, and hyphens.");
        if (requireVersion && !version.matches("[A-Za-z0-9][A-Za-z0-9._+-]*"))
            throw new IOException("Add a valid string 'version' to module.json before deploying.");
        if (main == null || main.isBlank()) {
            if (Files.isRegularFile(root.resolve("__init__.fc"), LinkOption.NOFOLLOW_LINKS)) main = "__init__.fc";
            else if (Files.isRegularFile(root.resolve("init.fc"), LinkOption.NOFOLLOW_LINKS)) main = "init.fc";
            else throw new IOException("Module needs __init__.fc, init.fc, or a module.json 'main' entry.");
        }
        Path relative;
        try { relative = Path.of(main); }
        catch (RuntimeException e) { throw new IOException("module.json 'main' must be a relative .fc path.", e); }
        Path entry = root.resolve(relative).normalize();
        if (!main.endsWith(".fc") || relative.isAbsolute() || relative.getRoot() != null || !entry.startsWith(root)
                || !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Module entry must be an existing .fc file inside the module folder.");
        return new Metadata(name, version, main);
    }

    static Path createArchive(Path source, Path destination) throws IOException {
        Metadata metadata = inspect(source, true);
        Path root = source.toRealPath();
        List<Path> files;
        try (var paths = Files.walk(root)) {
            files = paths.filter(path -> !path.equals(root))
                    .filter(path -> !excluded(root.relativize(path)))
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .sorted(Comparator.comparing(path -> root.relativize(path).toString()))
                    .toList();
        }
        if (files.size() > MAX_FILES) throw new IOException("Module contains too many files to deploy (limit: " + MAX_FILES + ").");
        if (files.stream().noneMatch(file -> root.resolve(metadata.main()).equals(file)))
            throw new IOException("Module entry was excluded from the deployment archive.");
        Files.createDirectories(destination.toAbsolutePath().getParent());
        try (var output = new ZipOutputStream(Files.newOutputStream(destination))) {
            long total = 0;
            if (!Files.isRegularFile(root.resolve("module.json"), LinkOption.NOFOLLOW_LINKS)) {
                String manifest = "{\"name\":\"" + metadata.name() + "\",\"version\":\"" + metadata.version()
                        + "\",\"main\":\"" + metadata.main().replace("\\", "\\\\").replace("\"", "\\\"") + "\"}\n";
                byte[] bytes = manifest.getBytes(StandardCharsets.UTF_8);
                total += bytes.length;
                output.putNextEntry(new ZipEntry("module.json"));
                output.write(bytes);
                output.closeEntry();
            }
            for (Path file : files) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                if (relative.equals("pom.xml")) continue;
                long size = Files.size(file);
                total += size;
                if (total > MAX_UNPACKED_BYTES) throw new IOException("Module is larger than the 128 MiB deployment limit.");
                output.putNextEntry(new ZipEntry(relative));
                Files.copy(file, output);
                output.closeEntry();
            }
        }
        return destination;
    }

    static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) >= 0) if (count > 0) digest.update(buffer, 0, count);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    static Path install(Path source, String nameOverride, Path projectRoot) throws IOException {
        Path stage = null;
        Path moduleSource;
        if (Files.isDirectory(source)) moduleSource = source.toRealPath();
        else if (Files.isRegularFile(source)) {
            stage = Files.createTempDirectory("falcon-module-install-");
            extractArchive(source, stage);
            moduleSource = stage;
        } else throw new IOException("Local module path does not exist: " + source);
        try {
            Metadata metadata = inspect(moduleSource, false);
            String name = nameOverride == null || nameOverride.isBlank() ? metadata.name() : nameOverride;
            if (!name.matches("[A-Za-z0-9_.-]+")) throw new IOException("Module name may contain only letters, numbers, dots, underscores, and hyphens.");
            Path root = projectRoot.toAbsolutePath().normalize();
            Path modules = root.resolve("modules");
            Path destination = modules.resolve(name).normalize();
            if (!destination.startsWith(modules)) throw new IOException("Invalid module destination.");
            if (Files.exists(destination)) throw new IOException("Module already exists: " + destination);
            Path localFile = root.resolve(name + ".fc");
            if ((Files.isRegularFile(moduleSource.resolve("__init__.fc")) || Files.isRegularFile(moduleSource.resolve("init.fc")))
                    && Files.isRegularFile(localFile))
                throw new IOException("Module name conflict: folder '" + destination + "' contains an initializer and file '" + localFile + "' already exists. Keep one module form.");
            Files.createDirectories(modules);
            try {
                copyContents(moduleSource, destination);
                inspect(destination, false);
            } catch (IOException e) {
                deleteTree(destination);
                throw e;
            }
            return destination;
        } finally { if (stage != null) deleteTree(stage); }
    }

    private static void copyContents(Path source, Path destination) throws IOException {
        Path root = source.toRealPath();
        Files.createDirectories(destination);
        try (var files = Files.walk(root)) {
            for (Path file : files.toList()) {
                if (file.equals(root) || excluded(root.relativize(file))) continue;
                if (Files.isSymbolicLink(file)) throw new IOException("Module contains a symbolic link; remove it before installing.");
                Path relative = root.relativize(file);
                Path target = destination.resolve(relative).normalize();
                if (!target.startsWith(destination)) throw new IOException("Module path escapes its install folder.");
                if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(target);
                else if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(target.getParent());
                    Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private static void extractArchive(Path archive, Path destination) throws IOException {
        int files = 0;
        long total = 0;
        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(archive), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                Path relative;
                try { relative = Path.of(entry.getName()); }
                catch (RuntimeException e) { throw new IOException("Package contains an invalid path.", e); }
                Path output = destination.resolve(relative).normalize();
                if (relative.isAbsolute() || relative.getRoot() != null || !output.startsWith(destination))
                    throw new IOException("Package contains a path outside its root.");
                if (!entry.isDirectory()) {
                    if (++files > MAX_FILES) throw new IOException("Package contains too many files.");
                    Files.createDirectories(output.getParent());
                    try (var stream = Files.newOutputStream(output)) {
                        byte[] buffer = new byte[16 * 1024];
                        int count;
                        while ((count = input.read(buffer)) >= 0) {
                            if (count == 0) continue;
                            total += count;
                            if (total > MAX_UNPACKED_BYTES) throw new IOException("Package is larger than the 128 MiB install limit.");
                            stream.write(buffer, 0, count);
                        }
                    }
                } else Files.createDirectories(output);
                input.closeEntry();
            }
        }
    }

    private static boolean excluded(Path relative) {
        for (Path part : relative) {
            String name = part.toString();
            String lower = name.toLowerCase(Locale.ROOT);
            if (EXCLUDED_DIRECTORIES.contains(lower) || lower.equals(".gitignore")) return true;
            if (lower.equals(".env") || lower.startsWith(".env.") || lower.equals("credentials.json")
                    || lower.startsWith("secrets.") || lower.endsWith(".pem") || lower.endsWith(".p12") || lower.endsWith(".pfx")) return true;
        }
        return false;
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
