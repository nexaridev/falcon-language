package falcon;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Dependency-free integration and regression suite; assertions are always enabled. */
public final class FalconTest {
    private static int passed;
    private static final java.util.List<String> failures = new java.util.ArrayList<>();
    private FalconTest() {}

    public static void main(String[] args) throws Exception {
        output("precedence", "func main() { say(2 + 3 * 4, (2 + 3) * 4, 17 / 5, 17 % 5) }", "14 20 3 2\n");
        output("newlines", "func main() {\nvar a = 2\nvar b = 3\nsay(a + b)\n}", "5\n");
        output("semicolons", "func main() { var a = 2; var b = 3; say(a, b); }", "2 3\n");
        output("continuations", "func main() { var a = 2 +\n3\nsay(\na *\n2,\ntrue\n) }", "10 true\n");
        output("group continuation", "func main() { say((1\n+ 2)) }", "3\n");
        output("unary", "func main() { say(-2 * 3, !false, +4, -1.5) }", "-6 true 4 -1.5\n");
        output("string punctuation", "func main() { say(\"give\", \"when\", \"}\", \"+\", \"(\") }", "give when } + (\n");
        output("comments and escapes", "// hello\nfunc main() { /* outer /* inner */ end */ say(\"a\\tb\\n\\\"c\\\"\\\\\") }", "a\tb\n\"c\"\\\n");
        output("comment newline", "func main() { var x = 1 /* split\nline */ say(x) }", "1\n");
        output("CRLF", "func main() {\r\nvar x = 7\r\nsay(x)\r\n}", "7\n");
        output("CR", "func main() {\rvar x = 7\rsay(x)\r}", "7\n");
        output("BOM", "\uFEFFfunc main() { say(1) }", "1\n");
        output("when else", "func main() { when (false) { say(1) }\nelse when (true) { say(2) } else { say(3) } }", "2\n");
        output("while", "func main() { var x = 0\nwhile (x < 3) { say(x)\nx = x + 1 } }", "0\n1\n2\n");
        output("each and lazy range", "func main() { each (i in 2..5) { say(i) } each (j in 5..2) { say(j) } }", "2\n3\n4\n");
        output("range variable", "func main() { fix numbers = 1..3\neach (n in numbers) { say(n) } }", "1\n2\n");
        output("recursion", "func fact(n: int) -> int { when (n <= 1) { give 1 } else { give n * fact(n - 1) } }\nfunc main() { say(fact(6)) }", "720\n");
        output("forward call", "func main() { say(double(4)) } func double(n: int) -> int { give n * 2 }", "8\n");
        output("lexical scope", "func main() { var x = 1\n{ var x = 2\nsay(x) }\nsay(x) }", "2\n1\n");
        output("numeric widening", "func half(n: float) -> float { give n / 2 } func main() { var x: float = 1\nx = 3\nsay(x, half(5)) }", "3.0 2.5\n");
        output("return widening", "func f() -> float { give 1 } func main() { say(f()) }", "1.0\n");
        output("string concatenation", "func main() { say(\"score: \" + 10 + \" \" + true) }", "score: 10 true\n");
        output("short circuit", "func main() { say(false && 1 / 0 == 0, true || 1 / 0 == 0) }", "false true\n");
        output("large integer comparison", "func main() { say(9007199254740992 == 9007199254740993, 9007199254740992 < 9007199254740993) }", "false true\n");
        output("give newline", "func stop() { give\n} func main() { stop()\nsay(\"done\") }", "done\n");
        output("return through loop", "func f() -> int { each (i in 0..10) { when (i == 3) { give i } } give 0 } func main() { say(f()) }", "3\n");
        output("empty print", "func main() { say() }", "\n");
        failure("missing separator", "func main() { var x = 1 say(x) }", "Separate statements");
        failure("fix assignment", "func main() { fix x = 1\nx = 2 }", "Cannot reassign");
        failure("wrong assignment", "func main() { var x = 1\nx = \"a\" }", "Expected int, got string");
        failure("unknown variable", "func main() { say(missing) }", "Unknown variable");
        failure("unknown call", "func main() { missing() }", "Unknown function");
        failure("duplicate binding", "func main() { var x = 1\nvar x = 2 }", "already declared");
        failure("duplicate function", "func main() {} func main() {}", "already defined");
        failure("duplicate parameter", "func f(x: int, x: int) {} func main() {}", "already declared");
        failure("function name shadowing", "func main() { var say = 1 }", "function name");
        failure("missing return", "func f(x: bool) -> int { when (x) { give 1 } } func main() {}", "every path");
        failure("wrong return", "func f() -> int { give \"a\" } func main() {}", "Expected int, got string");
        failure("return newline value", "func f() -> int { give\n1 } func main() {}", "Expected int, got void");
        failure("void binding", "func main() { var x = say(1) }", "cannot have type void");
        failure("void parameter", "func f(x: void) {} func main() {}", "cannot have type void");
        failure("void comparison", "func main() { say(say() == say()) }", "Cannot compare");
        failure("condition type", "func main() { when (1) {} }", "Expected bool");
        failure("loop type", "func main() { each (i in 1) {} }", "Expected range");
        failure("loop binding immutable", "func main() { each (i in 0..1) { i = 3 } }", "Cannot reassign");
        failure("loop scope", "func main() { each (i in 0..1) {} say(i) }", "Unknown variable");
        failure("argument count", "func f(x: int) {} func main() { f() }", "Expected 1 arguments");
        failure("argument type", "func f(x: int) {} func main() { f(true) }", "Expected int, got bool");
        failure("narrowing", "func main() { var x: int = 2.5 }", "Expected int, got float");
        failure("unreachable", "func main() { give\nsay(1) }", "cannot run");
        failure("missing main", "func other() {}", "entry point");
        failure("main signature", "func main(x: int) {}", "entry point");
        failure("unclosed string", "func main() { say(\"hi) }", "Unclosed string");
        failure("unclosed comment", "/* hi", "Unclosed block comment");
        failure("unknown character", "func main() { @ }", "Unexpected character");
        failure("unknown escape", "func main() { say(\"\\q\") }", "Unknown escape");
        failure("missing brace", "func main() {", "Expected '}'");
        failure("oversized integer", "func main() { say(9999999999999999999999) }", "Number is too large");
        failure("division zero", "func main() { say(1 / 0) }", "divide by zero");
        failure("float zero", "func main() { say(1.0 / 0) }", "divide by zero");
        failure("mod zero", "func main() { say(1 % 0) }", "divide by zero");
        failure("overflow", "func main() { say(9223372036854775807 + 1) }", "Integer overflow");
        failure("execution limit", "func main() { while (true) {} }", "step limit");
        failure("recursion limit", "func recurse() { recurse() } func main() { recurse() }", "call depth");
        failure("empty huge loop limit", "func main() { each (i in 0..9223372036854775807) {} }", "step limit");

        Token last = new Lexer("// comment\r\nvar x = 1\r\nsay(x)").scan().stream()
                .filter(t -> t.text().equals("say")).findFirst().orElseThrow();
        expect(last.line() == 3 && last.column() == 1, "token positions");
        FalconError diagnostic = new FalconError(last, "example");
        expect(diagnostic.diagnostic("test.fc", "// comment\r\nvar x = 1\r\nsay(x)")
                .contains("test.fc:3:1: error: example"), "source diagnostic");

        Path file = Files.createTempFile("falcon-test-", ".fc");
        try {
            Files.writeString(file, "func main() { say(42) }");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            PrintStream stream = new PrintStream(bytes, true, StandardCharsets.UTF_8);
            expect(Main.cli(new String[]{"check", file.toString()}, stream, stream) == 0 && bytes.toString(StandardCharsets.UTF_8).startsWith("OK:"), "CLI check");
            bytes.reset();
            expect(Main.cli(new String[]{"run", file.toString()}, stream, stream) == 0
                    && TestOutput.withoutDuration(bytes.toString(StandardCharsets.UTF_8)).trim().equals("42"), "CLI run");
            expect(Main.cli(new String[]{"tokens", file.toString()}, stream, stream) == 0, "CLI tokens");
            expect(Main.cli(new String[]{"run", file.toString(), "--max-steps", "0"}, stream, stream) == 2, "CLI invalid limit");
            expect(Main.cli(new String[]{"run", file.toString(), "--max-steps", "1"}, stream, stream) == 1, "CLI runtime error");
            expect(Main.cli(new String[]{"check", file + ".missing"}, stream, stream) == 2, "CLI missing file");
            Files.writeString(file, "func main() { say(unknown) }");
            bytes.reset();
            expect(Main.cli(new String[]{"run", file.toString()}, stream, stream) == 1 && bytes.toString(StandardCharsets.UTF_8).contains(":1:19: error:"), "CLI source error");
        } finally { Files.deleteIfExists(file); }
        ByteArrayOutputStream versionBytes = new ByteArrayOutputStream();
        PrintStream versionStream = new PrintStream(versionBytes, true, StandardCharsets.UTF_8);
        expect(Main.cli(new String[]{"--version"}, versionStream, versionStream) == 0
                && versionBytes.toString(StandardCharsets.UTF_8).trim().equals("Falcon 0.12.0"), "CLI version");
        versionBytes.reset();
        expect(Main.cli(new String[]{"--help"}, versionStream, versionStream) == 0
                && versionBytes.toString(StandardCharsets.UTF_8).lines().findFirst().orElse("").contains("Falcon 0.12.0"), "help version");
        output("if chain", "func main() { each (n in 0..4) { if (n == 0) { say(0) } elseif (n == 1) { say(1) } else if (n == 2) { say(2) } else { say(3) } } }", "0\n1\n2\n3\n");
        output("if returns", "func f(n: int) -> int { if (n == 0) { give 10 }\nelseif (n == 1) { give 20 }\nelse { give 30 } } func main() { say(f(1)) }", "20\n");
        failure("orphan elseif", "func main() { elseif (true) {} }", "must follow");
        failure("orphan else", "func main() { else {} }", "must follow");
        failure("if condition type", "func main() { if (1) {} }", "bool");
        runGroup("GUI", GuiTest::run);
        runGroup("modules", ModuleTest::run);
        runGroup("package deploy/install", PackageTest::run);
        runGroup("JVM cache", JvmCacheTest::run);
        runGroup("JVM JIT", FastJitTest::run);
        runGroup("editor", EditorTest::run);
        runGroup("stdlib", StdlibTest::run);
        runGroup("optional libraries", OptionalLibraryTest::run);
        if (!failures.isEmpty()) {
            System.err.println("Falcon tests failed:");
            for (String failure : failures) System.err.println(" - " + failure);
            throw new AssertionError(failures.size() + " test(s) failed; " + passed + " passed.");
        }
        System.out.println("Passed " + passed + " tests.");
    }

