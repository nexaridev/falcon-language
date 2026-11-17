package falcon;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import static falcon.Ast.*;

/** Resolves scopes and checks every function before execution, including unused code. */
final class Checker {
    record Symbol(Type type, boolean mutable) {}
    final Map<String, Function> functions = new LinkedHashMap<>();
    final Map<Expr, Type> types = new IdentityHashMap<>();
    private final Deque<Map<String, Symbol>> scopes = new ArrayDeque<>();
    private Type result;
    private String namespace = "";

    void check(Program program) {
        check(program, true);
    }

    void check(Program program, boolean requireMain) {
        if (!program.imports().isEmpty())
            fail(program.imports().get(0).token(), "Load module imports before checking the program.");
        for (Function function : program.functions()) {
            String name = function.name().text();
            if (name.equals("say") || functions.putIfAbsent(function.key(), function) != null)
                fail(function.name(), "Function '" + name + "' is already defined.");
        }
        Function main = functions.get("main");
        if (main == null && requireMain) fail(new Token(Token.Kind.NAME, "main", 1, 1), "Add an entry point: func main() { ... }");
        if (main != null && (!main.parameters().isEmpty() || main.result() != Type.VOID))
            fail(main.name(), "The entry point must be 'func main()' with no parameters or return value.");
        for (Function function : program.functions()) {
            namespace = function.namespace();
            result = function.result(); scopes.push(new HashMap<>());
            for (Parameter param : function.parameters()) {
                valueType(param.name(), param.type());
                declare(param.name(), new Symbol(param.type(), true));
            }
            boolean returns = statements(function.body());
            if (result != Type.VOID && !returns)
                fail(function.name(), "Function '" + function.name().text() + "' must give a " + result + " on every path.");
            scopes.pop();
        }
    }

    private boolean statements(Block block) {
        boolean returns = false;
        for (Stmt statement : block.statements()) {
            if (returns) fail(statement.token(), "This statement cannot run because the preceding code always returns.");
            returns = statement(statement);
        }
        return returns;
    }
    private boolean statement(Stmt statement) {
        if (statement instanceof Block block) {
            scopes.push(new HashMap<>()); boolean returns = statements(block); scopes.pop(); return returns;
        }
        if (statement instanceof Binding binding) {
            Type actual = expression(binding.value()); valueType(binding.name(), actual);
            Type type = binding.annotation() == null ? actual : binding.annotation();
            valueType(binding.name(), type); compatible(binding.name(), type, actual);
            declare(binding.name(), new Symbol(type, binding.mutable()));
        } else if (statement instanceof Assign assign) {
            Symbol symbol = resolve(assign.token());
            if (!symbol.mutable()) fail(assign.token(), "Cannot reassign '" + assign.token().text() + "'; it was declared with 'fix'.");
            compatible(assign.token(), symbol.type(), expression(assign.value()));
        } else if (statement instanceof ExprStmt expr) {
            expression(expr.value());
            if (!(expr.value() instanceof Call)) fail(expr.token(), "This expression has no effect; assign it or pass it to say(...).");
        } else if (statement instanceof When when) {
            require(when.token(), Type.BOOL, expression(when.condition()));
            boolean yes = statement(when.yes());
            boolean no = when.no() != null && statement(when.no());
            return yes && no;
        } else if (statement instanceof While loop) {
            require(loop.token(), Type.BOOL, expression(loop.condition())); statement(loop.body());
        } else if (statement instanceof Each each) {
            Type iterable = expression(each.iterable());
            if (iterable != Type.RANGE && iterable != Type.LIST) fail(each.token(), "Expected range or list, got " + iterable + ".");
            scopes.push(new HashMap<>()); declare(each.name(), new Symbol(iterable == Type.RANGE ? Type.INT : Type.ANY, false));
            statements(each.body()); scopes.pop();
        } else if (statement instanceof Give give) {
            Type actual = give.value() == null ? Type.VOID : expression(give.value());
            if (give.value() != null && actual == Type.VOID) fail(give.token(), "A void call has no value to return; use 'give' alone.");
            compatible(give.token(), result, actual); return true;
        }
        return false;
    }

