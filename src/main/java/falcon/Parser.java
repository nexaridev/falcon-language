package falcon;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import static falcon.Ast.*;

final class Parser {
    private static final Set<String> RESERVED = Set.of("func", "var", "fix", "if", "elseif", "when", "else", "while",
            "each", "in", "give", "true", "false", "int", "float", "number", "bool", "string", "void", "use", "as", "any", "list", "map", "null");
    private final List<Token> tokens;
    private int pos, parentheses;
    Parser(List<Token> tokens) { this.tokens = tokens; }

    Program parse() {
        List<Function> functions = new ArrayList<>();
        List<Import> imports = new ArrayList<>();
        separators();
        while (peek().kind() != Token.Kind.EOF) {
            if (match("use")) {
                Token start = tokens.get(pos - 1);
                String path;
                Token alias;
                boolean named = peek().kind() != Token.Kind.STRING;
                if (peek().kind() == Token.Kind.STRING) {
                    path = advance().text(); expect("as"); alias = name();
                } else {
                    alias = name(); path = alias.text() + ".fc";
                    if (match("as")) alias = name();
                }
                endStatement(); imports.add(new Import(start, path, alias, named)); separators(); continue;
            }
            expect("func");
            Token name = name();
            expect("("); newlines();
            List<Parameter> params = new ArrayList<>();
            if (!at(")")) do {
                newlines(); Token param = name(); expect(":");
                params.add(new Parameter(param, type())); newlines();
            } while (match(","));
            expect(")");
            Type result = match("->") ? type() : Type.VOID;
            functions.add(new Function(name, List.copyOf(params), result, block()));
            separators();
        }
        return new Program(List.copyOf(functions), List.copyOf(imports));
    }

    private Type type() {
        Token token = advance();
        if (token.kind() == Token.Kind.NAME) {
            return switch (token.text()) {
                case "int" -> Type.INT; case "float" -> Type.FLOAT; case "bool" -> Type.BOOL;
                case "string" -> Type.STRING; case "void" -> Type.VOID;
                case "number" -> Type.NUMBER; case "any" -> Type.ANY;
                case "list" -> {
                    if (match("<")) {
                        Type element = type(); expect(">");
                        if (element == Type.VOID || element == Type.RANGE || element == Type.MAP)
                            throw error(token, "List elements must have a value type, such as int, bool, string, number or any.");
                        yield Type.listOf(element);
                    }
                    yield Type.LIST;
                }
                case "map" -> Type.MAP;
                default -> throw error(token, "Unknown type '" + token.text() + "'.");
            };
        }
        throw error(token, "Expected a type: int, float, number, bool, string, list, map, any or void.");
    }

    private Block block() {
        newlines(); Token start = expect("{");
        List<Stmt> statements = new ArrayList<>(); separators();
        while (!at("}") && peek().kind() != Token.Kind.EOF) {
            statements.add(statement()); separators();
        }
        expect("}"); return new Block(start, List.copyOf(statements));
    }

    private Stmt statement() {
        Token start = peek();
        if (match("var") || match("fix")) {
            Token name = name(); Type annotation = match(":") ? type() : null;
            expect("="); newlines(); Expr value = expression(); endStatement();
            return new Binding(start, name, annotation, value, start.text().equals("var"));
        }
        if (match("if") || match("when")) return conditional(start);
        if (at("elseif") || at("else")) throw error(peek(), "'" + peek().text() + "' must follow an if/elseif block.");
        if (match("while")) return new While(start, condition(), block());
        if (match("each")) {
            expect("("); newlines(); Token name = name(); expect("in"); newlines();
            parentheses++; Expr iterable = expression(); parentheses--;
            newlines(); expect(")"); return new Each(start, name, iterable, block());
        }
        if (match("give")) {
            Expr value = boundary() ? null : expression(); endStatement();
            return new Give(start, value);
        }
        if (at("{")) return block();
        if (peek().kind() == Token.Kind.NAME && tokens.get(pos + 1).text().equals("=")) {
            Token name = name(); expect("="); newlines(); Expr value = expression(); endStatement();
            return new Assign(name, value);
        }
        Expr value = expression();
        if (value instanceof Index && at("="))
            throw error(peek(), "Index assignment isn't supported yet. Assign to a whole list variable instead.");
        endStatement(); return new ExprStmt(start, value);
    }

    private When conditional(Token start) {
        Expr condition = condition(); Block yes = block();
        newlines(); Stmt no = null;
        if (match("elseif")) no = conditional(tokens.get(pos - 1));
        else if (match("else")) {
            newlines();
            no = match("if") || match("when") ? conditional(tokens.get(pos - 1)) : block();
        }
        return new When(start, condition, yes, no);
    }

