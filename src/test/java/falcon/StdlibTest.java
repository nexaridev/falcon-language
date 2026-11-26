package falcon;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.ArrayList;
import com.sun.net.httpserver.HttpServer;

final class StdlibTest {
    private static Path root;
    private static int passed;
    static int run() throws Exception {
        root = Files.createTempDirectory("falcon-standard-suite-");
        try {
            success("number reassignment", "func main() { var x: number = 1\nx = 2.5\nsay(x)\nx = 9007199254740993\nsay(x) }", "2.5\n9007199254740993\n");
            success("number function", "func twice(x: number) -> number { give x * 2 } func main() { say(twice(3), twice(1.5)) }", "6 3.0\n");
            success("number arithmetic", "func main() { var a: number = 7\nvar b: number = 2\nsay(a / b, a % b, -a, a > b)\nb = 2.0\nsay(a / b) }", "3 1 -7 true\n3.5\n");
            success("number to float", "func widen(x: number) -> float { give x } func main() { say(widen(3)) }", "3.0\n");
            failure("number rejects text", "func main() { var x: number = \"hi\" }", "Expected number, got string");
            failure("number rejects bool", "func main() { var x: number = true }", "Expected number, got bool");
            failure("number cannot narrow", "func f(x: number) -> int { give x } func main() {}", "Expected int, got number");
            failure("inferred int stays int", "func main() { var x = 1\nx = 2.5 }", "Expected int, got float");
            success("list literal and index", "func main() { var items = [1,\n2.5, \"hi\", null]\nsay(items, items[1], \"😀a\"[0]) }", "[1, 2.5, hi, null] 2.5 😀\n");
            success("list iteration", "use collections as c\nfunc main() { var values = [1, 2, 3]\nvar sum: number = 0\neach (v in values) { sum = sum + c.asNumber(v) } say(sum) }", "6\n");
            success("multiline trailing comma", "func main() { say([\n1,\n2,\n]) }", "[1, 2]\n");
            success("shared references equality", "use test\nfunc main() { var a = []\ntest.equal([a,a], [[],[]]) }", "");
            success("round exact integer", "use math\nfunc main() { say(math.round(9007199254740993)) }", "9007199254740993\n");
            failure("index bounds", "func main() { say([1][1]) }", "outside");
            failure("index type", "func main() { say([1][\"bad\"]) }", "Expected int, got string");
            failure("void element", "func main() { var a = [say()] }", "Cannot store void");
            success("typed list index needs no extraction", "func main() { var values = [1]\nsay(values[0] + 1) }", "2\n");
            success("math number", "use math\nfunc main() { say(math.square(2.5), math.abs(-1.5), math.min(1, 2.5), math.max(2.5, 1), math.clamp(10, 0, 5), math.round(2.6), math.toInt(2.9)) }", "6.25 1.5 1 2.5 5 3 2\n");
            success("all libraries load", "use io\nuse math\nuse text\nuse collections\nuse fs\nuse path\nuse random\nuse time\nuse json\nuse os\nuse test\nuse http\nfunc main() { test.near(math.pi(), 3.14159, 0.00001) }", "");
            failure("math conversion overflow", "use math\nfunc main() { say(math.toInt(9223372036854775808.0)) }", "64-bit integer range");
            success("text operations", "use text\nuse test\nfunc main() { test.equal(text.length(\"a😀\"), 2)\ntest.equal(text.substring(\"a😀b\", 1, 2), \"😀\")\ntest.equal(text.lower(\"ABC\"), \"abc\")\ntest.equal(text.upper(\"abc\"), \"ABC\")\ntest.equal(text.trim(\"  hi  \"), \"hi\")\ntest.assert(text.contains(\"hello\", \"ell\") && text.startsWith(\"hello\", \"he\") && text.endsWith(\"hello\", \"lo\"), \"search\")\ntest.equal(text.replace(\"a-b-a\", \"a\", \"z\"), \"z-b-z\")\ntest.equal(text.split(\"a,b,\", \",\"), [\"a\", \"b\", \"\"])\ntest.equal(text.split(\"😀a\", \"\"), [\"😀\", \"a\"])\ntest.equal(text.join([\"a\", \"b\"], \"-\"), \"a-b\")\ntest.equal(text.from(12), \"12\")\nsay(text.toInt(\" 12 \"), text.toFloat(\"1e2\"), text.toNumber(\"42\"), text.toNumber(\"2.5\")) }", "12 100.0 42 2.5\n");
            failure("bad numeric text", "use text\nfunc main() { text.toInt(\"1.2\") }", "Expected integer text");
            failure("nonfinite numeric text", "use text\nfunc main() { text.toFloat(\"1e999\") }", "no finite real result");
            failure("bad substring", "use text\nfunc main() { text.substring(\"abc\", 2, 1) }", "bounds");
            failure("join type", "use text\nfunc main() { text.join([1], \",\") }", "Expected string");
            success("collections list", "use collections as c\nuse test\nfunc main() { var a = c.newList()\nc.push(a, 3)\nc.push(a, 1.5)\nc.push(a, 2)\ntest.equal(c.size(a), 3)\ntest.equal(c.sortNumbers(a), [1.5, 2, 3])\ntest.equal(c.reverse(a), [2, 1.5, 3])\nc.set(a, 0, 9)\ntest.equal(c.get(a, 0), 9)\ntest.equal(c.remove(a, 1), 1.5)\ntest.equal(a, [9, 2]) }", "");
            success("collections maps", "use collections as c\nuse test\nfunc main() { var m = c.newMap()\nc.put(m, \"name\", \"Falcon\")\nc.put(m, \"missing\", null)\ntest.equal(m[\"name\"], \"Falcon\")\ntest.equal(c.lookup(m, \"name\"), \"Falcon\")\ntest.equal(c.keys(m), [\"name\", \"missing\"])\ntest.equal(c.values(m), [\"Falcon\", null])\ntest.assert(c.hasKey(m, \"missing\"), \"key\")\ntest.assert(c.erase(m, \"missing\"), \"erase\")\ntest.assert(!c.erase(m, \"missing\"), \"gone\")\ntest.equal(c.size(m), 1) }", "");
            success("collection extraction", "use collections as c\nuse test\nfunc main() { test.equal(c.asNumber(1.5), 1.5)\ntest.equal(c.asInt(2), 2)\ntest.equal(c.asFloat(2), 2.0)\ntest.equal(c.asString(\"x\"), \"x\")\ntest.assert(c.asBool(true), \"bool\")\ntest.equal(c.asList([1]), [1])\ntest.equal(c.asMap(c.newMap()), c.newMap())\ntest.assert(c.isNull(null), \"null\")\ntest.equal(c.kind(null), \"null\")\ntest.equal(c.kind(2), \"int\") }", "");
            failure("dynamic extraction type", "use collections as c\nfunc main() { c.asNumber(\"2\") }", "Expected number, got string");
            failure("missing map key", "use collections as c\nfunc main() { say(c.newMap()[\"x\"]) }", "no key");
            failure("numeric sort types", "use collections as c\nfunc main() { c.sortNumbers([1, \"x\"]) }", "Expected number");
            success("random seeded", "use random\nuse test\nfunc main() { random.seed(42)\nvar first = random.next()\nrandom.seed(42)\ntest.equal(first, random.next())\ntest.assert(first >= 0 && first < 1, \"fraction range\")\neach (i in 0..20) { var n = random.integer(-5, 5)\ntest.assert(n >= -5 && n < 5, \"integer range\") } test.equal(random.choice([7]), 7) }", "");
            failure("random empty choice", "use random\nfunc main() { random.choice([]) }", "empty list");
            failure("random invalid bounds", "use random\nfunc main() { random.integer(2, 2) }", "minimum");
            success("time", "use time\nuse test\nfunc main() { var before = time.monotonic()\ntime.sleep(0)\ntest.assert(time.monotonic() >= before, \"monotonic\")\ntest.assert(time.now() > 0, \"epoch\")\ntest.equal(time.iso(0), \"1970-01-01T00:00:00Z\") }", "");
            failure("negative sleep", "use time\nfunc main() { time.sleep(-1) }", "negative");
            success("JSON", "use json\nuse collections as c\nuse test\nfunc main() { var value = json.parse(\"{\\\"a\\\":[1,2.5,true,null,\\\"hi\\\"]}\")\nvar object = c.asMap(value)\ntest.equal(c.asList(object[\"a\"]), [1,2.5,true,null,\"hi\"])\ntest.equal(json.parse(json.stringify(value)), value)\ntest.equal(json.parse(\"9007199254740993\"), 9007199254740993)\ntest.equal(json.parse(\"1e2\"), 100.0) }", "");
            for (String malformed : List.of("[1,]", "{\"a\":1,}", "01", "1.", "NaN", "true false", "\"\\q\"", "{\"a\":1,\"a\":2}")) {
                failure("malformed JSON " + malformed, "use json\nfunc main() { json.parse(" + quote(malformed) + ") }", "JSON");
            }
            failure("cyclic JSON", "use collections as c\nuse json\nfunc main() { var a = []\nc.push(a, a)\njson.stringify(a) }", "cyclic");
            success("cyclic display", "use collections as c\nfunc main() { var a = []\nc.push(a, a)\nsay(a) }", "[<cycle>]\n");
            success("test equality", "use test\nfunc main() { test.equal([1, [true]], [1.0, [true]])\ntest.near(1.0, 1.01, 0.02) }", "");
            failure("assert failure", "use test\nfunc main() { test.assert(false, \"broken\") }", "broken");
            failure("equal failure", "use test\nfunc main() { test.equal(1, 2) }", "Expected 2, got 1");
            failure("near failure", "use test\nfunc main() { test.near(1, 2, 0.1) }", "differ");
            failure("fail function", "use test\nfunc main() { test.fail(\"failed deliberately\") }", "failed deliberately");
            ioAndArguments(); files(); http(); standardImports();
            return passed;
        } finally {
            try (var files = Files.walk(root)) { for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file); }
        }
    }

    private static void ioAndArguments() throws Exception {
        Result io = execute("use io\nfunc main() { say(io.prompt(\"Name? \"))\nsay(io.readInt(), io.readNumber())\nsay(io.hasInput())\nio.print(\"x\")\nio.println(\"y\") }", "Ada\n12\n2.5\n", List.of(), "run");
        expect(io.code == 0 && io.output.equals("Name? Ada\n12 2.5\nfalse\nxy\n"), "console input and output: " + io);
        failure("EOF", "use io\nfunc main() { io.readLine() }", "End of input");
        Result args = execute("use os\nuse test\nfunc main() { say(os.args())\ntest.assert(os.cwd() != \"\" && os.home() != \"\" && os.platform() != \"\", \"os values\")\ntest.assert(!os.hasEnv(\"FALCON_TEST_MISSING_814BE22\"), \"missing env\")\ntest.equal(os.env(\"FALCON_TEST_MISSING_814BE22\"), \"\") }", "", List.of("one", "two words", "--flag"), "run");
        expect(args.code == 0 && args.output.equals("[one, two words, --flag]\n"), "program arguments: " + args);
    }
    private static void files() throws Exception {
        String directory = quote(root.resolve("files").toString());
        String program = "use fs\nuse path\nuse test\nfunc main() { fix dir = " + directory + "\nfs.mkdir(dir)\nfix file = path.join(dir, \"sample.txt\")\nfs.writeText(file, \"hello 😀\")\nfs.appendText(file, \"!\")\ntest.equal(fs.readText(file), \"hello 😀!\")\ntest.assert(fs.exists(file) && fs.isFile(file) && fs.isDir(dir), \"file flags\")\ntest.equal(fs.listDir(dir), [file])\ntest.equal(path.filename(file), \"sample.txt\")\ntest.equal(path.extension(file), \".txt\")\ntest.equal(path.stem(file), \"sample\")\ntest.equal(path.dirname(file), dir)\ntest.equal(path.normalize(path.join(dir, \"../files\")), dir)\ntest.equal(path.absolute(dir), dir)\ntest.assert(fs.remove(file), \"remove\")\ntest.assert(!fs.remove(file), \"gone\") }";
        Result check = execute(program, "", List.of(), "check");
        expect(check.code == 0 && !Files.exists(root.resolve("files")), "check never performs I/O");
        success("file and path operations", program, "");
        failure("missing file", "use fs\nfunc main() { fs.readText(" + quote(root.resolve("absent").toString()) + ") }", "fs.readText");
    }
    private static void http() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] result = (exchange.getRequestMethod() + ":" + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(200, result.length);
            try (var stream = exchange.getResponseBody()) { stream.write(result); }
        });
        server.start();
        try {
            String url = quote("http://127.0.0.1:" + server.getAddress().getPort() + "/");
            success("local HTTP", "use http\nuse test\nfunc main() { var response = http.get(" + url + ")\ntest.equal(response[\"status\"], 200)\ntest.equal(response[\"body\"], \"GET:\")\nvar posted = http.request(\"POST\", " + url + ", \"Falcon\", 3000)\ntest.equal(posted[\"body\"], \"POST:Falcon\") }", "");
            failure("HTTP invalid timeout", "use http\nfunc main() { http.request(\"GET\", " + url + ", \"\", 0) }", "timeout");
            failure("HTTP invalid scheme", "use http\nfunc main() { http.get(\"file:///test\") }", "http or https");
        } finally { server.stop(0); }
    }
    private static void standardImports() throws Exception {
        Path standard = root.resolve("custom-standard"); Files.createDirectories(standard);
        Files.writeString(standard.resolve("helper.fc"), "func value() -> int { give 7 }");
        Files.writeString(standard.resolve("custom.fc"), "use helper\nfunc value() -> int { give helper.value() }");
        String previous = System.getProperty("falcon.stdlib");
        try {
            System.setProperty("falcon.stdlib", standard.toString());
            success("new Falcon stdlib module", "use custom\nfunc main() { say(custom.value()) }", "7\n");
            Files.writeString(standard.resolve("helper.fc"), "use custom\nfunc value() -> int { give 7 }");
            failure("standard import cycle", "use custom\nfunc main() {}", "Circular standard-library");
        } finally { if (previous == null) System.clearProperty("falcon.stdlib"); else System.setProperty("falcon.stdlib", previous); }
    }
    private record Result(int code, String output) {}
    private static Result execute(String source, String input, List<String> args, String command) throws Exception {
        Path file = root.resolve("main.fc"); Files.writeString(file, source);
        var output = new ByteArrayOutputStream(); var stream = new PrintStream(output, true, StandardCharsets.UTF_8);
        var options = new ArrayList<>(List.of(command, file.toString()));
        if (!args.isEmpty()) { options.add("--"); options.addAll(args); }
        int code = Main.cli(options.toArray(String[]::new), new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), stream, stream);
        return new Result(code, TestOutput.withoutDuration(output.toString(StandardCharsets.UTF_8)));
    }
    private static void success(String name, String source, String output) throws Exception {
        Result result = execute(source, "", List.of(), "run");
        expect(result.code == 0 && result.output.equals(output), name + ": " + result);
    }
    private static void failure(String name, String source, String fragment) throws Exception {
        Result result = execute(source, "", List.of(), "run");
        expect(result.code == 1 && result.output.contains(fragment), name + ": " + result);
    }
    private static String quote(String value) { return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
    private static void expect(boolean condition, String message) { if (!condition) throw new AssertionError(message); passed++; }
}
