package falcon;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class FastJitTest {
    private FastJitTest() {}

    public static void main(String[] args) throws Exception { run(); System.out.println("Passed 5 JVM JIT checks."); }

    static int run() throws Exception {
        String source = "func main() { var total = 0\neach (i in 0..100000) { total = total + ((i * 17 + 13) % 1009) }\nsay(total) }";
        var checker = check(source);
        FastJitCompiler.CompiledProgram compiled = FastJitCompiler.compile(checker.program(), checker.checker());
        if (compiled == null) throw new AssertionError("numeric loop did not compile to JVM bytecode");
        var bytes = new ByteArrayOutputStream();
        compiled.run(new PrintStream(bytes, true, StandardCharsets.UTF_8), 100_000_000);
        expect(bytes.toString(StandardCharsets.UTF_8).replace("\r\n", "\n").equals("50396093\n"),
                "compiled integer loop checksum: " + bytes.toString(StandardCharsets.UTF_8));

        var limited = compile("func main() { var i = 0\nwhile (i < 10) { i = i + 1 } }");
        try { limited.run(new PrintStream(new ByteArrayOutputStream()), 1); throw new AssertionError("compiled step limit"); }
        catch (FalconError error) { expect(error.getMessage().contains("step limit"), "compiled step-limit diagnostic"); }

        var overflow = compile("func main() { say(9223372036854775807 + 1) }");
        try { overflow.run(new PrintStream(new ByteArrayOutputStream()), 1000); throw new AssertionError("compiled overflow"); }
        catch (FalconError error) { expect(error.getMessage().contains("Integer overflow"), "compiled overflow diagnostic"); }

        var unsupported = check("func main() { say([1, 2]) }");
        expect(FastJitCompiler.compile(unsupported.program(), unsupported.checker()) == null,
                "unsupported source falls back to interpreter");

        Path file = Files.createTempFile("falcon-jit-time-", ".fc");
        try {
            String timed = "use time\nfunc main() { fix started = time.monotonic()\nvar total = 0\neach (i in 0..100) { total = total + i }\nsay(total, time.monotonic() > started) }";
            Files.writeString(file, timed);
            ModuleLoader loader = new ModuleLoader();
            Ast.Program linked = loader.load(file, timed);
            Checker moduleChecker = new Checker(); moduleChecker.check(linked);
            if (!moduleChecker.errors.isEmpty()) throw moduleChecker.errors.get(0);
            FastJitCompiler.CompiledProgram moduleJit = FastJitCompiler.compile(linked, moduleChecker);
            if (moduleJit == null) throw new AssertionError("time module program did not compile");
            bytes.reset(); moduleJit.run(new PrintStream(bytes, true, StandardCharsets.UTF_8), 100_000);
            expect(bytes.toString(StandardCharsets.UTF_8).contains("4950 true"), "compiled time wrapper");
        } finally { Files.deleteIfExists(file); }
        return 5;
    }

    private static FastJitCompiler.CompiledProgram compile(String source) {
        var checked = check(source);
        FastJitCompiler.CompiledProgram compiled = FastJitCompiler.compile(checked.program(), checked.checker());
        if (compiled == null) throw new AssertionError("supported source did not compile to JVM bytecode: " + source);
        return compiled;
    }

    private record Checked(Ast.Program program, Checker checker) {}
    private static Checked check(String source) {
        Ast.Program program = new Parser(new Lexer(source, "jit-test.fc").scan()).parse();
        Checker checker = new Checker(); checker.check(program);
        if (!checker.errors.isEmpty()) throw checker.errors.get(0);
        return new Checked(program, checker);
    }
    private static void expect(boolean condition, String name) { if (!condition) throw new AssertionError(name); }
}
