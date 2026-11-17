package falcon;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.regex.Pattern;

/** Per-program runtime state and host operations. Never used during checking. */
final class NativeRuntime implements AutoCloseable {
    private GuiRuntime gui;
    private TelegramRuntime telegram;
    private ImageRuntime images;
    private final BufferedReader input;
    private final PrintStream output;
    private final List<String> arguments;
    private final Random random = new Random();
    private String pending;
    private boolean loaded;
    private HttpClient httpClient;
    NativeRuntime(InputStream input, PrintStream output, List<String> arguments) {
        this.input = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        this.output = output; this.arguments = List.copyOf(arguments);
    }
    Object invoke(String key, List<Object> args, Token site) {
        String operation = key.substring("native::".length()).replace("::", ".");
        try {
            if (operation.startsWith("telebot.")) {
                if (telegram == null) telegram = new TelegramRuntime(output);
                return telegram.invoke(operation, args);
            }
            if (operation.startsWith("pillow.")) {
                if (images == null) images = new ImageRuntime();
                return images.invoke(operation, args);
            }
            if (operation.startsWith("gui.")) {
                if (gui == null) gui = new GuiRuntime();
                return gui.invoke(operation, args);
            }
            return execute(operation, args, site);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new FalconError(site, operation + ": operation interrupted.");
        } catch (IOException | IllegalArgumentException | ArithmeticException | SecurityException e) {
            throw new FalconError(site, operation + ": " + e.getMessage());
        }
    }

    @Override public void close() {
        if (gui != null) gui.close();
        if (telegram != null) telegram.close();
        if (images != null) images.close();
    }

