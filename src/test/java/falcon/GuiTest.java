package falcon;

import java.awt.*;
import java.util.List;
import javax.swing.*;

final class GuiTest {
    private static int passed;
    static int run() throws Exception {
        var file = java.nio.file.Files.createTempFile("falcon-gui-", ".fc");
        try {
            java.nio.file.Files.writeString(file, "use \"std/gui.fc\" as gui\nfunc main() { fix win = gui.window(\"Wrapper test\", 300, 200)\nfix input = gui.input(win, \"Ada\")\nsay(gui.text(input))\ngui.close(win) }");
            var bytes = new java.io.ByteArrayOutputStream();
            var stream = new java.io.PrintStream(bytes);
            check(Main.cli(new String[]{"check", file.toString()}, stream, stream) == 0);
            if (!GraphicsEnvironment.isHeadless()) {
                bytes.reset();
                check(Main.cli(new String[]{"run", file.toString()}, stream, stream) == 0);
                check(bytes.toString().trim().equals("Ada"));
            }
        } finally { java.nio.file.Files.deleteIfExists(file); }
        if (GraphicsEnvironment.isHeadless()) {
            try (GuiRuntime gui = new GuiRuntime()) {
                try { gui.invoke("gui.window", List.of("Test", 300L, 200L)); throw new AssertionError("headless"); }
                catch (IllegalArgumentException e) { check(e.getMessage().contains("headless")); }
            }
            return passed;
        }
        try (GuiRuntime gui = new GuiRuntime()) {
            long win = (long) gui.invoke("gui.window", List.of("Falcon GUI test", 300L, 200L));
            long input = (long) gui.invoke("gui.input", List.of(win, "Ada"));
            long label = (long) gui.invoke("gui.label", List.of(win, "Hello"));
            gui.invoke("gui.button", List.of(win, "Go", "greet"));
            check(gui.invoke("gui.text", List.of(input)).equals("Ada"));
            gui.invoke("gui.setText", List.of(label, "Hello Ada"));
            check(gui.invoke("gui.text", List.of(label)).equals("Hello Ada"));
            SwingUtilities.invokeAndWait(() -> {
                for (Window window : Window.getWindows())
                    if (window instanceof JFrame frame && frame.getTitle().equals("Falcon GUI test")) click(frame);
            });
            // Interrupt a broken event delivery instead of leaving the test suite blocked.
            var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
            try { check(executor.submit(() -> gui.invoke("gui.nextEvent", List.of(win))).get(3, java.util.concurrent.TimeUnit.SECONDS).equals("greet")); }
            finally { executor.shutdownNow(); }
            gui.invoke("gui.close", List.of(win));
            check(gui.invoke("gui.isOpen", List.of(win)).equals(false));
            check(gui.invoke("gui.nextEvent", List.of(win)).equals("close"));
            try { gui.invoke("gui.text", List.of(input)); throw new AssertionError("closed control"); }
            catch (IllegalArgumentException e) { check(e.getMessage().contains("closed")); }
            try { gui.invoke("gui.window", List.of("Bad", 0L, 200L)); throw new AssertionError("dimensions"); }
            catch (IllegalArgumentException e) { check(e.getMessage().contains("pixels")); }
        }
        return passed;
    }
    private static void click(Container parent) {
        for (Component child : parent.getComponents()) {
            if (child instanceof JButton button && button.getText().equals("Go")) button.doClick();
            else if (child instanceof Container container) click(container);
        }
    }
    private static void check(boolean value) { if (!value) throw new AssertionError("GUI test"); passed++; }
}
