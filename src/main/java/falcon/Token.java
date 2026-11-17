package falcon;

record Token(Kind kind, String text, int line, int column, String file) {
    Token(Kind kind, String text, int line, int column) { this(kind, text, line, column, ""); }
    enum Kind { NAME, NUMBER, STRING, SYMBOL, NEWLINE, EOF }
}
