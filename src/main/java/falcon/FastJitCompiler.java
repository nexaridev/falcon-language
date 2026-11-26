package falcon;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import static falcon.Ast.*;

/** Compiles the common, statically typed numeric subset to JVM bytecode at runtime. */
final class FastJitCompiler {
    @FunctionalInterface
    interface CompiledProgram { void run(PrintStream output, long maxSteps); }

    private FastJitCompiler() {}

    static CompiledProgram compile(Program program, Checker checker) {
        if (ToolProvider.getSystemJavaCompiler() == null) return null;
        Function main = program.functions().stream()
                .filter(function -> function.name().text().equals("main") && function.namespace().isEmpty())
                .findFirst().orElse(null);
        if (main == null || !main.parameters().isEmpty() || main.result() != Type.VOID || !program.structs().isEmpty()) return null;
        try {
            Generator generator = new Generator(checker, main);
            String body = generator.generate();
            if (body == null) return null;
            String prefix = "package falcon;\npublic final class FalconJit_";
            String suffix = " {\n" + runtimeSource(main.name().file()) + "\n" + body + "\n}\n";
            String placeholder = prefix + "HASH" + suffix;
            String id = hash(placeholder).substring(0, 20);
            String className = "FalconJit_" + id;
            String source = prefix + id + suffix;
            Class<?> compiled = compileClass(className, source);
            var method = compiled.getMethod("run", PrintStream.class, long.class);
            return (output, maxSteps) -> {
                try { method.invoke(null, output, maxSteps); }
                catch (InvocationTargetException exception) {
                    Throwable cause = exception.getCause();
                    if (cause instanceof RuntimeException runtime) throw runtime;
                    if (cause instanceof Error error) throw error;
                    throw new IllegalStateException(cause);
                } catch (ReflectiveOperationException exception) {
                    throw new IllegalStateException("Generated Falcon code could not be called.", exception);
                }
            };
        } catch (UnsupportedOperationException | IllegalArgumentException | LinkageError exception) {
            return null;
        } catch (Exception exception) {
            return null;
        }
    }

    private static String runtimeSource(String file) {
        return """
                private static final class State {
                    long remaining;
                    final String file;
                    State(long remaining, String file) { this.remaining = remaining; this.file = file; }
                    FalconError error(int line, int column, String message) {
                        return new FalconError(new Token(Token.Kind.NAME, \"\", line, column, file), message);
                    }
                    void tick(int line, int column) {
                        if (--remaining < 0) throw error(line, column, \"Execution step limit reached; check your loops or increase --max-steps.\");
                    }
                }
                private static long plus(State s, long a, long b, int line, int column) {
                    try { return Math.addExact(a, b); }
                    catch (ArithmeticException e) { throw s.error(line, column, \"Integer overflow; use smaller values or float.\"); }
                }
                private static long minus(State s, long a, long b, int line, int column) {
                    try { return Math.subtractExact(a, b); }
                    catch (ArithmeticException e) { throw s.error(line, column, \"Integer overflow; use smaller values or float.\"); }
                }
                private static long times(State s, long a, long b, int line, int column) {
                    try { return Math.multiplyExact(a, b); }
                    catch (ArithmeticException e) { throw s.error(line, column, \"Integer overflow; use smaller values or float.\"); }
                }
                private static long divide(State s, long a, long b, int line, int column) {
                    if (b == 0) throw s.error(line, column, \"Cannot divide by zero.\");
                    if (a == Long.MIN_VALUE && b == -1) throw s.error(line, column, \"Integer overflow; use smaller values or float.\");
                    return a / b;
                }
                private static long remainder(State s, long a, long b, int line, int column) {
                    if (b == 0) throw s.error(line, column, \"Cannot divide by zero.\");
                    return a % b;
                }
                private static double finite(State s, double value, int line, int column) {
                    if (!Double.isFinite(value)) throw s.error(line, column, \"Floating-point result is outside the finite range.\");
                    return value;
                }
                private static double negate(State s, double value, int line, int column) {
                    return finite(s, -value, line, column);
                }
                private static long negateInt(State s, long value, int line, int column) {
                    try { return Math.negateExact(value); }
                    catch (ArithmeticException e) { throw s.error(line, column, \"Integer overflow; use smaller values or float.\"); }
                }
                private static double floatDivide(State s, double a, double b, int line, int column) {
                    if (b == 0) throw s.error(line, column, \"Cannot divide by zero.\");
                    return finite(s, a / b, line, column);
                }
                private static double floatRemainder(State s, double a, double b, int line, int column) {
                    if (b == 0) throw s.error(line, column, \"Cannot divide by zero.\");
                    return finite(s, a % b, line, column);
                }
                """.replace("__FALCON_FILE__", javaString(file));
    }

