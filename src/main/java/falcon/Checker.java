package falcon;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static falcon.Ast.*;

/** Resolves scopes and checks every function before execution, including unused code. */
final class Checker {
    record FunctionType(List<Parameter> parameters, Type result, String resultStructName) {}
    record Symbol(Type type, boolean mutable, String structName, Type listElementType, String listElementStructName,
                  FunctionType functionType) {
        Symbol(Type type, boolean mutable) { this(type, mutable, null, null, null, null); }
        Symbol(Type type, boolean mutable, String structName) { this(type, mutable, structName, null, null, null); }
        Symbol(Type type, boolean mutable, String structName, Type listElementType, String listElementStructName) {
            this(type, mutable, structName, listElementType, listElementStructName, null);
        }
    }
    final Map<String, Function> functions = new LinkedHashMap<>();
    final Map<Expr, Type> types = new IdentityHashMap<>();
    final java.util.List<FalconError> errors = new java.util.ArrayList<>();
    final Map<String, StructDecl> structs = new LinkedHashMap<>();
    final Map<Expr, String> structNames = new IdentityHashMap<>();
    final Map<Expr, String> listStructNames = new IdentityHashMap<>();
    final Map<Expr, Type> listElementTypes = new IdentityHashMap<>();
    final Map<Expr, FunctionType> functionTypes = new IdentityHashMap<>();
    private final Deque<Map<String, Symbol>> scopes = new ArrayDeque<>();
    private Type result;
    private String resultStructName;
    private String namespace = "";

    void check(Program program) {
        check(program, true);
    }