    private Type expression(Expr expr) {
        Type type;
        if (expr instanceof Literal literal) type = literal.type();
        else if (expr instanceof ListExpr list) {
            for (Expr element : list.elements()) {
                Type actual = expression(element);
                if (actual == Type.VOID || actual == Type.RANGE) fail(element.token(), "Cannot store " + actual + " in a list.");
            }
            type = Type.LIST;
        } else if (expr instanceof Index index) {
            Type target = expression(index.target());
            if (target != Type.LIST && target != Type.MAP && target != Type.STRING) fail(index.token(), "Indexing requires a list, map or string.");
            require(index.token(), target == Type.MAP ? Type.STRING : Type.INT, expression(index.index()));
            type = target == Type.STRING ? Type.STRING : Type.ANY;
        }
        else if (expr instanceof Name name) type = resolve(name.token()).type();
        else if (expr instanceof Unary unary) {
            Type operand = expression(unary.value());
            if (unary.token().text().equals("!")) { require(unary.token(), Type.BOOL, operand); type = Type.BOOL; }
            else { numeric(unary.token(), operand); type = operand; }
        } else if (expr instanceof Binary binary) {
            Type left = expression(binary.left()), right = expression(binary.right());
            String op = binary.token().text();
            if (op.equals("&&") || op.equals("||")) {
                require(binary.token(), Type.BOOL, left); require(binary.token(), Type.BOOL, right); type = Type.BOOL;
            } else if (op.equals("..")) {
                require(binary.token(), Type.INT, left); require(binary.token(), Type.INT, right); type = Type.RANGE;
            } else if (op.equals("+") && (left == Type.STRING || right == Type.STRING)) {
                printable(binary.token(), left); printable(binary.token(), right); type = Type.STRING;
            } else if (op.equals("==") || op.equals("!=")) {
                if (left == Type.VOID || right == Type.VOID || left == Type.RANGE || right == Type.RANGE
                        || (!(isNumeric(left) && isNumeric(right)) && left != right && left != Type.ANY && right != Type.ANY))
                    fail(binary.token(), "Cannot compare " + left + " with " + right + ".");
                type = Type.BOOL;
            } else {
                numeric(binary.token(), left); numeric(binary.token(), right);
                type = switch (op) {
                    case "<", "<=", ">", ">=" -> Type.BOOL;
                    default -> left == Type.NUMBER || right == Type.NUMBER ? Type.NUMBER
                            : left == Type.FLOAT || right == Type.FLOAT ? Type.FLOAT : Type.INT;
                };
            }
        } else if (expr instanceof Call call) {
            if (call.token().text().equals("say")) {
                for (Expr arg : call.arguments()) printable(arg.token(), expression(arg));
                type = Type.VOID;
            } else {
                Function function = functions.get(call.target());
                if (function == null) function = StandardLibrary.function(call.target());
                if (function == null) throw new FalconError(call.token(), "Unknown function '" + call.token().text() + "'.");
                if (function.parameters().size() != call.arguments().size())
                    fail(call.token(), "Expected " + function.parameters().size() + " arguments, got " + call.arguments().size() + ".");
                for (int i = 0; i < call.arguments().size(); i++)
                    compatible(call.arguments().get(i).token(), function.parameters().get(i).type(), expression(call.arguments().get(i)));
                type = function.result();
            }
        } else throw new IllegalStateException("Unknown expression");
        types.put(expr, type); return type;
    }

    private void declare(Token name, Symbol symbol) {
        if (functions.containsKey(namespace + name.text()) || name.text().equals("say"))
            fail(name, "A variable cannot use the function name '" + name.text() + "'.");
        if (scopes.peek().putIfAbsent(name.text(), symbol) != null)
            fail(name, "'" + name.text() + "' is already declared in this scope.");
    }
    private Symbol resolve(Token name) {
        for (Map<String, Symbol> scope : scopes) if (scope.containsKey(name.text())) return scope.get(name.text());
        throw new FalconError(name, "Unknown variable '" + name.text() + "'. Declare it with 'var' or 'fix' first.");
    }
    private void valueType(Token token, Type type) {
        if (type == Type.VOID) fail(token, "A variable or parameter cannot have type void.");
    }
    private void printable(Token token, Type type) {
        if (type == Type.VOID || type == Type.RANGE) fail(token, "Cannot print or concatenate a " + type + " value.");
    }
    private static boolean isNumeric(Type type) { return type == Type.INT || type == Type.FLOAT || type == Type.NUMBER; }
    private void numeric(Token token, Type type) { if (!isNumeric(type)) fail(token, "Expected a number, got " + type + "."); }
    private void compatible(Token token, Type expected, Type actual) {
        if (expected == Type.ANY && actual != Type.VOID && actual != Type.RANGE) return;
        if (expected == Type.NUMBER && isNumeric(actual)) return;
        if (expected == Type.FLOAT && isNumeric(actual)) return;
        require(token, expected, actual);
    }
    private void require(Token token, Type expected, Type actual) {
        if (expected != actual) fail(token, "Expected " + expected + ", got " + actual + ".");
    }
    private void fail(Token token, String message) { throw new FalconError(token, message); }
}
