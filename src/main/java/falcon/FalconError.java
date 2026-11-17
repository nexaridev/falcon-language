package falcon;

final class FalconError extends RuntimeException {
    final Token token;

    FalconError(Token token, String message) {
        super(message);
        this.token = token;
    }

    String diagnostic(String file, String source) {
        String[] lines = source.split("\\R", -1);
        String line = token.line() <= lines.length ? lines[token.line() - 1] : "";
        return file + ":" + token.line() + ":" + token.column() + ": error: " + getMessage()
                + System.lineSeparator() + "  " + line.replace('\t', ' ')
                + System.lineSeparator() + "  " + " ".repeat(Math.max(0, token.column() - 1)) + "^";
    }
}