    private static void output(String name, String source, String expected) {
        try {
            String actual = run(source);
            expect(actual.equals(expected), name + ": expected " + expected + " but got " + actual);
        } catch (RuntimeException | AssertionError error) {
            failures.add(name + ": " + error.getMessage());
        }
    }
    private static void failure(String name, String source, String expected) {
        try {
            run(source);
            failures.add(name + ": expected error containing " + expected + " but the program succeeded");
        } catch (FalconError error) {
            expect(error.getMessage().contains(expected), name + ": expected error containing '"
                    + expected + "' but got '" + error.getMessage() + "'");
        } catch (RuntimeException | AssertionError error) {
            failures.add(name + ": unexpected failure: " + error.getMessage());
        }
    }
    private static String run(String source) {
        Ast.Program program = new Parser(new Lexer(source).scan()).parse();
        Checker checker = new Checker(); checker.check(program);
        if (!checker.errors.isEmpty()) throw checker.errors.get(0);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new Interpreter(checker, new PrintStream(bytes, true, StandardCharsets.UTF_8), 10_000).run();
        return bytes.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
    private static void expect(boolean condition, String name) {
        if (condition) passed++;
        else failures.add(name);
    }

    @FunctionalInterface
    private interface TestGroup { int run() throws Exception; }

    private static void runGroup(String name, TestGroup group) {
        try { passed += group.run(); }
        catch (Exception | AssertionError error) { failures.add(name + " test group: " + error.getMessage()); }
    }
}
