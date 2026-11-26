package falcon;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;

final class ModuleTest {
    private static int passed;

    static int run() throws Exception {
        scenario("simple module", "use math\nfunc main() { say(math.square(5)) }",
                Map.of("math.fc", "func square(x: int) -> int { give x * x }"), 0, "25\n");
        scenario("explicit relative path", "use \"lib/util.fc\" as util\nfunc main() { say(util.value()) }",
                Map.of("lib/util.fc", "func value() -> int { give 8 }"), 0, "8\n");
        scenario("folder module initializer", "use foldermod as foldermod\nfunc main() { say(foldermod.value()) }",
                Map.of("modules/foldermod/__init__.fc", "func value() -> int { give 42 }"), 0, "42\n");
        scenario("folder initializer imports sibling", "use foldermod as foldermod\nfunc main() { say(foldermod.value()) }",
                Map.of("modules/foldermod/__init__.fc", "use \"helper.fc\" as helper\nfunc value() -> int { give helper.value() }",
                        "modules/foldermod/helper.fc", "func value() -> int { give 43 }"), 0, "43\n");
        scenario("file and folder module conflict", "use poo\nfunc main() {}",
                Map.of("poo.fc", "func value() {}", "modules/poo/__init__.fc", "func value() {}"), 1,
                "conflicts: folder '");
        persistentJvmFolderModule();
        scenario("bare alias", "use math as m; func main() { say(m.value()) }",
                Map.of("math.fc", "func value() -> int { give 3 }"), 0, "3\n");
        scenario("isolated function names", "use left\nuse right\nfunc value() -> int { give 100 } func main() { say(value(), left.value(), right.value()) }",
                Map.of("left.fc", "func value() -> int { give 1 }", "right.fc", "func value() -> int { give 2 }"), 0, "100 1 2\n");
        scenario("module local recursion", "use math\nfunc factorial(n: int) -> int { give 99 } func main() { say(math.factorial(5)) }",
                Map.of("math.fc", "func factorial(n: int) -> int { when (n <= 1) { give 1 } give n * factorial(n - 1) }"), 0, "120\n");
        scenario("nested relative imports and diamond", "use \"lib/a.fc\" as a\nuse shared\nfunc main() { say(a.value(), shared.value()) }",
                Map.of("lib/a.fc", "use \"../shared.fc\" as s\nfunc value() -> int { give s.value() + 1 }",
                        "shared.fc", "func value() -> int { give 10 }"), 0, "11 10\n");
        scenario("same file two aliases", "use math as a\nuse \"./math.fc\" as b\nfunc main() { say(a.value(), b.value()) }",
                Map.of("math.fc", "func value() -> int { give 2 }"), 0, "2 2\n");
        scenario("library main does not run", "use library\nfunc main() { say(1) }",
                Map.of("library.fc", "func main() { say(999) }"), 0, "1\n");
        scenario("missing module", "use missing\nfunc main() {}", Map.of(), 1, "main.fc:1:1: error: Cannot load module");
        scenario("unknown module", "func main() { other.value() }", Map.of(), 1, "Unknown module 'other'");
        scenario("unknown member", "use math\nfunc main() { math.missing() }",
                Map.of("math.fc", "func value() {}"), 1, "has no function or struct 'missing'");
        scenario("no implicit function imports", "use math\nfunc main() { value() }",
                Map.of("math.fc", "func value() {}"), 1, "Unknown function 'value'");
        scenario("no caller scope leak", "use math\nfunc helper() {} func main() { math.value() }",
                Map.of("math.fc", "func value() { helper() }"), 1, "math.fc:1:16: error: Unknown function 'helper'");
        scenario("type error in imported file", "use math\nfunc main() {}",
                Map.of("math.fc", "func value() -> int {\n    give true\n}"), 1, "math.fc:2:5: error: Expected int, got bool");
        scenario("lexer error in imported file", "use math\nfunc main() {}",
                Map.of("math.fc", "@"), 1, "math.fc:1:1: error: Unexpected character");
        scenario("parser error in imported file", "use math\nfunc main() {}",
                Map.of("math.fc", "func value() {"), 1, "math.fc:1:15: error: Expected '}'");
        scenario("runtime error in imported file", "use math\nfunc main() { math.value() }",
                Map.of("math.fc", "func value() {\n    say(1 / 0)\n}"), 1, "math.fc:2:11: error: Cannot divide by zero");
        scenario("module call types", "use math\nfunc main() { math.value(true) }",
                Map.of("math.fc", "func value(x: int) {}"), 1, "Expected int, got bool");
        scenario("duplicate alias", "use math\nuse math\nfunc main() {}",
                Map.of("math.fc", ""), 1, "Module alias 'math' is already in use");
        scenario("alias function collision", "use math\nfunc math() {} func main() {}",
                Map.of("math.fc", ""), 1, "Module alias 'math' is already in use");
        scenario("alias binding collision", "use math\nfunc main() { var math = 1 }",
                Map.of("math.fc", ""), 1, "cannot use the module alias");
        scenario("alias parameter collision", "use math\nfunc f(math: int) {} func main() {}",
                Map.of("math.fc", ""), 1, "cannot use the module alias");
        scenario("duplicate library function", "use math\nfunc main() {}",
                Map.of("math.fc", "func f() {} func f() {}"), 1, "Function 'f' is already defined");
        scenario("library cannot supply entry point", "use math\n",
                Map.of("math.fc", "func main() {}"), 1, "Add an entry point");
        scenario("cycle", "use a\nfunc main() {}",
                Map.of("a.fc", "use b\n", "b.fc", "use a\n"), 1, "Circular module import");
        scenario("self import", "use main\nfunc main() {}", Map.of(), 1, "already in use");
        scenario("self import aliased", "use main as self\nfunc main() {}", Map.of(), 1, "Circular module import");
        scenario("quoted path needs alias", "use \"math.fc\"\nfunc main() {}", Map.of(), 1, "Expected 'as'");
        scenario("extension required", "use \"math.txt\" as math\nfunc main() {}", Map.of(), 1, "module 'math' is not installed; run falcon install \"math\"");
        scenario("module not a value", "use math\nfunc main() { say(math.value) }",
                Map.of("math.fc", "func value() {}"), 1, "Unknown variable 'math'");
        scenario("bundled factorial", "use math\nfunc main() { say(math.factorial(0), math.factorial(5), math.factorial(20)) }",
                Map.of(), 0, "1 120 2432902008176640000\n");
        scenario("bundled functions", "use math\nfunc main() { say(math.square(5), math.abs(-7), math.sqrt(9), math.pow(2, 3), math.min(2, 3), math.max(2, 3), math.floor(2.9), math.ceil(2.1)) }",
                Map.of(), 0, "25 7 3.0 8.0 2 3 2.0 3.0\n");
        scenario("bundled alias", "use math as m\nfunc main() { say(m.factorial(3)) }", Map.of(), 0, "6\n");
        scenario("explicit standard namespace", "use \"std/math.fc\" as m\nfunc main() { say(m.factorial(3)) }",
                Map.of("math.fc", "func factorial(n: int) -> int { give 99 }"), 0, "6\n");
        scenario("local math keeps precedence", "use math\nfunc main() { say(math.factorial(3)) }",
                Map.of("math.fc", "func factorial(n: int) -> int { give 99 }"), 0, "99\n");
        scenario("quoted local path never falls back", "use \"math.fc\" as m\nfunc main() {}", Map.of(), 1, "Cannot load module");
        scenario("factorial negative", "use math\nfunc main() { say(math.factorial(-1)) }", Map.of(), 1, "from 0 to 20");
        scenario("factorial overflow", "use math\nfunc main() { say(math.factorial(21)) }", Map.of(), 1, "from 0 to 20");
        scenario("square overflow", "use math\nfunc main() { say(math.square(9223372036854775807)) }", Map.of(), 1, "Integer overflow");
        scenario("abs overflow", "use math\nfunc main() { say(math.abs(-9223372036854775807 - 1)) }", Map.of(), 1, "Integer overflow");
        scenario("sqrt negative", "use math\nfunc main() { say(math.sqrt(-1)) }", Map.of(), 1, "nonnegative");
        scenario("pow non finite", "use math\nfunc main() { say(math.pow(10, 1000)) }", Map.of(), 1, "no finite real result");
        scenario("native arity", "use math\nfunc main() { say(math.factorial()) }", Map.of(), 1, "Expected 1 arguments");
        scenario("native type", "use math\nfunc main() { say(math.factorial(2.5)) }", Map.of(), 1, "Expected int, got float");
        scenario("native missing member", "use math\nfunc main() { math.missing() }", Map.of(), 1, "has no function or struct 'missing'");
        scenario("nested native import", "use helper\nfunc main() { say(helper.value()) }",
                Map.of("helper.fc", "use math\nfunc value() -> int { give math.factorial(4) }"), 0, "24\n");
        return passed;
    }

