package falcon;

import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Integration checks for the long-lived JVM command cache. */
final class JvmCacheTest {
    private JvmCacheTest() {}

    public static void main(String[] args) throws Exception {
        run();
        System.out.println("Passed 8 JVM cache checks.");
    }

    static int run() throws Exception {
        Path directory = Files.createTempDirectory("falcon-jvm-cache-");
        Path main = directory.resolve("main.fc");
        Path imported = directory.resolve("helper.fc");
        try (Session session = new Session()) {
            Files.writeString(main, "use helper\nfunc main() { say(helper.message()) }\n");
            Files.writeString(imported, "func message() -> string { give \"old module\" }\n");
            session.command("run " + main);
            session.await("old module\n", 1);

            Files.writeString(imported, "func message() -> string { give \"edited module\" }\n");
            session.command("run " + main);
            session.await("edited module\n", 1);

            Files.writeString(main, "func main() { var i = 0\nwhile (i < 3) { i = i + 1 }\nsay(i) }\n");
            session.command("run " + main + " --max-steps 1");
            session.await("step limit", 1);
            session.command("run " + main + " --max-steps 100");
            session.await("3\nEnded in", 1);

            Files.writeString(main, "use os\nfunc main() { say(os.args()) }\n");
            session.command("run " + main + " -- first-arg");
            session.await("[first-arg]", 1);
            session.command("run " + main + " -- second-arg");
            session.await("[second-arg]", 1);

            Files.writeString(main, "func main() { say(missingName) }\n");
            session.command("run " + main);
            session.await("Unknown variable 'missingName'", 1);
            session.command("run " + main);
            session.await("Unknown variable 'missingName'", 2);
            Files.writeString(main, "func main() { say(\"recovered\") }\n");
            session.command("run " + main);
            session.await("recovered\n", 1);
            session.closeInput();
            if (!session.finished.await(5, TimeUnit.SECONDS)) throw new AssertionError("JVM console did not exit after EOF");
            if (session.exitCode.get() != 0) throw new AssertionError("JVM console exited with " + session.exitCode.get());
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
        return 8;
    }

    private static final class Session implements AutoCloseable {
        private final PipedInputStream input;
        private final PipedOutputStream commands;
        private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        private final PrintStream output = new PrintStream(captured, true, StandardCharsets.UTF_8);
        private final CountDownLatch finished = new CountDownLatch(1);
        private final AtomicInteger exitCode = new AtomicInteger(-1);
        private final Thread worker;

        Session() throws Exception {
            input = new PipedInputStream(8192);
            commands = new PipedOutputStream(input);
            worker = new Thread(() -> {
                try { exitCode.set(Main.cli(new String[]{"jvm"}, input, output, output)); }
                finally { finished.countDown(); }
            }, "falcon-jvm-cache-test");
            worker.setDaemon(true);
            worker.start();
        }

        void command(String command) throws Exception {
            commands.write((command + "\n").getBytes(StandardCharsets.UTF_8));
            commands.flush();
        }

        void await(String text, int occurrences) throws Exception {
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline) {
                synchronized (captured) {
                    String output = captured.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
                    if (count(output, text) >= occurrences) return;
                    if (finished.getCount() == 0) throw new AssertionError("JVM ended before output '" + text + "': " + output);
                }
                Thread.sleep(10);
            }
            throw new AssertionError("Timed out waiting for '" + text + "': " + captured.toString(StandardCharsets.UTF_8));
        }

        private static int count(String text, String part) {
            int count = 0, offset = 0;
            while ((offset = text.indexOf(part, offset)) >= 0) { count++; offset += part.length(); }
            return count;
        }

        void closeInput() throws Exception { commands.close(); }
        @Override public void close() throws Exception {
            try { commands.close(); } catch (Exception ignored) {}
            worker.join(5_000);
            output.close();
        }
    }
}
