package falcon;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import static falcon.Ast.*;

final class Parser {
    private static final Set<String> RESERVED = Set.of("func", "var", "fix", "if", "elseif", "when", "else", "while", "try", "catch",
            "each", "in", "give", "true", "false", "int", "float", "number", "bool", "string", "void", "use", "as", "any", "list", "map", "null", "struct", "match", "case", "defer");
    private final List<Token> tokens;
    private int pos, parentheses;
    Parser(List<Token> tokens) { this.tokens = tokens; }

    Program parse() {
        List<Function> functions = new ArrayList<>();
        List<StructDecl> structs = new ArrayList<>();
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
            if (match("struct")) {
                Token name = name(); newlines(); expect("{"); separators();
                List<StructField> fields = new ArrayList<>();
                while (!at("}") && peek().kind() != Token.Kind.EOF) {
                    Token field = name(); expect(":"); TypeSpec fieldType = type();
                    fields.add(new StructField(field, fieldType.type(), fieldType.structName()));
                    if (match(",")) separators(); else separators();
                }
                expect("}"); separators(); structs.add(new StructDecl(name, List.copyOf(fields))); continue;
            }
            expect("func");
            Token name = name();
            expect("("); newlines();
            List<Parameter> params = new ArrayList<>();
            if (!at(")")) do {
                newlines(); Token param = name(); expect(":");
                TypeSpec parameterType = type();
                params.add(new Parameter(param, parameterType.type(), parameterType.structName())); newlines();
            } while (match(","));
            expect(")");
            TypeSpec result = match("->") ? type() : new TypeSpec(Type.VOID, null);
            functions.add(new Function(name, List.copyOf(params), result.type(), result.structName(), block(), ""));
            separators();
        }
        return new Program(List.copyOf(functions), List.copyOf(imports), List.copyOf(structs));
    }

    private TypeSpec type() {
        Token token = advance();
        if (token.kind() == Token.Kind.NAME) {
            return switch (token.text()) {
                case "int" -> new TypeSpec(Type.INT, null); case "float" -> new TypeSpec(Type.FLOAT, null); case "bool" -> new TypeSpec(Type.BOOL, null);
                case "string" -> new TypeSpec(Type.STRING, null); case "void" -> new TypeSpec(Type.VOID, null);
                case "number" -> new TypeSpec(Type.NUMBER, null); case "any" -> new TypeSpec(Type.ANY, null);
                case "list" -> {
                    if (match("<")) {
                        TypeSpec element = type(); expect(">");
                        if (element.type() == Type.VOID || element.type() == Type.RANGE || element.type() == Type.MAP)
                            throw error(token, "List elements must have a value type, such as int, bool, string, number or any.");
                        yield new TypeSpec(Type.listOf(element.type()), element.type() == Type.STRUCT ? element.structName() : null);
                    }
                    yield new TypeSpec(Type.LIST, null);
                }
                case "map" -> new TypeSpec(Type.MAP, null);
                default -> new TypeSpec(Type.STRUCT, token.text());
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
            Token name = name(); TypeSpec annotation = match(":") ? type() : null;
            expect("="); newlines(); Expr value = expression(); endStatement();
            return new Binding(start, name, annotation == null ? null : annotation.type(),
                    annotation == null ? null : annotation.structName(), value, start.text().equals("var"));
        }
        if (match("if") || match("when")) return conditional(start);
        if (at("elseif") || at("else")) throw error(peek(), "'" + peek().text() + "' must follow an if/elseif block.");
        if (match("while")) return new While(start, condition(), block());
        if (match("try")) {
            Block body = block(); newlines(); expect("catch"); expect("("); newlines();
            Token errorName = name(); newlines(); expect(")");
            return new TryCatch(start, body, errorName, block());
        }
        if (match("defer")) return new Defer(start, block());
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
        if (match("=")) {
            if (value instanceof Index index) {
                newlines(); Expr replacement = expression(); endStatement();
                return new IndexAssign(start, index.target(), index.index(), replacement);
            }
            if (value instanceof Field field) {
                newlines(); Expr replacement = expression(); endStatement();
                return new FieldAssign(start, field.target(), field.name(), replacement);
            }
            throw error(tokens.get(pos - 1), "Only variables and list/map indexes can be assigned.");
        }
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
        while (true) {
            if (match("[")) {
                Token start = tokens.get(pos - 1); parentheses++; newlines();
                Expr index = expression(); newlines(); expect("]"); parentheses--;
                value = new Index(start, value, index);
            } else if (match(".")) {
                Token member = name();
                if (match("(")) {
                    parentheses++; newlines(); List<Expr> args = new ArrayList<>();
                    if (!at(")")) do { newlines(); args.add(expression()); newlines(); } while (match(","));
                    expect(")"); parentheses--;
                    if (!(value instanceof Name base)) throw error(member, "Only module functions use dotted calls.");
                    Token called = new Token(Token.Kind.NAME, base.token().text() + "." + member.text(), base.token().line(), base.token().column(), base.token().file());
                    value = new Call(called, List.copyOf(args));
                } else value = new Field(member, value, member);
            } else break;
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
        if (token.kind() == Token.Kind.STRING) return token.text().contains("$" + "{")
                ? interpolated(token) : new Literal(token, unescapeInterpolation(token.text()), Type.STRING);
        if (token.kind() == Token.Kind.NAME) {
            if (token.text().equals("if")) {
                Expr condition = at("(") ? condition() : expression();
                Expr yes = expressionBlock();
                newlines(); expect("else");
                Expr no = expressionBlock();
                return new ConditionalExpr(token, condition, yes, no);
            }
            if (token.text().equals("match")) return matchExpression(token);
            if (token.text().equals("null")) return new Literal(token, null, Type.ANY);
            if (token.text().equals("true") || token.text().equals("false"))
                return new Literal(token, Boolean.parseBoolean(token.text()), Type.BOOL);
            if (RESERVED.contains(token.text())) throw error(token, "Expected an expression, found '" + token.text() + "'.");
            if (!match("(")) return new Name(token);
            parentheses++; newlines(); List<Expr> args = new ArrayList<>();
            if (!at(")")) do { newlines(); args.add(expression()); newlines(); } while (match(","));
            expect(")"); parentheses--; return new Call(token, List.copyOf(args));
        }
        if (token.kind() == Token.Kind.SYMBOL && token.text().equals("(")) {
            if (lambdaAhead()) return lambda(token);
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
    private Expr expressionBlock() {
        newlines(); expect("{"); separators();
        Expr value = expression(); endStatement(); separators(); expect("}");
        return value;
    }
    private Expr matchExpression(Token token) {
        Expr target = condition(); newlines(); expect("{"); separators();
        List<MatchCase> cases = new ArrayList<>();
        while (!at("}") && peek().kind() != Token.Kind.EOF) {
            boolean fallback;
            Expr label = null;
            if (match("case")) { fallback = false; label = expression(); }
            else if (match("else")) fallback = true;
            else throw error(peek(), "Expected 'case' or 'else' in match expression.");
            expect("=>"); Expr value = expression();
            cases.add(new MatchCase(label, value, fallback)); separators();
        }
        expect("}");
        if (cases.isEmpty()) throw error(token, "A match expression needs at least one case.");
        if (cases.stream().noneMatch(MatchCase::fallback)) throw error(token, "Add an 'else =>' branch to cover unmatched values.");
        return new MatchExpr(token, target, List.copyOf(cases));
    }
    private Expr interpolated(Token token) {
        String text = token.text();
        List<String> fragments = new ArrayList<>();
        List<Expr> expressions = new ArrayList<>();
        int cursor = 0;
        while (true) {
            int start = text.indexOf("$" + "{", cursor);
            if (start < 0) { fragments.add(unescapeInterpolation(text.substring(cursor))); break; }
            fragments.add(unescapeInterpolation(text.substring(cursor, start)));
            int end = interpolationEnd(text, start + 2, token);
            String source = text.substring(start + 2, end).trim();
            if (source.isEmpty()) throw error(token, "Interpolation braces must contain an expression.");
            expressions.add(new Parser(new Lexer(source, token.file()).scan()).standaloneExpression());
            cursor = end + 1;
        }
        return new Interpolated(token, List.copyOf(fragments), List.copyOf(expressions));
    }
    private String unescapeInterpolation(String text) { return text.replace('\uE000', '$'); }
    private int interpolationEnd(String text, int start, Token token) {
        int depth = 1; boolean quoted = false, escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') quoted = false;
            } else if (c == '"') quoted = true;
            else if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return i;
        }
        throw error(token, "Unclosed string interpolation; add a closing brace.");
    }
    private Expr standaloneExpression() {
        separators(); Expr value = expression(); separators();
        if (peek().kind() != Token.Kind.EOF) throw error(peek(), "Unexpected text in interpolation.");
        return value;
    }
    private boolean lambdaAhead() {
        int nesting = 0;
        for (int i = pos; i < tokens.size(); i++) {
            Token candidate = tokens.get(i);
            if (candidate.text().equals("(")) nesting++;
            else if (candidate.text().equals(")")) {
                if (nesting == 0) return i + 1 < tokens.size() && tokens.get(i + 1).text().equals("=>");
                nesting--;
            }
        }
        return false;
    }
    private Expr lambda(Token start) {
        newlines();
        List<Parameter> parameters = new ArrayList<>();
        if (!at(")")) do {
            newlines(); Token param = name(); expect(":");
            TypeSpec type = type();
            parameters.add(new Parameter(param, type.type(), type.structName()));
            newlines();
        } while (match(","));
        expect(")"); expect("=>");
        return new LambdaExpr(start, List.copyOf(parameters), expression());
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
