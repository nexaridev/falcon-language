package falcon;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Direct GitHub Releases API transport for versioned Falcon module archives. */
final class GitHubReleaseClient {
    static final String DEFAULT_API = "https://api.github.com";
    private final HttpClient http;
    private final String token;
    private final String apiBase;

    GitHubReleaseClient(String token) { this(token, DEFAULT_API); }

    GitHubReleaseClient(String token, String apiBase) {
        this.token = token;
        this.apiBase = apiBase.replaceAll("/+$", "");
        this.http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(8)).build();
    }

    static String token() throws IOException {
        return token(System.getenv(), configFile());
    }

    static String token(Map<String, String> environment, Path config) throws IOException {
        for (String name : List.of("FALCON_GITHUB_TOKEN", "GITHUB_TOKEN")) {
            String value = environment.get(name);
            if (value != null && !value.isBlank()) return value.trim();
        }
        if (Files.isRegularFile(config)) {
            var properties = new java.util.Properties();
            try (var input = Files.newInputStream(config)) { properties.load(input); }
            String value = properties.getProperty("github.token", "").trim();
            if (!value.isBlank()) return value;
        }
        return null;
    }

    static Path configFile() {
        String override = System.getenv("FALCON_CONFIG");
        if (override != null && !override.isBlank()) return Path.of(override).toAbsolutePath().normalize();
        return Path.of(System.getProperty("user.home"), ".falcon", "config.properties");
    }

    static void saveToken(String token, Path config) throws IOException {
        if (token == null || token.isBlank()) throw new IOException("Token cannot be empty.");
        Path absolute = config.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent != null) Files.createDirectories(parent);
        var properties = new java.util.Properties();
        if (Files.isRegularFile(absolute)) {
            try (var input = Files.newInputStream(absolute)) { properties.load(input); }
        }
        properties.setProperty("github.token", token.trim());
        try (var output = Files.newOutputStream(absolute)) { properties.store(output, "Falcon user settings"); }
        setPrivatePermissions(absolute, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        if (parent != null) setPrivatePermissions(parent, Set.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
    }

    static void clearToken(Path config) throws IOException {
        Path absolute = config.toAbsolutePath().normalize();
        if (!Files.isRegularFile(absolute)) return;
        var properties = new java.util.Properties();
        try (var input = Files.newInputStream(absolute)) { properties.load(input); }
        properties.remove("github.token");
        if (properties.isEmpty()) Files.deleteIfExists(absolute);
        else try (var output = Files.newOutputStream(absolute)) { properties.store(output, "Falcon user settings"); }
    }

    private static void setPrivatePermissions(Path path, Set<PosixFilePermission> permissions) {
        try { Files.setPosixFilePermissions(path, permissions); }
        catch (IOException | UnsupportedOperationException ignored) { /* Windows uses the user's profile ACL. */ }
    }

    static String discoverRepository(Path directory) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("git", "remote", "get-url", "origin")
                .directory(directory.toFile()).redirectErrorStream(true).start();
        byte[] bytes = process.getInputStream().readAllBytes();
        if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Timed out while reading the Git origin URL.");
        }
        String remote = new String(bytes, StandardCharsets.UTF_8).trim();
        if (process.exitValue() != 0) return null;
        var matcher = java.util.regex.Pattern.compile("(?:https?://|git@|ssh://git@)github\\.com[:/]([^/]+/[^/]+?)(?:\\.git)?$")
                .matcher(remote);
        if (!matcher.find()) return null;
        return validateRepository(matcher.group(1));
    }

    static String validateRepository(String repository) {
        if (repository == null || !repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))
            throw new IllegalArgumentException("Repository must be in owner/name form.");
        return repository.toLowerCase(java.util.Locale.ROOT);
    }

    String createRelease(String repository, String tag, String title, String body) throws IOException, InterruptedException {
        requireToken("publishing");
        repository = validateRepository(repository);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tag_name", tag);
        payload.put("name", title);
        payload.put("body", body);
        payload.put("draft", false);
        payload.put("prerelease", false);
        HttpResponse<String> response = sendJson("POST", api("/repos/" + repository + "/releases"), Json.write(payload));
        requireStatus(response.statusCode(), response.body(), 201, "creating release '" + tag + "'");
        Object parsed = Json.read(response.body());
        if (!(parsed instanceof Map<?, ?> result) || !(result.get("upload_url") instanceof String uploadUrl))
            throw new IOException("GitHub created the release but did not return an asset upload URL.");
        return uploadUrl.replaceFirst("\\{.*$", "");
    }

    void uploadAsset(String uploadUrl, String name, String contentType, Path file) throws IOException, InterruptedException {
        String separator = uploadUrl.contains("?") ? "&" : "?";
        URI uri = URI.create(uploadUrl + separator + "name=" + encode(name));
        HttpRequest.Builder request = baseRequest(uri).timeout(Duration.ofMinutes(3))
                .header("Content-Type", contentType)
                .PUT(HttpRequest.BodyPublishers.ofFile(file));
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        requireStatus(response.statusCode(), response.body(), 201, "uploading asset '" + name + "'");
    }

    Path downloadReleasePackage(String repository, String tag, Path temporaryDirectory) throws IOException, InterruptedException {
        repository = validateRepository(repository);
        HttpResponse<String> releaseResponse = sendJson("GET", api("/repos/" + repository + "/releases/tags/" + encode(tag)), null);
        requireStatus(releaseResponse.statusCode(), releaseResponse.body(), 200, "looking up release '" + tag + "'");
        Object decoded = Json.read(releaseResponse.body());
        if (!(decoded instanceof Map<?, ?> release) || !(release.get("assets") instanceof List<?> assets))
            throw new IOException("GitHub returned a release without an asset list.");
        Map<?, ?> packageAsset = null;
        Map<?, ?> checksumAsset = null;
        for (Object value : assets) {
            if (!(value instanceof Map<?, ?> asset) || !(asset.get("name") instanceof String name)) continue;
            if (name.endsWith(".falconpkg")) {
                if (packageAsset != null) throw new IOException("This release has multiple .falconpkg assets; publish one module per release tag.");
                packageAsset = asset;
            }
        }
        if (packageAsset == null || !(packageAsset.get("id") instanceof Number assetId))
            throw new IOException("Release '" + tag + "' has no .falconpkg module asset.");
        String archiveName = (String) packageAsset.get("name");
        for (Object value : assets) {
            if (value instanceof Map<?, ?> asset && (archiveName + ".sha256").equals(asset.get("name"))) {
                checksumAsset = asset;
                break;
            }
        }
        if (checksumAsset == null || !(checksumAsset.get("id") instanceof Number checksumId))
            throw new IOException("Release '" + tag + "' is missing its SHA-256 checksum asset.");

        Files.createDirectories(temporaryDirectory);
        Path archive = temporaryDirectory.resolve(archiveName);
        Path checksum = temporaryDirectory.resolve(archiveName + ".sha256");
        try {
            downloadAsset(repository, assetId.longValue(), archive);
            downloadAsset(repository, checksumId.longValue(), checksum);
            String expected = Files.readString(checksum, StandardCharsets.UTF_8).trim().split("\\s+")[0];
            String actual = ModulePackage.sha256(archive);
            if (!actual.equalsIgnoreCase(expected)) throw new IOException("Downloaded module failed its SHA-256 check.");
            return archive;
        } catch (IOException | InterruptedException e) {
            Files.deleteIfExists(archive);
            throw e;
        } finally { Files.deleteIfExists(checksum); }
    }

    private void downloadAsset(String repository, long id, Path destination) throws IOException, InterruptedException {
        HttpRequest.Builder request = baseRequest(api("/repos/" + repository + "/releases/assets/" + id))
                .header("Accept", "application/octet-stream").timeout(Duration.ofMinutes(3)).GET();
        HttpResponse<byte[]> response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            String message = new String(response.body(), StandardCharsets.UTF_8);
            throw apiError(response.statusCode(), message, "downloading release asset");
        }
        Files.write(destination, response.body());
    }

    private HttpResponse<String> sendJson(String method, URI uri, String json) throws IOException, InterruptedException {
        HttpRequest.Builder request = baseRequest(uri).header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28");
        if (json == null) request.GET();
        else request.header("Content-Type", "application/json; charset=utf-8")
                .method(method, HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpRequest.Builder baseRequest(URI uri) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).header("User-Agent", "Falcon-Module-CLI");
        if (token != null && !token.isBlank()) request.header("Authorization", "Bearer " + token);
        return request;
    }

    private URI api(String path) { return URI.create(apiBase + path); }
    private void requireToken(String action) throws IOException {
        if (token == null || token.isBlank()) throw new IOException("A GitHub token is required for " + action
                + ". Set FALCON_GITHUB_TOKEN, GITHUB_TOKEN, or github.token in " + configFile() + ".");
    }

    private static void requireStatus(int status, String body, int expected, String action) throws IOException {
        if (status != expected) throw apiError(status, body, action);
    }

    private static IOException apiError(int status, String body, String action) {
        String message = "";
        try {
            Object parsed = Json.read(body);
            if (parsed instanceof Map<?, ?> result && result.get("message") instanceof String text) message = text;
        } catch (RuntimeException ignored) { }
        if (message.isBlank()) message = body.replaceAll("\\s+", " ").trim();
        if (message.length() > 400) message = message.substring(0, 400) + "…";
        return new IOException("GitHub API returned HTTP " + status + " while " + action + (message.isBlank() ? "." : ": " + message));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
