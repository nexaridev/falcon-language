package falcon;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Command-level orchestration for direct release publishing and installation. */
final class PackageCommands {
    private PackageCommands() {}

    static int deploy(Path moduleDirectory, String repository, String token, String apiBase,
                      java.io.PrintStream out, java.io.PrintStream err) {
        Path archive = null;
        Path checksum = null;
        try {
            if (token == null || token.isBlank()) throw new IOException("No GitHub token was found. Set FALCON_GITHUB_TOKEN, GITHUB_TOKEN, or github.token in " + GitHubReleaseClient.configFile() + ".");
            repository = GitHubReleaseClient.validateRepository(repository);
            ModulePackage.Metadata metadata = ModulePackage.inspect(moduleDirectory, true);
            String asset = metadata.name() + "-" + metadata.version() + ".falconpkg";
            String tag = metadata.name() + "-v" + metadata.version();
            Path temporary = Files.createTempDirectory("falcon-deploy-");
            archive = temporary.resolve(asset);
            checksum = temporary.resolve(asset + ".sha256");
            try {
                out.println("Falcon deploy: validating " + moduleDirectory + "...");
                ModulePackage.createArchive(moduleDirectory, archive);
                String hash = ModulePackage.sha256(archive);
                Files.writeString(checksum, hash + "  " + asset + "\n", StandardCharsets.UTF_8);
                out.println("Falcon deploy: packaging " + metadata.name() + " " + metadata.version() + " (" + Files.size(archive) + " bytes)...");
                GitHubReleaseClient client = new GitHubReleaseClient(token, apiBase);
                String uploadUrl = client.createRelease(repository, tag,
                        "Falcon module " + metadata.name() + " " + metadata.version(),
                        "Falcon module package " + metadata.name() + " " + metadata.version() + ".\nSHA-256: " + hash);
                client.uploadAsset(uploadUrl, asset, "application/zip", archive);
                client.uploadAsset(uploadUrl, asset + ".sha256", "text/plain; charset=utf-8", checksum);
                out.println("Falcon deploy: published release " + tag + " in " + repository + ".");
                out.println("Install with: falcon install " + repository + " --release " + tag);
                return 0;
            } finally {
                Files.deleteIfExists(archive);
                Files.deleteIfExists(checksum);
                Files.deleteIfExists(temporary);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            err.println("Falcon deploy was interrupted.");
            return 1;
        } catch (Exception e) {
            err.println("Falcon deploy failed: " + e.getMessage());
            return 1;
        }
    }

    static int installLocal(Path source, String nameOverride, Path projectRoot,
                            java.io.PrintStream out, java.io.PrintStream err) {
        try {
            out.println("Falcon install: checking local module " + source + "...");
            Path installed = ModulePackage.install(source, nameOverride, projectRoot);
            out.println("Falcon install: installed at " + installed);
            out.println("Import it with: use \"" + installed.getFileName() + "\" as " + installed.getFileName());
            return 0;
        } catch (Exception e) {
            err.println("Falcon install failed: " + e.getMessage());
            return 1;
        }
    }

    static int installRelease(String repository, String releaseTag, String nameOverride,
                              String token, String apiBase, Path projectRoot,
                              java.io.PrintStream out, java.io.PrintStream err) {
        Path temporary = null;
        try {
            repository = GitHubReleaseClient.validateRepository(repository);
            temporary = Files.createTempDirectory("falcon-install-");
            out.println("Falcon install: downloading release " + releaseTag + " from " + repository + "...");
            Path archive = new GitHubReleaseClient(token, apiBase).downloadReleasePackage(repository, releaseTag, temporary);
            out.println("Falcon install: verifying the package checksum and module entry...");
            Path installed = ModulePackage.install(archive, nameOverride, projectRoot);
            out.println("Falcon install: installed at " + installed);
            out.println("Import it with: use \"" + installed.getFileName() + "\" as " + installed.getFileName());
            return 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            err.println("Falcon install was interrupted.");
            return 1;
        } catch (Exception e) {
            err.println("Falcon install failed: " + e.getMessage());
            return 1;
        } finally {
            if (temporary != null) {
                try (var files = Files.walk(temporary)) {
                    for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
                } catch (IOException ignored) { }
            }
        }
    }

    static String repositoryFromModule(Path moduleDirectory, Path workingDirectory) throws IOException, InterruptedException {
        String explicit = System.getenv("FALCON_GITHUB_REPOSITORY");
        if (explicit != null && !explicit.isBlank()) return GitHubReleaseClient.validateRepository(explicit.trim());
        String repository = GitHubReleaseClient.discoverRepository(workingDirectory);
        if (repository == null) throw new IOException("Can't detect a GitHub origin. Use --repo owner/repository.");
        return repository;
    }
}
