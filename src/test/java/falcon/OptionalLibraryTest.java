package falcon;

import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

final class OptionalLibraryTest {
    private static int passed;
    static int run() throws Exception {
        Path folder = Files.createTempDirectory("falcon-optional-");
        try {
            images(folder); telegram(folder); wrappers(folder);
        } finally {
            try (var files = Files.walk(folder)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
        return passed;
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); passed++; }
    private static void images(Path folder) throws Exception {
        try (var runtime = new ImageRuntime()) {
            long image = (long) runtime.invoke("pillow.create", List.of(4L, 2L, "#FF000080"));
            check(runtime.invoke("pillow.getPixel", List.of(image, 0L, 0L)).equals("#FF000080"), "alpha color");
            runtime.invoke("pillow.putPixel", List.of(image, 0L, 0L, "#00FF00"));
            long crop = (long) runtime.invoke("pillow.crop", List.of(image, 0L, 0L, 1L, 1L));
            check(runtime.invoke("pillow.getPixel", List.of(crop, 0L, 0L)).equals("#00FF00FF"), "crop pixels");
            long resize = (long) runtime.invoke("pillow.resize", List.of(crop, 8L, 6L));
            check(runtime.invoke("pillow.width", List.of(resize)).equals(8L) && runtime.invoke("pillow.height", List.of(resize)).equals(6L), "resize dimensions");
            long rotate = (long) runtime.invoke("pillow.rotate", List.of(image, 90L));
            check(runtime.invoke("pillow.width", List.of(rotate)).equals(2L) && runtime.invoke("pillow.height", List.of(rotate)).equals(4L), "rotate dimensions");
            check(runtime.invoke("pillow.getPixel", List.of(rotate, 0L, 3L)).equals("#00FF00FF"), "counterclockwise rotation");
            long flip = (long) runtime.invoke("pillow.flip", List.of(image, "horizontal"));
            check(runtime.invoke("pillow.getPixel", List.of(flip, 3L, 0L)).equals("#00FF00FF"), "horizontal flip");
            long gray = (long) runtime.invoke("pillow.grayscale", List.of(crop));
            check(runtime.invoke("pillow.getPixel", List.of(gray, 0L, 0L)).equals("#969696FF"), "grayscale");
            long dim = (long) runtime.invoke("pillow.brightness", List.of(crop, 0.5));
            check(runtime.invoke("pillow.getPixel", List.of(dim, 0L, 0L)).equals("#008000FF"), "brightness");
            long blur = (long) runtime.invoke("pillow.blur", List.of(crop));
            check(runtime.invoke("pillow.getPixel", List.of(blur, 0L, 0L)).equals("#00FF00FF"), "blur solid color");
            Path png = folder.resolve("кирилиця.png");
            runtime.invoke("pillow.save", List.of(image, png.toString(), "png"));
            long opened = (long) runtime.invoke("pillow.open", List.of(png.toString()));
            check(runtime.invoke("pillow.getPixel", List.of(opened, 3L, 1L)).equals("#FF000080"), "PNG alpha roundtrip");
            Path jpeg = folder.resolve("out.jpg");
            runtime.invoke("pillow.save", List.of(image, jpeg.toString(), "jpeg"));
            check(javax.imageio.ImageIO.read(jpeg.toFile()).getWidth() == 4, "JPEG readable");
            runtime.invoke("pillow.paste", List.of(image, crop, 2L, 1L));
            check(runtime.invoke("pillow.getPixel", List.of(image, 2L, 1L)).equals("#00FF00FF"), "paste");
            long canvas = (long) runtime.invoke("pillow.create", List.of(200L, 80L, "#FFFFFF"));
            runtime.invoke("pillow.rectangle", List.of(canvas, 0L, 0L, 10L, 10L, "#000000"));
            check(runtime.invoke("pillow.getPixel", List.of(canvas, 5L, 5L)).equals("#000000FF"), "rectangle");
            runtime.invoke("pillow.drawText", List.of(canvas, 20L, 5L, "Привіт", 24L, "#000000"));
            runtime.invoke("pillow.ellipse", List.of(canvas, 150L, 20L, 180L, 50L, "#0000FF"));
            check(runtime.invoke("pillow.getPixel", List.of(canvas, 165L, 35L)).equals("#0000FFFF"), "ellipse");
            runtime.invoke("pillow.line", List.of(canvas, 0L, 60L, 100L, 60L, "#FF0000"));
            long copy = (long) runtime.invoke("pillow.copy", List.of(crop));
            runtime.invoke("pillow.close", List.of(crop));
            check(runtime.invoke("pillow.getPixel", List.of(copy, 0L, 0L)).equals("#00FF00FF"), "independent copy");
            for (var args : List.of(List.<Object>of(0L, 10L, "#FFFFFF"), List.<Object>of(100000L, 100000L, "#FFFFFF"))) {
                try { runtime.invoke("pillow.create", args); throw new AssertionError("image bounds"); }
                catch (IllegalArgumentException e) { passed++; }
            }
            try { runtime.invoke("pillow.width", List.of(crop)); throw new AssertionError("closed image"); }
            catch (IllegalArgumentException e) { passed++; }
            try { runtime.invoke("pillow.crop", List.of(image, -1L, 0L, 1L, 1L)); throw new AssertionError("crop bounds"); }
            catch (IllegalArgumentException e) { passed++; }
        }
    }
    @SuppressWarnings("unchecked")
    private static void telegram(Path folder) throws Exception {
        var payload = new AtomicReference<>("{\"ok\":true,\"result\":{\"id\":123}}");
        var received = new AtomicReference<>(""); var mime = new AtomicReference<>("");
        var status = new java.util.concurrent.atomic.AtomicInteger(200);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            mime.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            byte[] data = payload.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), data.length);
            try (var body = exchange.getResponseBody()) { body.write(data); }
        });
        server.start(); var logs = new ByteArrayOutputStream();
        try (var runtime = new TelegramRuntime(new PrintStream(logs, true, StandardCharsets.UTF_8), URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"))) {
            long bot = (long) runtime.invoke("telebot.create", List.of("123:secret_TOKEN"));
            Path log = folder.resolve("bot.log"); runtime.invoke("telebot.setLogging", List.of(bot, "DEBUG", log.toString()));
            Map<String, Object> response = (Map<String, Object>) runtime.invoke("telebot.call", List.of(bot, "sendMessage", Map.of("chat_id", 9007199254740991L, "text", "Привіт 😀")));
            check(response.get("ok").equals(true), "Telegram success");
            check(((Map<?, ?>) Json.read(received.get())).get("text").equals("Привіт 😀") && received.get().contains("9007199254740991"), "Unicode and 64-bit chat ID");
            check(mime.get().startsWith("application/json"), "JSON request content type");
            payload.set("{\"ok\":true,\"result\":[{\"update_id\":41,\"message\":{\"text\":\"hello\"}}]}");
            runtime.invoke("telebot.poll", List.of(bot, 0L));
            check(received.get().contains("\"offset\":0"), "initial offset");
            payload.set("{\"ok\":false,\"error_code\":429,\"description\":\"retry 123:secret_TOKEN\",\"parameters\":{\"retry_after\":3}}");
            status.set(429);
            int before = calls.get();
            response = (Map<String, Object>) runtime.invoke("telebot.poll", List.of(bot, 0L));
            check(received.get().contains("\"offset\":42"), "next poll acknowledges prior batch");
            check(response.get("retry_after").equals(3L) && response.get("error_code").equals(429L), "rate limit metadata");
            check(calls.get() == before + 1, "no automatic resend");
            check(!response.get("description").toString().contains("secret_TOKEN"), "error token redacted");
            status.set(200); payload.set("{\"ok\":true,\"result\":[]}"); runtime.invoke("telebot.poll", List.of(bot, 0L));
            check(received.get().contains("\"offset\":42"), "error preserves offset");
            payload.set("{\"ok\":true,\"result\":[{\"update_id\":55},{}]}");
            response = (Map<String, Object>) runtime.invoke("telebot.poll", List.of(bot, 0L));
            check(response.get("ok").equals(false), "malformed update rejected");
            payload.set("{\"ok\":true,\"result\":[]}"); runtime.invoke("telebot.poll", List.of(bot, 0L));
            check(received.get().contains("\"offset\":42"), "malformed batch preserves entire offset");
            Path file = folder.resolve("upload.png"); Files.write(file, new byte[]{1, 2, 3});
            runtime.invoke("telebot.upload", List.of(bot, "sendPhoto", Map.of("chat_id", 123L, "caption", "Привіт"), "photo", file.toString()));
            check(mime.get().startsWith("multipart/form-data; boundary="), "multipart content type");
            check(received.get().contains("name=\"photo\"; filename=\"upload.png\"") && received.get().contains("Привіт"), "multipart file and Unicode caption");
            payload.set("invalid JSON");
            response = (Map<String, Object>) runtime.invoke("telebot.call", List.of(bot, "getMe", Map.of()));
            check(response.get("ok").equals(false) && response.get("error_code").equals(0L), "malformed HTTP response handled");
            runtime.invoke("telebot.log", List.of(bot, "token 123:secret_TOKEN\nforged log"));
            String logText = Files.readString(log);
            check(!logText.contains("secret_TOKEN") && !logText.contains("\nforged") && !logText.contains("Привіт"), "logs exclude token and message bodies, sanitize newlines");
            check(logs.toString(StandardCharsets.UTF_8).contains("[ERROR]"), "error log emitted");
            runtime.invoke("telebot.close", List.of(bot));
            try { runtime.invoke("telebot.poll", List.of(bot, 0L)); throw new AssertionError("closed bot"); }
            catch (IllegalArgumentException e) { passed++; }
        } finally { server.stop(0); }
    }
    private static void wrappers(Path folder) throws Exception {
        Path libs = folder.resolve("libraries"); Files.createDirectories(libs);
        for (String name : List.of("telebot", "pillow")) Files.copy(Path.of("libraries", name + ".fc"), libs.resolve(name + ".fc"));
        Path source = folder.resolve("main.fc");
        Files.writeString(source, "use \"libraries/telebot.fc\" as tb\nuse \"libraries/pillow.fc\" as im\nuse collections as c\nfunc main() { fix bot = tb.TeleBot(\"123:fake\")\ntb.close(bot)\nfix message = c.newMap()\nc.put(message, \"text\", \"/start@mybot hi\")\nsay(tb.command(message))\nfix image = im.new_image(4, 2, \"#123456\")\nsay(im.get_pixel(image, 0, 0), im.width(image))\nim.close(image) }");
        var bytes = new ByteArrayOutputStream(); var out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        int code = Main.cli(new String[]{"run", source.toString()}, out, out);
        check(code == 0 && bytes.toString(StandardCharsets.UTF_8).replace("\r\n", "\n").equals("/start\n#123456FF 4\n"), "optional module wrappers: " + bytes.toString(StandardCharsets.UTF_8));
        for (String file : List.of("examples/telegram_bot.fc", "examples/image_card.fc")) {
            bytes.reset(); check(Main.cli(new String[]{"check", file}, out, out) == 0, "example check: " + bytes.toString(StandardCharsets.UTF_8));
        }
    }
}
