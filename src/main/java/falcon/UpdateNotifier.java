package falcon;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Gives installed users a cached, low-latency update reminder after CLI commands. */
final class UpdateNotifier {
    private static final long CHECK_INTERVAL_MILLIS = Duration.ofHours(24).toMillis();
    private static final Pattern TAG = Pattern.compile("\\\"tag_name\\\"\\s*:\\s*\\\"v?(\\d+(?:\\.\\d+)+)\\\"");

    private UpdateNotifier() {}

    static void afterCommand(java.io.PrintStream out) {
        try {
            Path installRoot = Path.of(System.getenv().getOrDefault("LOCALAPPDATA", ""), "Falcon").toAbsolutePath().normalize();
            Path installedJar = installRoot.resolve("falcon.jar");
            Path runningJar = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
            if (!Files.isRegularFile(installedJar) || !runningJar.equals(installedJar)) return;

            Path cacheFile = installRoot.resolve("update-check.properties");
            Properties cache = new Properties();
            if (Files.isRegularFile(cacheFile)) try (var input = Files.newInputStream(cacheFile)) { cache.load(input); }
            long now = System.currentTimeMillis();
            long checked = parseLong(cache.getProperty("checkedAt"), 0);
            if (now - checked >= CHECK_INTERVAL_MILLIS) {
                String latest = queryLatestVersion();
                cache.setProperty("checkedAt", Long.toString(now));
                if (latest != null) cache.setProperty("latest", latest);
                try (var output = Files.newOutputStream(cacheFile)) { cache.store(output, "Falcon release check cache"); }
            }

            String latest = cache.getProperty("latest", "");
            String quietVersion = cache.getProperty("quietVersion", "");
            if (!latest.isBlank() && !latest.equals(quietVersion) && compareVersions(latest, Main.VERSION) > 0) {
                out.println("Update available: Falcon " + latest + " is newer. Run 'falcon install -u' to update.");
            }
        } catch (Exception ignored) {
            // Update checks must never interrupt the user's Falcon command.
        }
    }

    static int quiet(java.io.PrintStream out, java.io.PrintStream err) {
        try {
            Path installRoot = Path.of(System.getenv().getOrDefault("LOCALAPPDATA", ""), "Falcon").toAbsolutePath().normalize();
            Path installedJar = installRoot.resolve("falcon.jar");
            Path runningJar = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
            if (!Files.isRegularFile(installedJar) || !runningJar.equals(installedJar)) {
                err.println("'falcon --upd-quiet' works with the FalconSetup installation. Install FalconSetup first.");
                return 2;
            }
            Path cacheFile = installRoot.resolve("update-check.properties");
            Properties cache = new Properties();
            if (Files.isRegularFile(cacheFile)) try (var input = Files.newInputStream(cacheFile)) { cache.load(input); }
            String latest = cache.getProperty("latest", "");
            String dismissedVersion = compareVersions(latest, Main.VERSION) > 0 ? latest : Main.VERSION;
            cache.setProperty("quietVersion", dismissedVersion);
            try (var output = Files.newOutputStream(cacheFile)) { cache.store(output, "Falcon release check cache"); }
            out.println("Okay, I’ll stop suggesting Falcon " + dismissedVersion + " for this install. A different version can still be suggested later.");
            return 0;
        } catch (Exception e) {
            err.println("Could not save the update reminder setting: " + e.getMessage());
            return 1;
        }
    }

    private static String queryLatestVersion() throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(700)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.github.com/repos/nexaridev/falcon-language/releases/latest"))
                .timeout(Duration.ofSeconds(2))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Falcon-CLI")
                .GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) return null;
        Matcher matcher = TAG.matcher(response.body());
        return matcher.find() ? matcher.group(1) : null;
    }

    private static int compareVersions(String left, String right) {
        String[] a = left.split("\\."), b = right.split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            long av = i < a.length ? parseLong(a[i], 0) : 0;
            long bv = i < b.length ? parseLong(b[i], 0) : 0;
            int comparison = Long.compare(av, bv);
            if (comparison != 0) return comparison;
        }
        return 0;
    }

    private static long parseLong(String value, long fallback) {
        try { return Long.parseLong(value); }
        catch (NumberFormatException e) { return fallback; }
    }
}
