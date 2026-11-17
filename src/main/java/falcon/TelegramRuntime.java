package falcon;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Host transport for the optional libraries/telebot.fc module. */
final class TelegramRuntime implements AutoCloseable {
    private static final class Bot {
        final String token;
        long offset;
        String level = "INFO", logfile = "";
        Bot(String token) { this.token = token; }
    }
    private final Map<Long, Bot> bots = new HashMap<>();
    private final PrintStream output;
    private final URI endpoint;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private long next = 1;
    TelegramRuntime(PrintStream output) { this(output, URI.create("https://api.telegram.org/")); }
    // Injectable endpoint is package-private for local transport tests, not a Falcon API.
    TelegramRuntime(PrintStream output, URI endpoint) { this.output = output; this.endpoint = endpoint; }

    @SuppressWarnings("unchecked")
    Object invoke(String op, List<Object> a) throws IOException, InterruptedException {
        if (op.equals("telebot.create")) {
            String token = (String) a.get(0);
            if (!token.matches("[0-9]+:[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Expected a BotFather token; read it from an environment variable.");
            long id = next++; bots.put(id, new Bot(token)); return id;
        }
        long id = (long) a.get(0);
        Bot bot = bots.get(id);
        if (bot == null) throw new IllegalArgumentException("Unknown or closed bot handle.");
        return switch (op) {
            case "telebot.close" -> { bots.remove(id); yield null; }
            case "telebot.setLogging" -> {
                String level = ((String) a.get(1)).toUpperCase(Locale.ROOT);
                if (!Set.of("OFF", "ERROR", "INFO", "DEBUG").contains(level)) throw new IllegalArgumentException("Log level must be OFF, ERROR, INFO, or DEBUG.");
                String filename = (String) a.get(2);
                if (!filename.isEmpty()) Files.writeString(Path.of(filename), "", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                bot.level = level; bot.logfile = filename; yield null;
            }
            case "telebot.log" -> { log(bot, "INFO", (String) a.get(1)); yield null; }
            case "telebot.call" -> request(bot, (String) a.get(1), (Map<String, Object>) a.get(2), 60, null, null);
            case "telebot.upload" -> {
                String method = (String) a.get(1), field = (String) a.get(3);
                if (!(method.equals("sendPhoto") && field.equals("photo") || method.equals("sendDocument") && field.equals("document")))
                    throw new IllegalArgumentException("Uploads support sendPhoto/photo and sendDocument/document.");
                yield request(bot, method, (Map<String, Object>) a.get(2), 60, field, Path.of((String) a.get(4)));
            }
            case "telebot.poll" -> {
                long seconds = (long) a.get(1);
                if (seconds < 0 || seconds > 50) throw new IllegalArgumentException("Polling timeout must be 0–50 seconds.");
                var parameters = new LinkedHashMap<String, Object>();
                parameters.put("offset", bot.offset); parameters.put("timeout", seconds);
                parameters.put("allowed_updates", List.of("message", "callback_query"));
                Map<String, Object> response = request(bot, "getUpdates", parameters, seconds + 10, null, null);
                if (Boolean.TRUE.equals(response.get("ok"))) {
                    long offset = bot.offset;
                    if (!(response.get("result") instanceof List<?> updates)) yield failure(0, "Invalid updates response.", 0);
                    for (Object update : updates) {
                        if (!(update instanceof Map<?, ?> m) || !(m.get("update_id") instanceof Long n) || n < 0 || n == Long.MAX_VALUE)
                            yield failure(0, "Invalid update identifier.", 0);
                        offset = Math.max(offset, n + 1);
                    }
                    bot.offset = offset;
                }
                yield response;
            }
            default -> throw new IllegalArgumentException("Unknown bot operation.");
        };
    }

    private Map<String, Object> request(Bot bot, String method, Map<String, Object> params, long seconds, String field, Path file) throws InterruptedException {
        if (!method.matches("[A-Za-z][A-Za-z0-9]{0,79}")) throw new IllegalArgumentException("Invalid Telegram method name.");
        try {
            HttpRequest.BodyPublisher body;
            String contentType;
            if (file == null) {
                body = HttpRequest.BodyPublishers.ofString(Json.write(params), StandardCharsets.UTF_8);
                contentType = "application/json; charset=utf-8";
            } else {
                String boundary = "Falcon" + UUID.randomUUID().toString().replace("-", "");
                body = HttpRequest.BodyPublishers.ofByteArray(multipart(params, field, file, boundary));
                contentType = "multipart/form-data; boundary=" + boundary;
            }
            log(bot, "DEBUG", method + " request");
            HttpRequest request = HttpRequest.newBuilder(endpoint.resolve("./bot" + bot.token + "/" + method))
                    .timeout(Duration.ofSeconds(seconds)).header("Content-Type", contentType).POST(body).build();
            var pending = client.sendAsync(request, ignored -> new LimitedBody());
            HttpResponse<byte[]> response;
            try { response = pending.get(seconds + 1, java.util.concurrent.TimeUnit.SECONDS); }
            catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
                pending.cancel(true); throw new IOException("Transport failed", e);
            } catch (InterruptedException e) { pending.cancel(true); throw e; }
            byte[] bytes = response.body();
            Object decoded = Json.read(new String(bytes, StandardCharsets.UTF_8));
            if (!(decoded instanceof Map<?, ?> data) || !(data.get("ok") instanceof Boolean)) throw new IOException("Invalid response");
            if (Boolean.TRUE.equals(data.get("ok")) && response.statusCode() / 100 == 2 && data.containsKey("result")) {
                var result = new LinkedHashMap<String, Object>(); result.put("ok", true); result.put("result", data.get("result"));
                result.put("error_code", 0L); result.put("description", ""); result.put("retry_after", 0L);
                log(bot, "INFO", method + " OK"); return result;
            }
            long code = data.get("error_code") instanceof Long n ? n : response.statusCode();
            long retry = 0;
            if (data.get("parameters") instanceof Map<?, ?> p && p.get("retry_after") instanceof Long n) retry = Math.max(0, n);
            String description = data.get("description") instanceof String s ? clean(bot, s) : "Telegram request failed.";
            log(bot, "ERROR", method + " failed (" + code + ")");
            return failure(code, description, retry);
        } catch (IOException | IllegalArgumentException e) {
            // Exception messages can include the secret-bearing URL or request content.
            log(bot, "ERROR", method + " transport/response error");
            return failure(0, "Network, upload, or response error; no automatic resend was attempted.", 0);
        }
    }
    private static byte[] multipart(Map<String, Object> params, String field, Path file, String boundary) throws IOException {
        byte[] data;
        try (InputStream input = Files.newInputStream(file)) { data = input.readNBytes(20 * 1024 * 1024 + 1); }
        if (data.length > 20 * 1024 * 1024) throw new IOException("Upload exceeds 20 MiB");
        var bytes = new ByteArrayOutputStream();
        for (var entry : params.entrySet()) {
            if (!entry.getKey().matches("[A-Za-z_][A-Za-z0-9_]*") || entry.getKey().equals(field)) throw new IllegalArgumentException("Invalid upload parameter.");
            String value = entry.getValue() instanceof String s ? s : Json.write(entry.getValue());
            bytes.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + entry.getKey() + "\"\r\n\r\n" + value + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        String filename = file.getFileName().toString().replaceAll("[^A-Za-z0-9._-]", "_");
        bytes.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + field + "\"; filename=\"" + filename + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        bytes.write(data); bytes.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8)); return bytes.toByteArray();
    }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private java.util.concurrent.Flow.Subscription subscription;
        private long length;
        private boolean done;
        @Override public java.util.concurrent.CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        @Override public void onSubscribe(java.util.concurrent.Flow.Subscription value) { subscription = value; delegate.onSubscribe(value); }
        @Override public void onNext(List<java.nio.ByteBuffer> buffers) {
            if (done) return;
            for (var buffer : buffers) length += buffer.remaining();
            if (length > 8 * 1024 * 1024) { done = true; subscription.cancel(); delegate.onError(new IOException("Response exceeds 8 MiB")); }
            else delegate.onNext(buffers);
        }
        @Override public void onError(Throwable error) { if (!done) { done = true; delegate.onError(error); } }
        @Override public void onComplete() { if (!done) { done = true; delegate.onComplete(); } }
    }
    private static Map<String, Object> failure(long code, String message, long retry) {
        var result = new LinkedHashMap<String, Object>(); result.put("ok", false); result.put("result", null);
        result.put("error_code", code); result.put("description", message); result.put("retry_after", retry); return result;
    }
    private static String clean(Bot bot, String text) { return text.replace(bot.token, "[REDACTED]").replaceAll("[\\r\\n\\p{Cntrl}]", " "); }
    private void log(Bot bot, String level, String text) {
        if (bot.level.equals("OFF") || bot.level.equals("ERROR") && !level.equals("ERROR") || level.equals("DEBUG") && !bot.level.equals("DEBUG")) return;
        String line = Instant.now() + " [" + level + "] telebot " + clean(bot, text);
        output.println(line);
        if (!bot.logfile.isEmpty()) {
            try { Files.writeString(Path.of(bot.logfile), line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
            catch (IOException | IllegalArgumentException | SecurityException e) { output.println("[ERROR] telebot could not append log file."); }
        }
    }
    @Override public void close() { bots.clear(); }
}
