package falcon;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class PackageTest {
    private static int passed;
    private PackageTest() {}

    static int run() throws Exception {
        localFolderInstall();
        localArchiveInstallAndTraversalGuard();
        tokenSources();
        directReleaseDeployAndInstall();
        return passed;
    }

    private static void localFolderInstall() throws Exception {
        Path root = Files.createTempDirectory("falcon local package ");
        try {
            Path module = createModule(root.resolve("source"));
            Path installed = ModulePackage.install(module, null, root.resolve("project"));
            check(installed.endsWith(Path.of("modules", "logging")), "folder install destination");
            check(Files.isRegularFile(installed.resolve("__init__.fc")), "folder initializer installed");
            check(!Files.exists(installed.resolve(".env")), "local install filters environment secrets");
            passed++;
        } finally { deleteTree(root); }
    }

    private static void localArchiveInstallAndTraversalGuard() throws Exception {
        Path root = Files.createTempDirectory("falcon archive package ");
        try {
            Path module = createModule(root.resolve("source"));
            Path archive = root.resolve("logging.falconpkg");
            ModulePackage.createArchive(module, archive);
            Path project = root.resolve("project");
            Path installed = ModulePackage.install(archive, "local-logging", project);
            check(Files.isRegularFile(installed.resolve("__init__.fc")), "archive install initializer");
            check(!Files.exists(installed.resolve(".env")), "archive filters environment secrets");

            Path malicious = root.resolve("malicious.falconpkg");
            try (var zip = new ZipOutputStream(Files.newOutputStream(malicious))) {
                zip.putNextEntry(new ZipEntry("../outside.fc"));
                zip.write("bad".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            try {
                ModulePackage.install(malicious, null, root.resolve("other-project"));
                throw new AssertionError("zip-slip archive was accepted");
            } catch (IOException expected) {
                check(expected.getMessage().contains("outside its root"), "zip-slip rejection diagnostic");
            }
            check(!Files.exists(root.resolve("outside.fc")), "zip-slip wrote no file outside package");
            passed++;
        } finally { deleteTree(root); }
    }

    private static void tokenSources() throws Exception {
        Path root = Files.createTempDirectory("falcon token config ");
        try {
            Path config = root.resolve("config.properties");
            Properties properties = new Properties();
            properties.setProperty("github.token", "config-token");
            try (var output = Files.newOutputStream(config)) { properties.store(output, "test"); }
            check("config-token".equals(GitHubReleaseClient.token(Map.of(), config)), "token reads user config");
            check("environment-token".equals(GitHubReleaseClient.token(Map.of("FALCON_GITHUB_TOKEN", " environment-token ", "GITHUB_TOKEN", "other"), config)), "Falcon token env has priority");
            check("github-token".equals(GitHubReleaseClient.token(Map.of("GITHUB_TOKEN", "github-token"), config)), "GitHub token env fallback");
            GitHubReleaseClient.saveToken("saved-token", config);
            check("saved-token".equals(GitHubReleaseClient.token(Map.of(), config)), "token setup saves for later commands");
            GitHubReleaseClient.clearToken(config);
            check(GitHubReleaseClient.token(Map.of(), config) == null, "token clear removes saved credential");
            passed++;
        } finally { deleteTree(root); }
    }

    private static void directReleaseDeployAndInstall() throws Exception {
        Path root = Files.createTempDirectory("falcon release api ");
        Path module = createModule(root.resolve("logging"));
        Map<String, byte[]> assets = new LinkedHashMap<>();
        AtomicReference<String> createdTag = new AtomicReference<>();
        AtomicReference<String> failure = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/repos/test-owner/test-repo/releases", exchange -> {
            try {
                if (exchange.getRequestMethod().equals("POST") && exchange.getRequestURI().getPath().equals("/repos/test-owner/test-repo/releases")) {
                    verifyAuth(exchange);
                    Object json = Json.read(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    if (!(json instanceof Map<?, ?> body)) throw new AssertionError("release payload was not an object");
                    createdTag.set((String) body.get("tag_name"));
                    respond(exchange, 201, "application/json", ("{\"upload_url\":\"" + base + "/repos/test-owner/test-repo/releases/1/assets{?name,label}\"}").getBytes(StandardCharsets.UTF_8));
                } else if (exchange.getRequestMethod().equals("PUT")) {
                    verifyAuth(exchange);
                    String name = queryValue(exchange.getRequestURI().getRawQuery(), "name");
                    assets.put(name, exchange.getRequestBody().readAllBytes());
                    respond(exchange, 201, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
                } else if (exchange.getRequestMethod().equals("GET") && exchange.getRequestURI().getPath().endsWith("/releases/tags/logging-v1.2.3")) {
                    verifyAuth(exchange);
                    String response = "{\"assets\":[{\"id\":1,\"name\":\"logging-1.2.3.falconpkg\"},{\"id\":2,\"name\":\"logging-1.2.3.falconpkg.sha256\"}]}";
                    respond(exchange, 200, "application/json", response.getBytes(StandardCharsets.UTF_8));
                } else if (exchange.getRequestMethod().equals("GET") && exchange.getRequestURI().getPath().endsWith("/releases/assets/1")) {
                    verifyAuth(exchange);
                    respond(exchange, 200, "application/octet-stream", assets.get("logging-1.2.3.falconpkg"));
                } else if (exchange.getRequestMethod().equals("GET") && exchange.getRequestURI().getPath().endsWith("/releases/assets/2")) {
                    verifyAuth(exchange);
                    respond(exchange, 200, "application/octet-stream", assets.get("logging-1.2.3.falconpkg.sha256"));
                } else respond(exchange, 404, "application/json", "{\"message\":\"not found\"}".getBytes(StandardCharsets.UTF_8));
            } catch (Throwable e) {
                failure.set(e.toString());
                try { respond(exchange, 500, "application/json", "{\"message\":\"test handler failed\"}".getBytes(StandardCharsets.UTF_8)); }
                catch (IOException ignored) { }
            }
        });
        server.start();
        try {
            var output = new ByteArrayOutputStream();
            var stream = new java.io.PrintStream(output, true, StandardCharsets.UTF_8);
            int status = PackageCommands.deploy(module, "test-owner/test-repo", "test-token", base, stream, stream);
            check(status == 0, "direct API deploy succeeds: " + output.toString(StandardCharsets.UTF_8));
            check("logging-v1.2.3".equals(createdTag.get()), "module/version creates a release tag");
            check(assets.keySet().containsAll(java.util.List.of("logging-1.2.3.falconpkg", "logging-1.2.3.falconpkg.sha256")), "package and checksum uploaded");
            Path installed = root.resolve("install-project");
            output.reset();
            status = PackageCommands.installRelease("test-owner/test-repo", "logging-v1.2.3", null, "test-token", base, installed, stream, stream);
            check(status == 0, "direct API release install succeeds: " + output.toString(StandardCharsets.UTF_8));
            check(Files.isRegularFile(installed.resolve("modules/logging/__init__.fc")), "release install lands under modules folder");
            check(failure.get() == null, "mock GitHub API had no request failures: " + failure.get());
            check(!output.toString(StandardCharsets.UTF_8).contains("test-token"), "token is never printed");
            passed++;
        } finally {
            server.stop(0);
            deleteTree(root);
        }
    }

    private static Path createModule(Path directory) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("module.json"), "{\"name\":\"logging\",\"version\":\"1.2.3\",\"main\":\"__init__.fc\"}");
        Files.writeString(directory.resolve("__init__.fc"), "// local test module\nfunc info(message: string) { say(message) }\n");
        Files.writeString(directory.resolve("README.md"), "test module\n");
        Files.writeString(directory.resolve(".env"), "GITHUB_TOKEN=must-not-ship\n");
        Files.createDirectories(directory.resolve("target"));
        Files.writeString(directory.resolve("target/build.txt"), "must-not-ship");
        return directory;
    }

    private static void verifyAuth(HttpExchange exchange) {
        if (!"Bearer test-token".equals(exchange.getRequestHeaders().getFirst("Authorization")))
            throw new AssertionError("API request omitted its bearer token");
    }

    private static String queryValue(String rawQuery, String key) {
        for (String pair : rawQuery.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts[0].equals(key)) return java.net.URLDecoder.decode(parts.length == 2 ? parts[1] : "", StandardCharsets.UTF_8);
        }
        throw new AssertionError("Missing query parameter " + key);
    }

    private static void respond(HttpExchange exchange, int status, String contentType, byte[] bytes) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