    void check(Program program, boolean requireMain) {
        if (!program.imports().isEmpty())
            fail(program.imports().get(0).token(), "Load module imports before checking the program.");
        for (StructDecl struct : program.structs()) {
            if (structs.putIfAbsent(struct.name().text(), struct) != null)
                fail(struct.name(), "Struct '" + struct.name().text() + "' is already defined.");
        }
        for (StructDecl struct : program.structs()) {
            var fields = new java.util.HashSet<String>();
            for (StructField field : struct.fields()) {
                if (!fields.add(field.name().text())) fail(field.name(), "Field '" + field.name().text() + "' is already declared.");
                if (field.type() == Type.VOID || field.type() == Type.RANGE)
                    fail(field.name(), "Struct fields need a concrete value type.");
                validateStructType(field.name(), field.type(), field.structName());
            }
        }
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
            result = function.result(); resultStructName = function.resultStructName(); scopes.push(new HashMap<>());
            validateStructType(function.name(), function.result(), function.resultStructName());
            for (Parameter param : function.parameters()) {
                valueType(param.name(), param.type());
                validateStructType(param.name(), param.type(), param.structName());
                declare(param.name(), new Symbol(param.type(), true,
                        param.type() == Type.STRUCT ? param.structName() : null, null,
                        param.type() == Type.LIST_STRUCT ? param.structName() : null));
            }
            boolean returns;
            try {
                returns = statements(function.body());
                if (result != Type.VOID && !returns)
                    fail(function.name(), "Function '" + function.name().text() + "' must give a " + result + " on every path.");
            } catch (FalconError error) {
                errors.add(error);
            } finally { scopes.pop(); }
        }
    }

    private boolean statements(Block block) {
        boolean returns = false;
        for (Stmt statement : block.statements()) {
            if (returns) fail(statement.token(), "This statement cannot run because the preceding code always returns.");
            try { returns = statement(statement); }
            catch (FalconError error) { errors.add(error); returns = false; }
        }
        return returns;
    }
    private boolean statement(Stmt statement) {
        if (statement instanceof Block block) {
            scopes.push(new HashMap<>());
            try { return statements(block); } finally { scopes.pop(); }
        }
        if (statement instanceof Binding binding) {
            try {
                Type actual = expression(binding.value()); valueType(binding.name(), actual);
                Type type = binding.annotation() == null ? actual : binding.annotation();
                valueType(binding.name(), type);
                if (binding.annotation() != null) {
                    validateStructType(binding.name(), binding.annotation(), binding.annotationStructName());
                    compatibleRef(binding.name(), type, binding.annotationStructName(), actual, binding.value());
                }
                String structName = type == Type.STRUCT
                        ? (binding.annotation() == null ? structNames.get(binding.value()) : binding.annotationStructName()) : null;
                Type listElement = type.isList()
                        ? (binding.annotation() == null ? listElementTypes.get(binding.value()) : type.elementType()) : null;
                String elementStruct = type.isList()
                        ? (binding.annotation() == null ? listStructNames.get(binding.value()) : binding.annotationStructName()) : null;
                declare(binding.name(), new Symbol(type, binding.mutable(), structName, listElement, elementStruct,
                        functionTypes.get(binding.value())));
            } catch (FalconError error) {
                String name = binding.name().text();
                if (!scopes.peek().containsKey(name) && !functions.containsKey(namespace + name) && !name.equals("say")) {
                    Type fallbackType = binding.annotation() == null ? Type.ANY : binding.annotation();
                    String fallbackStruct = binding.annotationStructName();
                    if (binding.annotation() == null && binding.value() instanceof Call call && structs.containsKey(call.target())) {
                        fallbackType = Type.STRUCT; fallbackStruct = call.target();
                    }
                    scopes.peek().put(name, new Symbol(fallbackType, binding.mutable(),
                            fallbackType == Type.STRUCT ? fallbackStruct : null,
                            fallbackType.isList() ? fallbackType.elementType() : null,
                            fallbackType == Type.LIST_STRUCT ? fallbackStruct : null));
                }
                throw error;
            }
        } else if (statement instanceof Assign assign) {
            Symbol symbol = resolve(assign.token());
            if (!symbol.mutable()) fail(assign.token(), "Cannot reassign '" + assign.token().text() + "'; it was declared with 'fix'.");
            compatibleRef(assign.token(), symbol.type(),
                    symbol.type() == Type.STRUCT ? symbol.structName()
                            : symbol.type().isList() ? symbol.listElementStructName() : null,
                    expression(assign.value()), assign.value());
        } else if (statement instanceof IndexAssign assign) {
            Type target = expression(assign.target());
            if (!target.isList() && target != Type.MAP) fail(assign.token(), "Index assignment requires a list or map.");
            Type key = expression(assign.index());
            require(assign.token(), target == Type.MAP ? Type.STRING : Type.INT, key);
            Type actual = expression(assign.value());
            Type elementType = target == Type.MAP ? Type.ANY : target == Type.LIST
                    ? listElementTypes.getOrDefault(assign.target(), Type.ANY) : target.elementType();
            String elementStruct = target == Type.LIST_STRUCT ? listStructNames.get(assign.target()) : null;
            compatibleRef(assign.token(), elementType, elementStruct, actual, assign.value());
        } else if (statement instanceof FieldAssign assign) {
            Type targetType = expression(assign.target());
            String structName = structNames.get(assign.target());
            if (targetType != Type.STRUCT || structName == null) fail(assign.token(), "Field assignment requires a struct value.");
            StructField field = field(structName, assign.field());
            compatibleRef(assign.field(), field.type(), field.structName(), expression(assign.value()), assign.value());
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
            if (iterable != Type.RANGE && !iterable.isList()) fail(each.token(), "Expected range or list, got " + iterable + ".");
            scopes.push(new HashMap<>());
            try {
                Type element = iterable == Type.RANGE ? Type.INT : iterable == Type.LIST
                        ? listElementTypes.getOrDefault(each.iterable(), Type.ANY) : iterable.elementType();
                String elementStruct = iterable == Type.LIST_STRUCT || iterable == Type.LIST
                        ? listStructNames.get(each.iterable()) : null;
                declare(each.name(), new Symbol(element, false, element == Type.STRUCT ? elementStruct : null));
                statements(each.body());
            } finally { scopes.pop(); }
        } else if (statement instanceof Give give) {
            Type actual = give.value() == null ? Type.VOID : expression(give.value());
            if (give.value() != null && actual == Type.VOID) fail(give.token(), "A void call has no value to return; use 'give' alone.");
            if (give.value() == null && result != Type.VOID)
                fail(give.token(), "Expected " + result + ", got void. A newline ends the 'give' statement; put the returned value on the same line, for example: give 42.");
            compatibleRef(give.token(), result, resultStructName, actual, give.value()); return true;
        } else if (statement instanceof TryCatch attempt) {
            boolean bodyReturns = statement(attempt.body());
            scopes.push(new HashMap<>());
            boolean handlerReturns;
            try {
                declare(attempt.errorName(), new Symbol(Type.STRING, false));
                handlerReturns = statements(attempt.handler());
            } finally { scopes.pop(); }
            return bodyReturns && handlerReturns;
        } else if (statement instanceof Defer defer) {
            statement(defer.body());
        }
        return false;
    }

    private Type expression(Expr expr) {
        Type type;
        if (expr instanceof Literal literal) type = literal.type();
        else if (expr instanceof ListExpr list) {
            Type elementType = null;
            for (Expr element : list.elements()) {
                Type actual = expression(element);
                if (actual == Type.VOID || actual == Type.RANGE) fail(element.token(), "Cannot store " + actual + " in a list.");
                if (elementType == null) elementType = actual;
                else if (elementType != actual) {
                    if (isNumeric(elementType) && isNumeric(actual))
                        elementType = elementType == Type.NUMBER || actual == Type.NUMBER ? Type.NUMBER
                                : elementType == Type.FLOAT || actual == Type.FLOAT ? Type.FLOAT : Type.INT;
                    else elementType = Type.ANY;
                }
            }
            type = list.elements().isEmpty() ? Type.LIST : Type.listOf(elementType == null ? Type.ANY : elementType);
            if (!list.elements().isEmpty()) listElementTypes.put(list, elementType == null ? Type.ANY : elementType);
            if (elementType == Type.STRUCT) {
                String firstStruct = structNames.get(list.elements().get(0));
                boolean sameStruct = firstStruct != null && list.elements().stream().allMatch(e -> firstStruct.equals(structNames.get(e)));
                if (sameStruct) listStructNames.put(list, firstStruct);
                else type = Type.LIST;
            } else if (elementType == Type.LIST_STRUCT) {
                String firstStruct = listStructNames.get(list.elements().get(0));
                boolean sameStruct = firstStruct != null && list.elements().stream().allMatch(e -> firstStruct.equals(listStructNames.get(e)));
                if (sameStruct) listStructNames.put(list, firstStruct);
                else type = Type.LIST;
            }
        } else if (expr instanceof Index index) {
            Type target = expression(index.target());
            if (!target.isList() && target != Type.MAP && target != Type.STRING) fail(index.token(), "Indexing requires a list, map or string.");
            require(index.token(), target == Type.MAP ? Type.STRING : Type.INT, expression(index.index()));
            type = target == Type.STRING ? Type.STRING : target == Type.LIST
                    ? listElementTypes.getOrDefault(index.target(), Type.ANY)
                    : target.isList() ? target.elementType() : Type.ANY;
            if (type == Type.STRUCT && listStructNames.containsKey(index.target()))
                structNames.put(index, listStructNames.get(index.target()));
            if (type.isList() && listElementTypes.containsKey(index.target()))
                listElementTypes.put(index, listElementTypes.get(index.target()));
            if (type == Type.LIST_STRUCT && listStructNames.containsKey(index.target()))
                listStructNames.put(index, listStructNames.get(index.target()));
        }
        else if (expr instanceof Field fieldExpr) {
            Type target = expression(fieldExpr.target());
            String structName = structNames.get(fieldExpr.target());
            if (target != Type.STRUCT || structName == null) fail(fieldExpr.name(), "Field access requires a struct value.");
            StructField declaration = field(structName, fieldExpr.name());
            type = declaration.type();
            if (type == Type.STRUCT) structNames.put(fieldExpr, declaration.structName());
            if (type == Type.LIST_STRUCT) listStructNames.put(fieldExpr, declaration.structName());
        }
        else if (expr instanceof ConditionalExpr conditional) {
            require(conditional.token(), Type.BOOL, expression(conditional.condition()));
            Type yes = expression(conditional.yes()), no = expression(conditional.no());
            type = mergeTypes(conditional.token(), yes, no);
            String yesStruct = structNames.get(conditional.yes()), noStruct = structNames.get(conditional.no());
            if (type == Type.STRUCT && yesStruct != null && yesStruct.equals(noStruct)) structNames.put(expr, yesStruct);
            String leftStruct = listStructNames.get(conditional.yes()), rightStruct = listStructNames.get(conditional.no());
            if (type == Type.LIST_STRUCT && leftStruct != null && leftStruct.equals(rightStruct)) listStructNames.put(expr, leftStruct);
        }
        else if (expr instanceof Interpolated interpolated) {
            for (Expr part : interpolated.expressions()) printable(part.token(), expression(part));
            type = Type.STRING;
        }
        else if (expr instanceof MatchExpr match) {
            Type target = expression(match.target());
            Type resultType = null; String resultStruct = null;
            for (MatchCase branch : match.cases()) {
                if (!branch.fallback()) compatible(match.target().token(), target, expression(branch.label()));
                Type branchType = expression(branch.value());
                if (resultType == null) {
                    resultType = branchType; resultStruct = structNames.get(branch.value());
                } else {
                    Type merged = mergeTypes(match.token(), resultType, branchType);
                    if (merged == Type.STRUCT && !java.util.Objects.equals(resultStruct, structNames.get(branch.value())))
                        fail(match.token(), "All match branches must return the same struct type.");
                    resultType = merged;
                }
            }
            type = resultType == null ? Type.ANY : resultType;
            if (type == Type.STRUCT && resultStruct != null) structNames.put(expr, resultStruct);
        }
        else if (expr instanceof Name name) {
            Symbol symbol = resolve(name.token()); type = symbol.type();
            if (symbol.structName() != null) structNames.put(expr, symbol.structName());
            if (symbol.listElementType() != null) listElementTypes.put(expr, symbol.listElementType());
            if (symbol.listElementStructName() != null) listStructNames.put(expr, symbol.listElementStructName());
            if (symbol.functionType() != null) functionTypes.put(expr, symbol.functionType());
        }
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
        } else if (expr instanceof LambdaExpr lambda) {
            scopes.push(new HashMap<>());
            try {
                for (Parameter parameter : lambda.parameters()) {
                    valueType(parameter.name(), parameter.type());
                    validateStructType(parameter.name(), parameter.type(), parameter.structName());
                    declare(parameter.name(), new Symbol(parameter.type(), false,
                            parameter.type() == Type.STRUCT ? parameter.structName() : null,
                            null, parameter.type() == Type.LIST_STRUCT ? parameter.structName() : null));
                }
                Type bodyType = expression(lambda.body());
                if (bodyType == Type.VOID || bodyType == Type.RANGE) fail(lambda.token(), "A lambda must return a value.");
                FunctionType signature = new FunctionType(lambda.parameters(), bodyType, structNames.get(lambda.body()));
                functionTypes.put(lambda, signature);
                type = Type.FUNCTION;
            } finally { scopes.pop(); }
        } else if (expr instanceof Call call) {
            StructDecl struct = structs.get(call.target());
            if (struct != null) {
                if (call.arguments().size() != struct.fields().size())
                    fail(call.token(), "Struct '" + call.target() + "' expects " + struct.fields().size() + " field values.");
                for (int i = 0; i < call.arguments().size(); i++) {
                    Expr argument = call.arguments().get(i);
                    compatibleRef(argument.token(), struct.fields().get(i).type(), struct.fields().get(i).structName(),
                            expression(argument), argument);
                }
                type = Type.STRUCT; structNames.put(call, call.target());
            } else
            if (call.token().text().equals("say")) {
                for (Expr arg : call.arguments()) printable(arg.token(), expression(arg));
                type = Type.VOID;
            } else {
                Function function = functions.get(call.target());
                if (function == null) function = StandardLibrary.function(call.target());
                if (function == null) {
                    Symbol callable = null;
                    for (Map<String, Symbol> scope : scopes) {
                        if (scope.containsKey(call.token().text())) {
                            callable = scope.get(call.token().text());
                            break;
                        }
                    }
                    if (callable == null)
                        fail(call.token(), "Unknown function '" + call.token().text() + "'. Check the name or define the function first.");
                    if (callable.type() != Type.FUNCTION || callable.functionType() == null)
                        fail(call.token(), "Variable '" + call.token().text() + "' is not callable.");
                    FunctionType signature = callable.functionType();
                    if (signature.parameters().size() != call.arguments().size())
                        fail(call.token(), "Expected " + signature.parameters().size() + " arguments, got " + call.arguments().size() + ".");
                    for (int i = 0; i < call.arguments().size(); i++) {
                        Expr argument = call.arguments().get(i); Parameter parameter = signature.parameters().get(i);
                        compatibleRef(argument.token(), parameter.type(), parameter.structName(), expression(argument), argument);
                    }
                    type = signature.result();
                    if (type == Type.STRUCT) structNames.put(call, signature.resultStructName());
                } else {
                    if (function.parameters().size() != call.arguments().size())
                        fail(call.token(), "Expected " + function.parameters().size() + " arguments, got " + call.arguments().size() + ".");
                    for (int i = 0; i < call.arguments().size(); i++) {
                        Expr argument = call.arguments().get(i);
                        Parameter parameter = function.parameters().get(i);
                        compatibleRef(argument.token(), parameter.type(), parameter.structName(), expression(argument), argument);
                    }
                    type = function.result();
                    if (type == Type.STRUCT) structNames.put(call, function.resultStructName());
                    if (type == Type.LIST_STRUCT) listStructNames.put(call, function.resultStructName());
                }
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
        throw new FalconError(name, "Unknown variable '" + name.text() + "'. I couldn't find it; check the spelling or declare it with 'var' or 'fix' first.");
    }
    private StructField field(String structName, Token fieldName) {
        StructDecl struct = structs.get(structName);
        if (struct == null) fail(fieldName, "Unknown struct '" + structName + "'.");
        for (StructField field : struct.fields()) if (field.name().text().equals(fieldName.text())) return field;
        fail(fieldName, "Struct '" + structName + "' has no field '" + fieldName.text() + "'.");
        throw new IllegalStateException();
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
        if (expected == Type.LIST && actual.isList()) return;
        if (expected.isList() && actual == Type.LIST) return;
        if (expected == Type.ANY && actual != Type.VOID && actual != Type.RANGE) return;
        if (expected == Type.NUMBER && isNumeric(actual)) return;
        if (expected == Type.FLOAT && isNumeric(actual)) return;
        require(token, expected, actual);
    }
    private Type mergeTypes(Token token, Type left, Type right) {
        if (left == right) return left;
        if (isNumeric(left) && isNumeric(right))
            return left == Type.NUMBER || right == Type.NUMBER ? Type.NUMBER
                    : left == Type.FLOAT || right == Type.FLOAT ? Type.FLOAT : Type.INT;
        fail(token, "Expression branches have different types: " + left + " and " + right + ".");
        return Type.ANY;
    }
    private void compatibleRef(Token token, Type expected, String expectedStruct, Type actual, Expr actualExpr) {
        if (expected == Type.STRUCT && actual == Type.STRUCT && expectedStruct != null) {
            String actualStruct = actualExpr == null ? null : structNames.get(actualExpr);
            if (!expectedStruct.equals(actualStruct))
                fail(token, "Expected " + expectedStruct + ", got " + (actualStruct == null ? "an unknown struct" : actualStruct) + ".");
            return;
        }
        if (expected == Type.LIST_STRUCT && actual == Type.LIST_STRUCT && expectedStruct != null) {
            String actualStruct = actualExpr == null ? null : listStructNames.get(actualExpr);
            if (!expectedStruct.equals(actualStruct))
                fail(token, "Expected list<" + expectedStruct + ">, got list<" + (actualStruct == null ? "unknown" : actualStruct) + ">.");
            return;
        }
        if (expected == Type.LIST_STRUCT && actual == Type.LIST && actualExpr instanceof ListExpr list && list.elements().isEmpty()) return;
        if (expected.isList() && expected != Type.LIST && actual == Type.LIST)
            fail(token, "Expected " + listTypeName(expected, expectedStruct) + ", got list<any>.");
        compatible(token, expected, actual);
    }
    private String listTypeName(Type type, String structName) {
        return type == Type.LIST_STRUCT && structName != null ? "list<" + structName + ">" : type.toString();
    }
    private void validateStructType(Token token, Type type, String structName) {
        if ((type == Type.STRUCT || type == Type.LIST_STRUCT)
                && (structName == null || !structs.containsKey(structName)))
            fail(token, "Unknown type '" + (structName == null ? "struct" : structName) + "'.");
    }
    private void require(Token token, Type expected, Type actual) {
        if (expected != actual) fail(token, "Expected " + expected + ", got " + actual + ".");
    }
    private void fail(Token token, String message) { throw new FalconError(token, message); }
}
