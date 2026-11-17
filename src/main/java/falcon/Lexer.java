package falcon;

import java.util.ArrayList;
import java.util.List;

final class Lexer {
    private final String source;
    private final String file;
    private final List<Token> tokens = new ArrayList<>();
    private int pos, line = 1, column = 1;

    Lexer(String source) { this(source, ""); }
    Lexer(String source, String file) { this.source = source; this.file = file; }

    List<Token> scan() {
        while (!end()) {
            int start = pos, row = line, col = column;
            char c = take();
            if (c == ' ' || c == '\t' || c == '\f' || (c == '\uFEFF' && start == 0)) continue;
            if (c == '\n' || c == '\r') {
                if (c == '\r') {
                    if (peek() == '\n') take();
                    else { line++; column = 1; }
                }
                tokens.add(new Token(Token.Kind.NEWLINE, "\n", row, col));
                continue;
            }
            if (c == '/' && peek() == '/') {
                while (!end() && peek() != '\n' && peek() != '\r') take();
                continue;
            }
            if (c == '/' && peek() == '*') {
                take();
                int depth = 1;
                while (depth > 0 && !end()) {
                    if (peek() == '/' && peekNext() == '*') { take(); take(); depth++; }
                    else if (peek() == '*' && peekNext() == '/') { take(); take(); depth--; }
                    else {
                        int commentRow = line, commentCol = column;
                        char ch = take();
                        if (ch == '\r') {
                            if (peek() == '\n') take();
                            else { line++; column = 1; }
                        }
                        if (ch == '\r' || ch == '\n')
                            tokens.add(new Token(Token.Kind.NEWLINE, "\n", commentRow, commentCol));
                    }
                }
                if (depth != 0) throw error(row, col, "Unclosed block comment; add '*/'.");
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                while (Character.isLetterOrDigit(peek()) || peek() == '_') take();
                tokens.add(new Token(Token.Kind.NAME, source.substring(start, pos), row, col));
            } else if (digit(c)) {
                while (digit(peek())) take();
                if (peek() == '.' && digit(peekNext())) {
                    take();
                    while (digit(peek())) take();
                }
                tokens.add(new Token(Token.Kind.NUMBER, source.substring(start, pos), row, col));
            } else if (c == '"') {
                StringBuilder value = new StringBuilder();
                while (!end() && peek() != '"') {
                    char ch = take();
                    if (ch == '\n' || ch == '\r') throw error(row, col, "Strings cannot cross lines; use \\n.");
                    if (ch == '\\') {
                        if (end()) throw error(row, col, "Unclosed string.");
                        ch = switch (take()) {
                            case 'n' -> '\n'; case 'r' -> '\r'; case 't' -> '\t';
                            case '"' -> '"'; case '\\' -> '\\';
                            default -> throw error(line, column - 1, "Unknown escape; use \\n, \\r, \\t, \\\" or \\\\.");
                        };
                    }
                    value.append(ch);
                }
                if (end()) throw error(row, col, "Unclosed string; add a closing quote.");
                take();
                tokens.add(new Token(Token.Kind.STRING, value.toString(), row, col));
            } else {
                String symbol = String.valueOf(c);
                String pair = symbol + peek();
                if (List.of("==", "!=", "<=", ">=", "&&", "||", "->", "..").contains(pair)) {
                    take(); symbol = pair;
                } else if ("(){}[]:;,+-*/%!=<>.".indexOf(c) < 0) {
                    throw error(row, col, "Unexpected character '" + c + "'.");
                }
                tokens.add(new Token(Token.Kind.SYMBOL, symbol, row, col));
            }
        }
        tokens.add(new Token(Token.Kind.EOF, "<end>", line, column));
        return tokens.stream().map(t -> new Token(t.kind(), t.text(), t.line(), t.column(), file)).toList();
    }

    private boolean end() { return pos >= source.length(); }
    private char peek() { return end() ? '\0' : source.charAt(pos); }
    private char peekNext() { return pos + 1 >= source.length() ? '\0' : source.charAt(pos + 1); }
    private char take() {
        char c = source.charAt(pos++);
        if (c == '\n') { line++; column = 1; } else column++;
        return c;
    }
    private static boolean digit(char c) { return c >= '0' && c <= '9'; }
    private FalconError error(int row, int col, String message) {
        return new FalconError(new Token(Token.Kind.SYMBOL, "", row, col, file), message);
    }
}