    private Object execute(String op, List<Object> a, Token site) throws IOException, InterruptedException {
        return switch (op) {
            case "io.print" -> { output.print(Values.display(a.get(0))); output.flush(); yield null; }
            case "io.println" -> { output.println(Values.display(a.get(0))); yield null; }
            case "io.hasInput" -> { loadLine(); yield pending != null; }
            case "io.readLine" -> readLine();
            case "io.prompt" -> { output.print(s(a, 0)); output.flush(); yield readLine(); }
            case "math.requireFactorialInput" -> {
                long n = i(a, 0);
                if (n < 0 || n > 20) throw new IllegalArgumentException("math.factorial requires an integer from 0 to 20 (64-bit result).");
                yield null;
            }
            case "math.sqrt" -> {
                if (n(a, 0) < 0) throw new IllegalArgumentException("math.sqrt requires a nonnegative number.");
                yield Math.sqrt(n(a, 0));
            }
            case "math.pow" -> finite(Math.pow(n(a, 0), n(a, 1)));
            case "math.floor" -> Math.floor(n(a, 0));
            case "math.ceil" -> Math.ceil(n(a, 0));
            case "math.round" -> {
                if (a.get(0) instanceof Long value) yield value;
                yield checkedInteger(Math.floor(n(a, 0) + 0.5));
            }
            case "math.toInt" -> {
                if (a.get(0) instanceof Long value) yield value;
                yield checkedInteger(n(a, 0));
            }
            case "math.pi" -> Math.PI;
            case "text.length" -> (long) s(a, 0).codePointCount(0, s(a, 0).length());
            case "text.trim" -> s(a, 0).strip();
            case "text.lower" -> s(a, 0).toLowerCase(Locale.ROOT);
            case "text.upper" -> s(a, 0).toUpperCase(Locale.ROOT);
            case "text.contains" -> s(a, 0).contains(s(a, 1));
            case "text.startsWith" -> s(a, 0).startsWith(s(a, 1));
            case "text.endsWith" -> s(a, 0).endsWith(s(a, 1));
            case "text.replace" -> s(a, 0).replace(s(a, 1), s(a, 2));
            case "text.substring" -> substring(s(a, 0), i(a, 1), i(a, 2));
            case "text.split" -> {
                if (s(a, 1).isEmpty()) {
                    var result = new ArrayList<Object>();
                    s(a, 0).codePoints().forEach(c -> result.add(new String(Character.toChars(c)))); yield result;
                }
                yield new ArrayList<>(List.of(s(a, 0).split(Pattern.quote(s(a, 1)), -1)));
            }
            case "text.join" -> {
                var result = new ArrayList<String>();
                for (Object value : list(a, 0)) result.add((String) as(value, "string"));
                yield String.join(s(a, 1), result);
            }
            case "text.toInt" -> parseInt(s(a, 0));
            case "text.toFloat" -> parseFloat(s(a, 0));
            case "text.toNumber" -> {
                String value = s(a, 0).strip();
                if (value.matches("[+-]?[0-9]+")) yield parseInt(value);
                yield parseFloat(value);
            }
            case "text.from" -> Values.display(a.get(0));
            case "collections.newList" -> new ArrayList<>();
            case "collections.newMap" -> new LinkedHashMap<String, Object>();
            case "collections.size" -> {
                if (a.get(0) instanceof List<?> values) yield (long) values.size();
                if (a.get(0) instanceof Map<?, ?> values) yield (long) values.size();
                throw new IllegalArgumentException("size expects a list or map.");
            }
            case "collections.push" -> { list(a, 0).add(a.get(1)); yield null; }
            case "collections.get" -> list(a, 0).get(Values.offset(i(a, 1), list(a, 0).size()));
            case "collections.set" -> { list(a, 0).set(Values.offset(i(a, 1), list(a, 0).size()), a.get(2)); yield null; }
            case "collections.remove" -> list(a, 0).remove(Values.offset(i(a, 1), list(a, 0).size()));
            case "collections.put" -> { map(a, 0).put(s(a, 1), a.get(2)); yield null; }
            case "collections.lookup" -> Values.index(map(a, 0), s(a, 1), site);
            case "collections.hasKey" -> map(a, 0).containsKey(s(a, 1));
            case "collections.erase" -> { boolean found = map(a, 0).containsKey(s(a, 1)); map(a, 0).remove(s(a, 1)); yield found; }
            case "collections.keys" -> new ArrayList<>(map(a, 0).keySet());
            case "collections.values" -> new ArrayList<>(map(a, 0).values());
            case "collections.compareNumbers" -> (long) new java.math.BigDecimal(a.get(0).toString()).compareTo(new java.math.BigDecimal(a.get(1).toString()));
            case "collections.asNumber" -> as(a.get(0), "number");
            case "collections.asInt" -> as(a.get(0), "int");
            case "collections.asFloat" -> ((Number) as(a.get(0), "number")).doubleValue();
            case "collections.asString" -> as(a.get(0), "string");
            case "collections.asBool" -> as(a.get(0), "bool");
            case "collections.asList" -> as(a.get(0), "list");
            case "collections.asMap" -> as(a.get(0), "map");
            case "collections.isNull" -> a.get(0) == null;
            case "collections.kind" -> Values.kind(a.get(0));
            case "fs.readText" -> Files.readString(Path.of(s(a, 0)), StandardCharsets.UTF_8);
            case "fs.writeText" -> { Files.writeString(Path.of(s(a, 0)), s(a, 1), StandardCharsets.UTF_8); yield null; }
            case "fs.appendText" -> { Files.writeString(Path.of(s(a, 0)), s(a, 1), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND); yield null; }
            case "fs.exists" -> Files.exists(Path.of(s(a, 0)));
            case "fs.isFile" -> Files.isRegularFile(Path.of(s(a, 0)));
            case "fs.isDir" -> Files.isDirectory(Path.of(s(a, 0)));
            case "fs.mkdir" -> { Files.createDirectories(Path.of(s(a, 0))); yield null; }
            case "fs.listDir" -> {
                try (var paths = Files.list(Path.of(s(a, 0)))) { yield new ArrayList<>(paths.map(Path::toString).sorted().toList()); }
            }
            case "fs.remove" -> Files.deleteIfExists(Path.of(s(a, 0)));
            case "path.join" -> Path.of(s(a, 0)).resolve(s(a, 1)).normalize().toString();
            case "path.normalize" -> Path.of(s(a, 0)).normalize().toString();
            case "path.absolute" -> Path.of(s(a, 0)).toAbsolutePath().normalize().toString();
            case "path.filename" -> filename(s(a, 0));
            case "path.dirname" -> { Path parent = Path.of(s(a, 0)).getParent(); yield parent == null ? "." : parent.toString(); }
            case "path.extension" -> extension(filename(s(a, 0)));
            case "path.stem" -> { String name = filename(s(a, 0)); yield name.substring(0, name.length() - extension(name).length()); }
            case "random.seed" -> { random.setSeed(i(a, 0)); yield null; }
            case "random.next" -> random.nextDouble();
            case "random.integer" -> {
                if (i(a, 0) >= i(a, 1)) throw new IllegalArgumentException("minimum must be smaller than the exclusive maximum.");
                yield random.nextLong(i(a, 0), i(a, 1));
            }
            case "random.choice" -> {
                if (list(a, 0).isEmpty()) throw new IllegalArgumentException("Cannot choose from an empty list.");
                yield list(a, 0).get(random.nextInt(list(a, 0).size()));
            }
            case "time.now" -> System.currentTimeMillis();
            case "time.monotonic" -> System.nanoTime() / 1_000_000_000.0;
            case "time.sleep" -> {
                if (i(a, 0) < 0) throw new IllegalArgumentException("Sleep duration cannot be negative.");
                Thread.sleep(i(a, 0)); yield null;
            }
            case "time.iso" -> Instant.ofEpochMilli(i(a, 0)).toString();
            case "json.parse" -> Json.read(s(a, 0));
            case "json.stringify" -> Json.write(a.get(0));
            case "os.args" -> new ArrayList<>(arguments);
            case "os.cwd" -> Path.of("").toAbsolutePath().normalize().toString();
            case "os.env" -> System.getenv().getOrDefault(s(a, 0), "");
            case "os.hasEnv" -> System.getenv().containsKey(s(a, 0));
            case "os.platform" -> System.getProperty("os.name");
            case "os.home" -> System.getProperty("user.home");
            case "test.fail" -> throw new IllegalArgumentException(s(a, 0));
            case "http.get" -> request("GET", s(a, 0), "", 10000);
            case "http.request" -> request(s(a, 0), s(a, 1), s(a, 2), i(a, 3));
            default -> throw new IllegalStateException("Unimplemented native operation " + op);
        };
    }

