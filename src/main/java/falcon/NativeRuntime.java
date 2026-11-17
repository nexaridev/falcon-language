package falcon;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.regex.Pattern;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.crypto.Mac;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

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
            if (operation.startsWith("cmd.")) return command(operation, args);
            if (operation.startsWith("crypto.")) return crypto(operation, args);
            if (operation.startsWith("regex.")) return regex(operation, args);
            if (operation.startsWith("compression.")) return compression(operation, args);
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
        } catch (Exception e) {
            throw new FalconError(site, operation + ": " + (e.getMessage() == null ? "the operation could not be completed." : e.getMessage()));
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
    private Object command(String op, List<Object> a) throws IOException, InterruptedException {
        if (op.equals("cmd.which")) {
            String name = s(a, 0);
            if (!name.matches("[A-Za-z0-9_.-]+")) throw new IllegalArgumentException("Command name may only contain letters, digits, dot, underscore, and dash.");
            boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
            ProcessBuilder pb = windows ? new ProcessBuilder("where.exe", name) : new ProcessBuilder("which", name);
            try { Process p=pb.start(); byte[] out=p.getInputStream().readAllBytes(); p.waitFor(); return p.exitValue()==0?new String(out,StandardCharsets.UTF_8).lines().findFirst().orElse(""):""; }
            catch (IOException e) { return ""; }
        }
        String value=s(a,0); long timeout=i(a,1);
        if(timeout<1 || timeout>300000) throw new IllegalArgumentException("Command timeout must be from 1 to 300000 milliseconds.");
        boolean windows=System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        Process process=(windows?new ProcessBuilder("cmd.exe","/d","/s","/c",value):new ProcessBuilder("/bin/sh","-c",value)).start();
        process.getOutputStream().close();
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var stdout=pool.submit(()->readLimited(process.getInputStream()));
            var stderr=pool.submit(()->readLimited(process.getErrorStream()));
            boolean finished=process.waitFor(timeout,java.util.concurrent.TimeUnit.MILLISECONDS);
            if(!finished){process.destroyForcibly(); return commandResult(-1,"","Command timed out after "+timeout+" ms.",true);}
            return commandResult(process.exitValue(),stdout.get(2,java.util.concurrent.TimeUnit.SECONDS),stderr.get(2,java.util.concurrent.TimeUnit.SECONDS),false);
        } catch(java.util.concurrent.ExecutionException|java.util.concurrent.TimeoutException e){process.destroyForcibly(); throw new IOException("Could not collect command output.",e);}
        finally {pool.shutdownNow();}
    }
    private static String readLimited(InputStream stream) throws IOException { byte[] bytes=stream.readNBytes(1024*1024+1); if(bytes.length>1024*1024) throw new IOException("Command output exceeded 1 MiB."); return new String(bytes,StandardCharsets.UTF_8); }
    private static Map<String,Object> commandResult(int exit,String stdout,String stderr,boolean timedOut){var result=new LinkedHashMap<String,Object>();result.put("exit_code",(long)exit);result.put("stdout",stdout);result.put("stderr",stderr);result.put("timed_out",timedOut);return result;}
    private Object crypto(String op,List<Object>a) throws Exception {
        String value=s(a,0);
        return switch(op){
            case "crypto.sha256","crypto.md5" -> HexFormat.of().formatHex(MessageDigest.getInstance(op.endsWith("md5")?"MD5":"SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
            case "crypto.hmacSha256" -> { Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(s(a,1).getBytes(StandardCharsets.UTF_8),"HmacSHA256"));yield HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8))); }
            case "crypto.secureToken" -> { long count=i(a,0);if(count<16||count>128)throw new IllegalArgumentException("Secure token size must be 16–128 random bytes.");byte[] bytes=new byte[(int)count];new SecureRandom().nextBytes(bytes);yield Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
            case "crypto.base64Encode" -> Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
            case "crypto.base64Decode" -> new String(Base64.getDecoder().decode(value),StandardCharsets.UTF_8);
            case "crypto.hexEncode" -> HexFormat.of().formatHex(value.getBytes(StandardCharsets.UTF_8));
            case "crypto.hexDecode" -> new String(HexFormat.of().parseHex(value),StandardCharsets.UTF_8);
            case "crypto.encrypt" -> encrypt(value,s(a,1));
            case "crypto.decrypt" -> decrypt(value,s(a,1));
            default -> throw new IllegalArgumentException("Unknown crypto operation.");
        };
    }
    private static byte[] aesKey(String key) throws Exception {return MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));}
    private static String encrypt(String value,String key) throws Exception {byte[] iv=new byte[12];new SecureRandom().nextBytes(iv);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(aesKey(key),"AES"),new GCMParameterSpec(128,iv));byte[] cipher=c.doFinal(value.getBytes(StandardCharsets.UTF_8));byte[] result=Arrays.copyOf(iv,iv.length+cipher.length);System.arraycopy(cipher,0,result,iv.length,cipher.length);return Base64.getEncoder().encodeToString(result);}
    private static String decrypt(String value,String key) throws Exception {byte[] data=Base64.getDecoder().decode(value);if(data.length<29)throw new IllegalArgumentException("Encrypted value is malformed.");Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,new SecretKeySpec(aesKey(key),"AES"),new GCMParameterSpec(128,data,0,12));return new String(c.doFinal(data,12,data.length-12),StandardCharsets.UTF_8);}
    private Object regex(String op,List<Object>a){String value=s(a,0),pattern=s(a,1);Pattern compiled;try{compiled=Pattern.compile(pattern);}catch(java.util.regex.PatternSyntaxException e){throw new IllegalArgumentException("That pattern is not valid: "+e.getDescription()+".");}return switch(op){case "regex.matches"->compiled.matcher(value).matches();case "regex.find"->{var m=compiled.matcher(value);yield m.find()?m.group():"";}case "regex.findAll"->{var result=new ArrayList<Object>();var m=compiled.matcher(value);while(m.find())result.add(m.group());yield result;}case "regex.replace"->compiled.matcher(value).replaceAll(s(a,2));case "regex.split"->new ArrayList<>(Arrays.asList(compiled.split(value)));default->throw new IllegalArgumentException("Unknown regex operation.");};}
    private Object compression(String op,List<Object>a)throws IOException{
        if(op.equals("compression.gzip")){var out=new ByteArrayOutputStream();try(var gzip=new GZIPOutputStream(out)){gzip.write(s(a,0).getBytes(StandardCharsets.UTF_8));}return Base64.getEncoder().encodeToString(out.toByteArray());}
        if(op.equals("compression.gunzip")){byte[] zipped=Base64.getDecoder().decode(s(a,0));try(var in=new GZIPInputStream(new ByteArrayInputStream(zipped))){return new String(in.readNBytes(16*1024*1024+1),StandardCharsets.UTF_8);}}
        Path first=Path.of(s(a,0)).toAbsolutePath().normalize();Path second=Path.of(s(a,1)).toAbsolutePath().normalize();
        if(op.equals("compression.zip")){try(var out=new ZipOutputStream(Files.newOutputStream(second))){Path base=Files.isDirectory(first)?first:first.getParent();try(var paths=Files.walk(first)){for(Path file:paths.filter(Files::isRegularFile).toList()){String name=base.relativize(file).toString().replace('\\','/');out.putNextEntry(new ZipEntry(name));Files.copy(file,out);out.closeEntry();}}}return null;}
        if(op.equals("compression.unzip")){Files.createDirectories(second);int count=0;try(var zip=new ZipInputStream(Files.newInputStream(first))){ZipEntry entry;while((entry=zip.getNextEntry())!=null){if(++count>10000)throw new IOException("Archive has too many entries.");Path target=second.resolve(entry.getName()).normalize();if(!target.startsWith(second))throw new IOException("Archive contains an unsafe path.");if(entry.isDirectory())Files.createDirectories(target);else{Files.createDirectories(target.getParent());try(var out=Files.newOutputStream(target)){zip.transferTo(out);}}zip.closeEntry();}}return null;}
        throw new IllegalArgumentException("Unknown compression operation.");
    }
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
