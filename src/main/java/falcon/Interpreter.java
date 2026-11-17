package falcon;

import java.io.PrintStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import static falcon.Ast.*;

/** Tree-walking reference backend. The checked AST is independent of this backend. */
final class Interpreter {
    private record Range(long start, long end) {}
    static final class StructValue {
        final String name;
        final Map<String, Object> fields = new java.util.LinkedHashMap<>();
        StructValue(String name) { this.name = name; }
    }
    private static final class Cell {
        final Type type;
        Object value;
        Cell(Type type, Object value) { this.type = type; this.value = coerce(type, value); }
    }
    private static final class Scope {
        final Scope parent;
        final Map<String, Cell> values = new HashMap<>();
        Scope(Scope parent) { this.parent = parent; }
        Cell find(String name) {
            if (values.containsKey(name)) return values.get(name);
            if (parent != null) return parent.find(name);
            throw new IllegalStateException("Unresolved checked name: " + name);
        }
    }
    private static final class Returned extends RuntimeException {
        final Object value;
        Returned(Object value) { super(null, null, false, false); this.value = value; }
    }
    private Checker checker;
    private Scope interactiveScope;
    private final PrintStream out;
    private final NativeRuntime runtime;
    private long remaining;
    private int depth;
    Interpreter(Checker checker, PrintStream out, long maxSteps) {
        this(checker, out, maxSteps, System.in, List.of());
    }
    Interpreter(Checker checker, PrintStream out, long maxSteps, InputStream input, List<String> arguments) {
        this.checker = checker; this.out = out; this.remaining = maxSteps;
        this.runtime = new NativeRuntime(input, out, arguments);
    }
    void run() {
        Function main = checker.functions.get("main");
        try { invoke(main, List.of(), main.name()); }
        finally { runtime.close(); }
    }
    void updateChecker(Checker checker) { this.checker = checker; }
    void runInteractive(Stmt statement) {
        if (interactiveScope == null) interactiveScope = new Scope(null);
        execute(statement, interactiveScope);
    }
    private Object invoke(Function function, List<Object> args, Token callSite) {
        tick(callSite);
        if (depth >= 256) throw new FalconError(callSite, "Maximum function call depth (256) exceeded.");
        depth++;
        try {
            Scope scope = new Scope(null);
            for (int i = 0; i < args.size(); i++) {
                Parameter param = function.parameters().get(i);
                scope.values.put(param.name().text(), new Cell(param.type(), args.get(i)));
            }
            try { statements(function.body(), scope); }
            catch (Returned returned) { return coerce(function.result(), returned.value); }
            return null;
        } finally { depth--; }
    }
    private void statements(Block block, Scope scope) {
        for (Stmt statement : block.statements()) execute(statement, scope);
    }
    private void execute(Stmt statement, Scope scope) {
        tick(statement.token());
        if (statement instanceof Block block) statements(block, new Scope(scope));
        else if (statement instanceof Binding binding) {
            Object value = evaluate(binding.value(), scope);
            Type type = binding.annotation() == null ? checker.types.get(binding.value()) : binding.annotation();
            scope.values.put(binding.name().text(), new Cell(type, value));
        } else if (statement instanceof Assign assign) {
            Cell cell = scope.find(assign.token().text()); cell.value = coerce(cell.type, evaluate(assign.value(), scope));
        } else if (statement instanceof IndexAssign assign) {
            Object target = evaluate(assign.target(), scope);
            Object index = evaluate(assign.index(), scope);
            Object value = evaluate(assign.value(), scope);
            Values.assign(target, index, value, assign.token());
        } else if (statement instanceof FieldAssign assign) {
            Object target = evaluate(assign.target(), scope);
            Object value = evaluate(assign.value(), scope);
            ((StructValue) target).fields.put(assign.field().text(), value);
        } else if (statement instanceof ExprStmt expr) evaluate(expr.value(), scope);
        else if (statement instanceof When when) {
            if ((boolean) evaluate(when.condition(), scope)) execute(when.yes(), scope);
            else if (when.no() != null) execute(when.no(), scope);
        } else if (statement instanceof While loop) {
            while ((boolean) evaluate(loop.condition(), scope)) execute(loop.body(), scope);
        } else if (statement instanceof Each each) {
            Object iterable = evaluate(each.iterable(), scope);
            if (iterable instanceof List<?> list) {
                for (Object value : new ArrayList<>(list)) {
                    tick(each.token());
                    Scope iteration = new Scope(scope); iteration.values.put(each.name().text(), new Cell(Type.ANY, value));
                    statements(each.body(), iteration);
                }
                return;
            }
            Range range = (Range) iterable;
            // Falcon has no closures, so an each-variable's per-iteration cell
            // cannot escape. Reuse its lexical scope and cell instead of
            // allocating a HashMap and Cell for every integer in a large range.
            Scope iteration = new Scope(scope);
            Cell loopVariable = new Cell(Type.INT, range.start());
            for (long i = range.start(); i < range.end(); i++) {
                tick(each.token());
                iteration.values.clear();
                iteration.values.put(each.name().text(), loopVariable);
                loopVariable.value = i;
                statements(each.body(), iteration);
            }
        } else if (statement instanceof Give give) {
            throw new Returned(give.value() == null ? null : evaluate(give.value(), scope));
        } else if (statement instanceof TryCatch attempt) {
            try { execute(attempt.body(), scope); }
            catch (FalconError error) {
                Scope handler = new Scope(scope);
                handler.values.put(attempt.errorName().text(), new Cell(Type.STRING, error.getMessage()));
                execute(attempt.handler(), handler);
            }
        }
    }
    private Object evaluate(Expr expr, Scope scope) {
        tick(expr.token());
        try {
            if (expr instanceof Literal literal) return literal.value();
            if (expr instanceof ListExpr list) {
                var values = new ArrayList<Object>();
                for (Expr element : list.elements()) values.add(evaluate(element, scope));
                return values;
            }
            if (expr instanceof Index index) return Values.index(evaluate(index.target(), scope), evaluate(index.index(), scope), index.token());
            if (expr instanceof Field field) return ((StructValue) evaluate(field.target(), scope)).fields.get(field.name().text());
            if (expr instanceof Name name) return scope.find(name.token().text()).value;
            if (expr instanceof Unary unary) {
                Object value = evaluate(unary.value(), scope);
                return switch (unary.token().text()) {
                    case "!" -> !(boolean) value;
                    case "+" -> value;
                    case "-" -> {
                        if (value instanceof Long n) yield Math.negateExact(n);
                        yield finite(-((Number) value).doubleValue(), unary.token());
                    }
                    default -> throw new IllegalStateException();
                };
            }
            if (expr instanceof Call call) {
                List<Object> args = new ArrayList<>();
                for (Expr arg : call.arguments()) args.add(evaluate(arg, scope));
                StructDecl struct = checker.structs.get(call.target());
                if (struct != null) {
                    StructValue value = new StructValue(call.target());
                    for (int i = 0; i < args.size(); i++) value.fields.put(struct.fields().get(i).name().text(), args.get(i));
                    return value;
                }
                if (call.token().text().equals("say")) {
                    out.println(args.stream().map(Values::display).collect(Collectors.joining(" "))); return null;
                }
                if (StandardLibrary.function(call.target()) != null)
                    return runtime.invoke(call.target(), args, call.token());
                return invoke(checker.functions.get(call.target()), args, call.token());
            }
            if (expr instanceof Binary binary) {
                Object left = evaluate(binary.left(), scope);
                String op = binary.token().text();
                if (op.equals("&&")) return (boolean) left && (boolean) evaluate(binary.right(), scope);
                if (op.equals("||")) return (boolean) left || (boolean) evaluate(binary.right(), scope);
                Object right = evaluate(binary.right(), scope);
                if (op.equals("..")) return new Range((long) left, (long) right);
                if (op.equals("+") && (left instanceof String || right instanceof String)) return Values.display(left) + Values.display(right);
                if (op.equals("==") || op.equals("!=")) {
                    boolean equal = Values.equal(left, right);
                    return op.equals("==") ? equal : !equal;
                }
                if (left instanceof Long a && right instanceof Long b) {
                    return switch (op) {
                        case "+" -> Math.addExact(a, b); case "-" -> Math.subtractExact(a, b); case "*" -> Math.multiplyExact(a, b);
                        case "/" -> {
                            if (b == 0) throw new FalconError(binary.token(), "Cannot divide by zero.");
                            if (a == Long.MIN_VALUE && b == -1) throw new ArithmeticException();
                            yield a / b;
                        }
                        case "%" -> { if (b == 0) throw new FalconError(binary.token(), "Cannot divide by zero."); yield a % b; }
                        case "<" -> a < b; case "<=" -> a <= b; case ">" -> a > b; case ">=" -> a >= b;
                        default -> throw new IllegalStateException();
                    };
                }
                double a = ((Number) left).doubleValue(), b = ((Number) right).doubleValue();
                return switch (op) {
                    case "+" -> finite(a + b, binary.token()); case "-" -> finite(a - b, binary.token());
                    case "*" -> finite(a * b, binary.token());
                    case "/", "%" -> {
                        if (b == 0) throw new FalconError(binary.token(), "Cannot divide by zero.");
                        yield finite(op.equals("/") ? a / b : a % b, binary.token());
                    }
                    case "<" -> a < b; case "<=" -> a <= b; case ">" -> a > b; case ">=" -> a >= b;
                    default -> throw new IllegalStateException();
                };
            }
            throw new IllegalStateException("Unknown expression");
        } catch (ArithmeticException e) { throw new FalconError(expr.token(), "Integer overflow; use smaller values or float."); }
    }
    private void tick(Token token) {
        if (--remaining < 0) throw new FalconError(token, "Execution step limit reached; check your loops or increase --max-steps.");
    }
    private static Object coerce(Type type, Object value) {
        if (type == Type.FLOAT && value instanceof Long number) return number.doubleValue();
        return value;
    }
    private double finite(double value, Token token) {
        if (!Double.isFinite(value)) throw new FalconError(token, "Floating-point result is outside the finite range.");
        return value;
    }
}
