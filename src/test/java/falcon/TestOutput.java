package falcon;

final class TestOutput {
    private TestOutput() {}

    static String withoutDuration(String output) {
        return output.replaceAll("(?m)^Ended in [^\\r\\n]+\\R?", "").replace("\r\n", "\n");
    }
}
