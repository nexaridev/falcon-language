package falcon;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.BorderFactory;
import javax.swing.WindowConstants;

/** Swing UI operations run on the EDT; Falcon consumes events on its own thread. */
final class GuiRuntime implements AutoCloseable {
    private static final class WindowState {
        final JFrame frame;
        final JPanel panel;
        final LinkedBlockingQueue<String> events = new LinkedBlockingQueue<>(1024);
        volatile boolean open = true;
        WindowState(JFrame frame, JPanel panel) { this.frame = frame; this.panel = panel; }
    }
    private record Control(WindowState owner, JComponent component) {}
    private final Map<Long, WindowState> windows = new HashMap<>();
    private final Map<Long, Control> controls = new HashMap<>();
    private long next = 1;

    Object invoke(String operation, List<Object> args) throws InterruptedException {
        if (GraphicsEnvironment.isHeadless()) throw new IllegalArgumentException("GUI requires a desktop display; this Java environment is headless.");
        if (operation.equals("gui.nextEvent")) {
            if (SwingUtilities.isEventDispatchThread()) throw new IllegalArgumentException("Cannot wait for GUI events on the UI thread.");
            WindowState state = onEdt(() -> window((long) args.get(0)));
            if (!state.open && state.events.isEmpty()) return "close";
            return state.events.take();
        }
        return onEdt(() -> switch (operation) {
            case "gui.window" -> {
                long width = (long) args.get(1), height = (long) args.get(2);
                if (width < 100 || width > 4096 || height < 100 || height > 4096)
                    throw new IllegalArgumentException("Window width and height must be 100–4096 pixels.");
                JFrame frame = new JFrame((String) args.get(0));
                frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
                JPanel panel = new JPanel();
                panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
                panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
                frame.add(new JScrollPane(panel), BorderLayout.CENTER);
                frame.setSize((int) width, (int) height);
                frame.setLocationRelativeTo(null);
                WindowState state = new WindowState(frame, panel);
                frame.addWindowListener(new WindowAdapter() {
                    @Override public void windowClosing(WindowEvent event) { dispose(state); }
                    @Override public void windowClosed(WindowEvent event) { dispose(state); }
                });
                long id = next++; windows.put(id, state); yield id;
            }
            case "gui.label", "gui.input", "gui.button" -> {
                WindowState state = liveWindow((long) args.get(0));
                String text = (String) args.get(1);
                JComponent control;
                if (operation.equals("gui.label")) control = new JLabel(text);
                else if (operation.equals("gui.input")) {
                    JTextField field = new JTextField(text, 24);
                    field.setMaximumSize(new Dimension(Integer.MAX_VALUE, field.getPreferredSize().height));
                    control = field;
                } else {
                    String event = (String) args.get(2);
                    if (event.isBlank() || event.equals("close")) throw new IllegalArgumentException("Button event must be nonempty and cannot be 'close'.");
                    JButton button = new JButton(text);
                    button.addActionListener(ignored -> { if (state.open) state.events.offer(event); });
                    control = button;
                }
                control.setAlignmentX(JComponent.LEFT_ALIGNMENT);
                state.panel.add(control); state.panel.revalidate(); state.panel.repaint();
                long id = next++; controls.put(id, new Control(state, control)); yield id;
            }
            case "gui.text" -> {
                JComponent component = control((long) args.get(0));
                if (component instanceof JTextField field) yield field.getText();
                if (component instanceof JLabel label) yield label.getText();
                yield ((JButton) component).getText();
            }
            case "gui.setText" -> {
                JComponent component = control((long) args.get(0)); String value = (String) args.get(1);
                if (component instanceof JTextField field) field.setText(value);
                else if (component instanceof JLabel label) label.setText(value);
                else ((JButton) component).setText(value);
                yield null;
            }
            case "gui.setEnabled" -> { control((long) args.get(0)).setEnabled((boolean) args.get(1)); yield null; }
            case "gui.show" -> { liveWindow((long) args.get(0)).frame.setVisible(true); yield null; }
            case "gui.isOpen" -> window((long) args.get(0)).open;
            case "gui.close" -> { dispose(window((long) args.get(0))); yield null; }
            case "gui.message" -> { JOptionPane.showMessageDialog(null, args.get(1), (String) args.get(0), JOptionPane.INFORMATION_MESSAGE); yield null; }
            case "gui.confirm" -> JOptionPane.showConfirmDialog(null, args.get(1), (String) args.get(0), JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION;
            default -> throw new IllegalArgumentException("Unknown GUI operation " + operation);
        });
    }
    private WindowState window(long id) {
        WindowState state = windows.get(id);
        if (state == null) throw new IllegalArgumentException("Unknown window handle " + id + ".");
        return state;
    }
    private WindowState liveWindow(long id) {
        WindowState state = window(id);
        if (!state.open) throw new IllegalArgumentException("Window " + id + " is closed.");
        return state;
    }
    private JComponent control(long id) {
        Control control = controls.get(id);
        if (control == null) throw new IllegalArgumentException("Unknown control handle " + id + ".");
        if (!control.owner.open) throw new IllegalArgumentException("The control's window is closed.");
        return control.component;
    }
    private void dispose(WindowState state) {
        if (!state.open) return;
        state.open = false;
        state.events.clear(); state.events.offer("close");
        state.frame.dispose();
    }
    private <T> T onEdt(Callable<T> action) throws InterruptedException {
        if (SwingUtilities.isEventDispatchThread()) {
            try { return action.call(); } catch (RuntimeException e) { throw e; }
            catch (Exception e) { throw new IllegalArgumentException(e.getMessage(), e); }
        }
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                try { result.set(action.call()); } catch (Exception e) { failure.set(e); }
            });
        } catch (InvocationTargetException e) { throw new IllegalArgumentException("Cannot update GUI: " + e.getCause(), e); }
        if (failure.get() instanceof RuntimeException runtime) throw runtime;
        if (failure.get() != null) throw new IllegalArgumentException(failure.get().getMessage(), failure.get());
        return result.get();
    }
    @Override public void close() {
        // Queue cleanup even if the interpreter was interrupted while waiting for an event.
        Runnable cleanup = () -> { windows.values().forEach(this::dispose); controls.clear(); windows.clear(); };
        if (SwingUtilities.isEventDispatchThread()) cleanup.run();
        else {
            boolean interrupted = Thread.interrupted();
            try { onEdt(() -> { cleanup.run(); return null; }); }
            catch (InterruptedException e) { SwingUtilities.invokeLater(cleanup); interrupted = true; }
            finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
    }
}
