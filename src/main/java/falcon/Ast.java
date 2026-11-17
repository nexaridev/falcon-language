package falcon;

import java.util.List;

final class Ast {
    private Ast() {}
    enum Type {
        INT, FLOAT, NUMBER, BOOL, STRING, VOID, RANGE, ANY, LIST, MAP;
        @Override public String toString() { return name().toLowerCase(); }
    }
    record Import(Token token, String path, Token alias, boolean named) {}
    record Program(List<Function> functions, List<Import> imports) {
        Program(List<Function> functions) { this(functions, List.of()); }
    }
    record Parameter(Token name, Type type) {}
    record Function(Token name, List<Parameter> parameters, Type result, Block body, String namespace) {
        Function(Token name, List<Parameter> parameters, Type result, Block body) {
            this(name, parameters, result, body, "");
        }
        String key() { return namespace + name.text(); }
    }

    sealed interface Stmt permits Block, Binding, Assign, ExprStmt, When, While, Each, Give {
        Token token();
    }
    record Block(Token token, List<Stmt> statements) implements Stmt {}
    record Binding(Token token, Token name, Type annotation, Expr value, boolean mutable) implements Stmt {}
    record Assign(Token token, Expr value) implements Stmt {}
    record ExprStmt(Token token, Expr value) implements Stmt {}
    record When(Token token, Expr condition, Block yes, Stmt no) implements Stmt {}
    record While(Token token, Expr condition, Block body) implements Stmt {}
    record Each(Token token, Token name, Expr iterable, Block body) implements Stmt {}
    record Give(Token token, Expr value) implements Stmt {}

    sealed interface Expr permits Literal, Name, Unary, Binary, Call, ListExpr, Index { Token token(); }
    record ListExpr(Token token, List<Expr> elements) implements Expr {}
    record Index(Token token, Expr target, Expr index) implements Expr {}
    record Literal(Token token, Object value, Type type) implements Expr {}
    record Name(Token token) implements Expr {}
    record Unary(Token token, Expr value) implements Expr {}
    record Binary(Token token, Expr left, Expr right) implements Expr {}
    record Call(Token token, List<Expr> arguments, String target) implements Expr {
        Call(Token token, List<Expr> arguments) { this(token, arguments, token.text()); }
    }
}