    private static Class<?> compileClass(String simpleName, String source) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var standard = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8);
        var outputs = new LinkedHashMap<String, ByteArrayOutputStream>();
        JavaFileManager manager = new ForwardingJavaFileManager<>(standard) {
            @Override public JavaFileObject getJavaFileForOutput(Location location, String className,
                    JavaFileObject.Kind kind, FileObject sibling) {
                return new SimpleJavaFileObject(URI.create("bytes:///" + className.replace('.', '/') + kind.extension), kind) {
                    @Override public OutputStream openOutputStream() {
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                        outputs.put(className, bytes);
                        return bytes;
                    }
                };
            }
        };
        String classPath = System.getProperty("java.class.path", "");
        JavaFileObject input = new SimpleJavaFileObject(URI.create("string:///falcon/" + simpleName + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return source; }
        };
        Boolean success = compiler.getTask(null, manager, diagnostics,
                List.of("--release", "17", "-encoding", "UTF-8", "-classpath", classPath), null, List.of(input)).call();
        manager.close();
        if (!Boolean.TRUE.equals(success)) return null;
        try {
            Class<?> primary = null;
            for (var entry : outputs.entrySet()) {
                Class<?> defined = MethodHandles.lookup().defineClass(entry.getValue().toByteArray());
                if (entry.getKey().equals("falcon." + simpleName)) primary = defined;
            }
            return primary;
        } catch (LinkageError duplicate) { return Class.forName("falcon." + simpleName); }
    }

    private static String hash(String source) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));
    }

    private static String javaString(String value) {
        var result = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> result.append("\\\\"); case '"' -> result.append("\\\"");
                case '\n' -> result.append("\\n"); case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t"); case '\b' -> result.append("\\b"); case '\f' -> result.append("\\f");
                default -> { if (c < 0x20) result.append(String.format("\\u%04x", (int)c)); else result.append(c); }
            }
        }
        return result.append('"').toString();
    }

    private static final class Generator {
        private record Local(String javaName, Type type) {}
        private final Checker checker;
        private final Function main;
        private final StringBuilder code = new StringBuilder();
        private final Deque<Map<String, Local>> scopes = new ArrayDeque<>();
        private int indent = 2;
        private int nextLocal;
        private int nextTemp;
        private boolean unsupported;

        Generator(Checker checker, Function main) { this.checker = checker; this.main = main; }

        String generate() {
            scopes.push(new HashMap<>());
            line("public static void run(java.io.PrintStream out, long maxSteps) {");
            indent++;
            line("State state = new State(maxSteps, " + javaString(main.name().file()) + ");");
            line("try {"); indent++;
            tick(main.name());
            statements(main.body().statements());
            indent--; line("} catch (ArithmeticException e) {"); indent++;
            line("throw state.error(" + main.name().line() + ", " + main.name().column() + ", \"Integer overflow; use smaller values or float.\");");
            indent--; line("}"); indent--; line("}");
            scopes.pop();
            return unsupported ? null : code.toString();
        }

        private void statements(List<Stmt> statements) { for (Stmt statement : statements) statement(statement); }

        private void statement(Stmt statement) {
            if (statement instanceof Binding binding) {
                Type type = binding.annotation() == null ? checker.types.get(binding.value()) : binding.annotation();
                if (type == Type.NUMBER) type = checker.types.get(binding.value());
                String javaType = javaType(type);
                if (javaType == null) { unsupported = true; return; }
                tick(binding.token());
                String value = expression(binding.value());
                String name = "v" + nextLocal++;
                line(javaType + " " + name + " = " + convert(value, checker.types.get(binding.value()), type) + ";");
                scopes.peek().put(binding.name().text(), new Local(name, type));
            } else if (statement instanceof Assign assign) {
                Local target = find(assign.token().text());
                if (target == null) { unsupported = true; return; }
                tick(assign.token());
                String value = expression(assign.value());
                line(target.javaName() + " = " + convert(value, checker.types.get(assign.value()), target.type()) + ";");
            } else if (statement instanceof ExprStmt expression) {
                if (!(expression.value() instanceof Call call) || !call.token().text().equals("say")) { unsupported = true; return; }
                tick(expression.token());
                expression(expression.value());
            } else if (statement instanceof Block block) {
                tick(block.token()); emitBlock(block);
            } else if (statement instanceof When when) {
                tick(when.token());
                String condition = expression(when.condition());
                line("if (" + condition + ") {"); indent++; scopes.push(new HashMap<>());
                tick(when.yes().token());
                statements(when.yes().statements()); scopes.pop(); indent--; line("}");
                if (when.no() != null) {
                    if (when.no() instanceof Block block) {
                        line("else {"); indent++; scopes.push(new HashMap<>()); tick(block.token());
                        statements(block.statements()); scopes.pop(); indent--; line("}");
                    } else {
                        unsupported = true;
                    }
                }
            } else if (statement instanceof While loop) {
                tick(loop.token()); line("while (true) {"); indent++;
                String condition = expression(loop.condition());
                line("if (!(" + condition + ")) break;");
                statement(loop.body()); indent--; line("}");
            } else if (statement instanceof Each each) {
                if (!(each.iterable() instanceof Binary range) || !range.token().text().equals("..")) { unsupported = true; return; }
                tick(each.token()); tick(range.token());
                String start = expression(range.left()); String end = expression(range.right());
                String first = "r" + nextTemp++, last = "r" + nextTemp++;
                String variable = "v" + nextLocal++;
                line("long " + first + " = " + start + ";");
                line("long " + last + " = " + end + ";");
                line("long " + variable + ";");
                line("for (" + variable + " = " + first + "; " + variable + " < " + last + "; " + variable + "++) {"); indent++;
                tick(each.token()); scopes.push(new HashMap<>());
                scopes.peek().put(each.name().text(), new Local(variable, Type.INT));
                statement(each.body()); scopes.pop(); indent--; line("}");
            } else unsupported = true;
        }

        private void emitBlock(Block block) {
            line("{"); indent++; scopes.push(new HashMap<>());
            statements(block.statements());
            scopes.pop(); indent--; line("}");
        }

        private String expression(Expr expression) {
            if (unsupported) return "0";
            tick(expression.token());
            if (expression instanceof Literal literal) {
                Object value = literal.value();
                if (value == null) { unsupported = true; return "null"; }
                if (value instanceof Long number) return number + "L";
                if (value instanceof Double number) return Double.toString(number) + "D";
                if (value instanceof Boolean bool) return bool.toString();
                if (value instanceof String string) return javaString(string);
                unsupported = true; return "0";
            }
            if (expression instanceof Name name) {
                Local local = find(name.token().text());
                if (local == null) { unsupported = true; return "0"; }
                return local.javaName();
            }
            if (expression instanceof Unary unary) {
                String value = expression(unary.value());
                Type type = checker.types.get(unary.value());
                return switch (unary.token().text()) {
                    case "+" -> value;
                    case "!" -> "(!" + value + ")";
                    case "-" -> type == Type.INT
                            ? "negateInt(state, " + value + ", " + unary.token().line() + ", " + unary.token().column() + ")"
                            : "negate(state, " + value + ", " + unary.token().line() + ", " + unary.token().column() + ")";
                    default -> { unsupported = true; yield "0"; }
                };
            }
            if (expression instanceof Binary binary) {
                String operator = binary.token().text();
                String left = expression(binary.left());
                if (operator.equals("&&") || operator.equals("||")) {
                    String temp = "b" + nextTemp++;
                    line("boolean " + temp + " = " + left + ";");
                    line("if (" + (operator.equals("&&") ? temp : "!" + temp) + ") {"); indent++;
                    String right = expression(binary.right()); line(temp + " = " + right + ";");
                    indent--; line("}"); return temp;
                }
                String right = expression(binary.right());
                Type leftType = checker.types.get(binary.left()), rightType = checker.types.get(binary.right());
                int line = binary.token().line(), column = binary.token().column();
                if (operator.equals("+") && (leftType == Type.STRING || rightType == Type.STRING))
                    return "(String.valueOf(" + left + ") + String.valueOf(" + right + "))";
                if (operator.equals("==") || operator.equals("!=")) {
                    if (leftType == Type.STRING || rightType == Type.STRING)
                        return "java.util.Objects." + (operator.equals("==") ? "equals" : "equals") + "(" + left + ", " + right + ")"
                                + (operator.equals("==") ? "" : " == false");
                    return "(" + left + " " + operator + " " + right + ")";
                }
                if (List.of("<", "<=", ">", ">=").contains(operator)) return "(" + left + " " + operator + " " + right + ")";
                boolean floating = leftType == Type.FLOAT || rightType == Type.FLOAT;
                if (floating) {
                    String method = switch (operator) {
                        case "+" -> "finite(state, " + left + " + " + right + ", " + line + ", " + column + ")";
                        case "-" -> "finite(state, " + left + " - " + right + ", " + line + ", " + column + ")";
                        case "*" -> "finite(state, " + left + " * " + right + ", " + line + ", " + column + ")";
                        case "/" -> "floatDivide(state, " + left + ", " + right + ", " + line + ", " + column + ")";
                        case "%" -> "floatRemainder(state, " + left + ", " + right + ", " + line + ", " + column + ")";
                        default -> null;
                    };
                    if (method == null) { unsupported = true; return "0"; }
                    return method;
                }
                return switch (operator) {
                    case "+" -> "plus(state, " + left + ", " + right + ", " + line + ", " + column + ")";
                    case "-" -> "minus(state, " + left + ", " + right + ", " + line + ", " + column + ")";
                    case "*" -> "times(state, " + left + ", " + right + ", " + line + ", " + column + ")";
                    case "/" -> "divide(state, " + left + ", " + right + ", " + line + ", " + column + ")";
                    case "%" -> "remainder(state, " + left + ", " + right + ", " + line + ", " + column + ")";
                    default -> { unsupported = true; yield "0"; }
                };
            }
            if (expression instanceof Call call) {
                if (call.token().text().equals("say")) {
                    var parts = new ArrayList<String>();
                    for (Expr argument : call.arguments()) parts.add("String.valueOf(" + expression(argument) + ")");
                    line("out.println(String.join(\" \", new String[]{" + String.join(", ", parts) + "}));");
                    return "null";
                }
                if (call.target().endsWith("time::monotonic") || call.target().equals("time.monotonic")) {
                    if (!call.arguments().isEmpty()) { unsupported = true; return "0.0"; }
                    // The call, stdlib wrapper invocation/body, and native call consume steps.
                    tick(call.token()); tick(call.token()); tick(call.token());
                    return "(System.nanoTime() / 1_000_000_000.0D)";
                }
            }
            unsupported = true;
            return "0";
        }

        private void tick(Token token) { line("state.tick(" + token.line() + ", " + token.column() + ");"); }
        private void line(String text) { code.append("    ".repeat(Math.max(0, indent))).append(text).append('\n'); }
        private Local find(String name) {
            for (Map<String, Local> scope : scopes) { Local value = scope.get(name); if (value != null) return value; }
            return null;
        }
        private static String javaType(Type type) {
            return switch (type) { case INT -> "long"; case FLOAT -> "double"; case BOOL -> "boolean"; case STRING -> "String"; default -> null; };
        }
        private static String convert(String value, Type from, Type to) {
            return to == Type.FLOAT && from == Type.INT ? "((double) (" + value + "))" : value;
        }
    }
}