    private Expr condition() {
        expect("("); parentheses++; newlines(); Expr value = expression();
        newlines(); expect(")"); parentheses--; return value;
    }
    private Expr expression() { return binary(1); }
    private Expr binary(int minimum) {
        Expr left = unary();
        while (true) {
            if (parentheses > 0) newlines();
            int precedence = precedence(peek());
            if (precedence < minimum) break;
            Token operator = advance(); newlines();
            left = new Binary(operator, left, binary(precedence + 1));
        }
        return left;
    }
    private int precedence(Token token) {
        if (token.kind() != Token.Kind.SYMBOL) return 0;
        return switch (token.text()) {
            case "||" -> 1; case "&&" -> 2; case "==", "!=" -> 3;
            case "<", "<=", ">", ">=" -> 4; case ".." -> 5;
            case "+", "-" -> 6; case "*", "/", "%" -> 7; default -> 0;
        };
    }
    private Expr unary() {
        if (match("!") || match("-") || match("+")) {
            Token operator = tokens.get(pos - 1); newlines();
            return new Unary(operator, unary());
        }
        Expr value = primary();
        while (match("[")) {
            Token start = tokens.get(pos - 1); parentheses++; newlines();
            Expr index = expression(); newlines(); expect("]"); parentheses--;
            value = new Index(start, value, index);
        }
        return value;
    }
    private Expr primary() {
        if (parentheses > 0) newlines();
        Token token = advance();
        if (token.kind() == Token.Kind.NUMBER) {
            try {
                if (token.text().contains(".")) {
                    double value = Double.parseDouble(token.text());
                    if (!Double.isFinite(value)) throw new NumberFormatException();
                    return new Literal(token, value, Type.FLOAT);
                }
                return new Literal(token, Long.parseLong(token.text()), Type.INT);
            } catch (NumberFormatException e) { throw error(token, "Number is too large."); }
        }
        if (token.kind() == Token.Kind.STRING) return new Literal(token, token.text(), Type.STRING);
        if (token.kind() == Token.Kind.NAME) {
            if (token.text().equals("null")) return new Literal(token, null, Type.ANY);
            if (token.text().equals("true") || token.text().equals("false"))
                return new Literal(token, Boolean.parseBoolean(token.text()), Type.BOOL);
            if (RESERVED.contains(token.text())) throw error(token, "Expected an expression, found '" + token.text() + "'.");
            if (match(".")) {
                Token member = name();
                token = new Token(Token.Kind.NAME, token.text() + "." + member.text(), token.line(), token.column(), token.file());
                if (!at("(")) throw error(token, "Module members must be called as functions: " + token.text() + "(...).");
            }
            if (!match("(")) return new Name(token);
            parentheses++; newlines(); List<Expr> args = new ArrayList<>();
            if (!at(")")) do { newlines(); args.add(expression()); newlines(); } while (match(","));
            expect(")"); parentheses--; return new Call(token, List.copyOf(args));
        }
        if (token.kind() == Token.Kind.SYMBOL && token.text().equals("(")) {
            parentheses++; newlines(); Expr value = expression(); newlines(); expect(")"); parentheses--; return value;
        }
        if (token.kind() == Token.Kind.SYMBOL && token.text().equals("[")) {
            parentheses++; newlines(); List<Expr> elements = new ArrayList<>();
            if (!at("]")) {
                elements.add(expression()); newlines();
                while (match(",")) {
                    newlines(); if (at("]")) break;
                    elements.add(expression()); newlines();
                }
            }
            expect("]"); parentheses--; return new ListExpr(token, List.copyOf(elements));
        }
        throw error(token, "Expected an expression.");
    }
    private Token name() {
        Token token = advance();
        if (token.kind() != Token.Kind.NAME || RESERVED.contains(token.text()))
            throw error(token, "Expected a name, such as 'score'.");
        return token;
    }
    private void endStatement() {
        if (!boundary()) throw error(peek(), "Separate statements with a new line or ';'.");
        if (at(";")) advance();
    }
    private boolean boundary() { return at(";") || at("}") || peek().kind() == Token.Kind.NEWLINE || peek().kind() == Token.Kind.EOF; }
    private void newlines() { while (peek().kind() == Token.Kind.NEWLINE) advance(); }
    private void separators() { while (peek().kind() == Token.Kind.NEWLINE || at(";")) advance(); }
    private Token peek() { return tokens.get(pos); }
    private Token advance() { Token t = peek(); if (t.kind() != Token.Kind.EOF) pos++; return t; }
    private boolean at(String text) {
        return (peek().kind() == Token.Kind.NAME || peek().kind() == Token.Kind.SYMBOL) && peek().text().equals(text);
    }
    private boolean match(String text) { if (!at(text)) return false; advance(); return true; }
    private Token expect(String text) { if (!at(text)) throw error(peek(), "Expected '" + text + "'."); return advance(); }
    private FalconError error(Token token, String message) { return new FalconError(token, message); }
}