    private void loadLine() throws IOException { if (!loaded) { pending = input.readLine(); loaded = true; } }
    private String readLine() throws IOException {
        loadLine(); if (pending == null) throw new IllegalArgumentException("End of input; use io.hasInput() to check for more lines.");
        String result = pending; pending = null; loaded = false; return result;
    }
    private static long parseInt(String source) {
        String value = source.strip();
        if (!value.matches("[+-]?[0-9]+")) throw new IllegalArgumentException("Expected integer text, got '" + source + "'.");
        try { return Long.parseLong(value); } catch (NumberFormatException e) { throw new IllegalArgumentException("Integer is outside the signed 64-bit range."); }
    }
    private static double parseFloat(String source) {
        String value = source.strip();
        if (!value.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?"))
            throw new IllegalArgumentException("Expected decimal text, got '" + source + "'.");
        return finite(Double.parseDouble(value));
    }
    private static long checkedInteger(double value) {
        if (!Double.isFinite(value) || value < -0x1.0p63 || value >= 0x1.0p63)
            throw new IllegalArgumentException("Number is outside the signed 64-bit integer range.");
        return (long) value;
    }
    private static double finite(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Operation has no finite real result for these arguments.");
        return value;
    }
    private static Object as(Object value, String type) {
        if ((type.equals("number") && value instanceof Number) || type.equals(Values.kind(value))) return value;
        throw new IllegalArgumentException("Expected " + type + ", got " + Values.kind(value) + ".");
    }
    private static String substring(String value, long start, long end) {
        int length = value.codePointCount(0, value.length());
        if (start < 0 || start > end || end > length) throw new IllegalArgumentException("substring bounds must satisfy 0 <= start <= end <= length.");
        return value.substring(value.offsetByCodePoints(0, (int) start), value.offsetByCodePoints(0, (int) end));
    }
    private static String filename(String value) { Path name = Path.of(value).getFileName(); return name == null ? "" : name.toString(); }
    private static String extension(String filename) { int dot = filename.lastIndexOf('.'); return dot <= 0 ? "" : filename.substring(dot); }
    private static double n(List<Object> values, int index) { return ((Number) values.get(index)).doubleValue(); }
    private static long i(List<Object> values, int index) { return (long) values.get(index); }
    private static String s(List<Object> values, int index) { return (String) values.get(index); }
    @SuppressWarnings("unchecked") private static List<Object> list(List<Object> values, int index) { return (List<Object>) values.get(index); }
    @SuppressWarnings("unchecked") private static Map<String, Object> map(List<Object> values, int index) { return (Map<String, Object>) values.get(index); }
    private Map<String, Object> request(String method, String url, String body, long timeout) throws IOException, InterruptedException {
        if (timeout < 1 || timeout > 60000) throw new IllegalArgumentException("HTTP timeout must be between 1 and 60000 milliseconds.");
        URI uri = URI.create(url);
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())))
            throw new IllegalArgumentException("HTTP URL must use http or https.");
        if (httpClient == null) httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(timeout))
                .method(method.toUpperCase(Locale.ROOT), body.isEmpty() ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        var result = new LinkedHashMap<String, Object>();
        result.put("status", (long) response.statusCode()); result.put("body", response.body());
        var headers = new LinkedHashMap<String, Object>();
        response.headers().map().forEach((key, values) -> headers.put(key, new ArrayList<>(values)));
        result.put("headers", headers); return result;
    }
}
