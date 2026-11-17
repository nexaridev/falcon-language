package falcon;

import java.util.List;

final class Ast {
    private Ast() {}
    enum Type {
        INT, FLOAT, NUMBER, BOOL, STRING, VOID, RANGE, ANY, LIST, LIST_INT, LIST_FLOAT,
        LIST_NUMBER, LIST_BOOL, LIST_STRING, MAP, STRUCT;
        boolean isList() { return this == LIST || name().startsWith("LIST_"); }
        Type elementType() {
            return switch (this) {
                case LIST_INT -> INT; case LIST_FLOAT -> FLOAT; case LIST_NUMBER -> NUMBER;
                case LIST_BOOL -> BOOL; case LIST_STRING -> STRING; default -> ANY;
            };
        }
        static Type listOf(Type element) {
            return switch (element) {
                case INT -> LIST_INT; case FLOAT -> LIST_FLOAT; case NUMBER -> LIST_NUMBER;
                case BOOL -> LIST_BOOL; case STRING -> LIST_STRING; default -> LIST;
            };
        }
        @Override public String toString() {
            return switch (this) {
                case LIST_INT -> "list<int>"; case LIST_FLOAT -> "list<float>";
                case LIST_NUMBER -> "list<number>"; case LIST_BOOL -> "list<bool>";
                case LIST_STRING -> "list<string>"; case STRUCT -> "struct"; default -> name().toLowerCase();
            };
        }
    }
    record Import(Token token, String path, Token alias, boolean named) {}
    record Program(List<Function> functions, List<Import> imports, List<StructDecl> structs) {
        Program(List<Function> functions) { this(functions, List.of(), List.of()); }
        Program(List<Function> functions, List<Import> imports) { this(functions, imports, List.of()); }
    }
    record StructField(Token name, Type type) {}
    record StructDecl(Token name, List<StructField> fields) {}
    record Parameter(Token name, Type type) {}
    record Function(Token name, List<Parameter> parameters, Type result, Block body, String namespace) {
        Function(Token name, List<Parameter> parameters, Type result, Block body) {
            this(name, parameters, result, body, "");
        }
        String key() { return namespace + name.text(); }
    }

    sealed interface Stmt permits Block, Binding, Assign, IndexAssign, FieldAssign, ExprStmt, When, While, Each, Give, TryCatch {
        Token token();
    }
    record Block(Token token, List<Stmt> statements) implements Stmt {}
    record Binding(Token token, Token name, Type annotation, Expr value, boolean mutable) implements Stmt {}
    record Assign(Token token, Expr value) implements Stmt {}
    record IndexAssign(Token token, Expr target, Expr index, Expr value) implements Stmt {}
    record FieldAssign(Token token, Expr target, Token field, Expr value) implements Stmt {}
    record ExprStmt(Token token, Expr value) implements Stmt {}
    record When(Token token, Expr condition, Block yes, Stmt no) implements Stmt {}
    record While(Token token, Expr condition, Block body) implements Stmt {}
    record Each(Token token, Token name, Expr iterable, Block body) implements Stmt {}
    record Give(Token token, Expr value) implements Stmt {}
    record TryCatch(Token token, Block body, Token errorName, Block handler) implements Stmt {}

    sealed interface Expr permits Literal, Name, Unary, Binary, Call, ListExpr, Index, Field { Token token(); }
    record ListExpr(Token token, List<Expr> elements) implements Expr {}
    record Index(Token token, Expr target, Expr index) implements Expr {}
    record Field(Token token, Expr target, Token name) implements Expr {}
    record Literal(Token token, Object value, Type type) implements Expr {}
    record Name(Token token) implements Expr {}
    record Unary(Token token, Expr value) implements Expr {}
    record Binary(Token token, Expr left, Expr right) implements Expr {}
    record Call(Token token, List<Expr> arguments, String target) implements Expr {
        Call(Token token, List<Expr> arguments) { this(token, arguments, token.text()); }
    }
}