    private static void scenario(String name, String entry, Map<String, String> dependencies,
                                 int expectedCode, String expectedOutput) throws Exception {
        Path directory = Files.createTempDirectory("falcon modules ");
        try {
            Path main = directory.resolve("main.fc");
            Files.writeString(main, entry);
            for (var file : dependencies.entrySet()) {
                Path destination = directory.resolve(file.getKey());
                Files.createDirectories(destination.getParent());
                Files.writeString(destination, file.getValue());
            }
            var output = new ByteArrayOutputStream();
            var stream = new PrintStream(output, true, StandardCharsets.UTF_8);
            int code = Main.cli(new String[]{"run", main.toString()}, stream, stream);
            String actual = TestOutput.withoutDuration(output.toString(StandardCharsets.UTF_8));
            boolean matches = expectedCode == 0 ? actual.equals(expectedOutput) : actual.contains(expectedOutput);
            if (code != expectedCode || !matches)
                throw new AssertionError(name + " (exit " + code + "): " + actual);
            if (expectedCode == 0) {
                output.reset();
                if (Main.cli(new String[]{"check", main.toString()}, stream, stream) != 0
                        || !output.toString(StandardCharsets.UTF_8).startsWith("OK:"))
                    throw new AssertionError(name + ": check failed or executed code");
            }
            passed++;
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void persistentJvmFolderModule() throws Exception {
        Path directory = Files.createTempDirectory("falcon jvm folder module ");
        try {
            Path module = directory.resolve("modules/foldermod/__init__.fc");
            Files.createDirectories(module.getParent());
            Files.writeString(module, "func value() -> int { give 44 }");
            Path main = directory.resolve("main.fc");
            Files.writeString(main, "use foldermod as foldermod\nfunc main() { say(foldermod.value()) }");
            String commands = "falcon run \"" + main + "\"\n:quit\n";
            var output = new ByteArrayOutputStream();
            var stream = new PrintStream(output, true, StandardCharsets.UTF_8);
            int code = Main.cli(new String[]{"jvm"}, new java.io.ByteArrayInputStream(commands.getBytes(StandardCharsets.UTF_8)), stream, stream);
            String actual = TestOutput.withoutDuration(output.toString(StandardCharsets.UTF_8));
            if (code != 0 || !actual.contains("44\n")) throw new AssertionError("persistent JVM couldn't run a folder module: " + actual);
            passed++;
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
