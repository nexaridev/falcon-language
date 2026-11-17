# Desktop windows in Falcon

`use gui` loads Falcon's included desktop toolkit. It uses Java Swing, with no extra package to install. It is a small toolkit inspired by the convenience of Tkinter, not a binding to Python or Tk.

Run the greeting example from the Falcon folder:

```powershell
falcon run examples/gui.fc
```

```fc
use "std/gui.fc" as gui

func main() {
    fix window = gui.window("Hello", 420, 240)
    fix name = gui.input(window, "")
    gui.button(window, "Greet", "greet")
    fix label = gui.label(window, "")
    gui.show(window)
    while (gui.isOpen(window)) {
        fix event = gui.nextEvent(window)
        if (event == "greet") {
            gui.setText(label, "Hello, " + gui.text(name))
        }
    }
}
```

Widgets appear vertically in creation order. Window and control handles have type `int`. Keep the event loop in `main`: returning from `main`, or a runtime error, closes all windows.

| Function | Behavior |
| --- | --- |
| `window(title: string, width: int, height: int) -> int` | Create a hidden window; dimensions must be 100–4096 pixels. |
| `label(window: int, value: string) -> int` | Add a text label. |
| `input(window: int, value: string) -> int` | Add a single-line editable input. |
| `button(window: int, caption: string, event: string) -> int` | Add a button whose clicks produce the supplied event string. |
| `text(control: int) -> string` | Read an input, label, or button's text. |
| `setText(control: int, value: string)` | Change text. |
| `setEnabled(control: int, enabled: bool)` | Enable or disable a widget. |
| `show(window: int)` | Display the window. |
| `isOpen(window: int) -> bool` | Check whether the window has been closed. |
| `nextEvent(window: int) -> string` | Wait for a button click or window closure. |
| `close(window: int)` | Close a window. Repeated closes are harmless. |
| `message(title: string, message: string)` | Show a message dialog and wait until dismissed. |
| `confirm(title: string, message: string) -> bool` | Show a yes/no dialog; only Yes returns true. |

Closing a window produces `"close"`; subsequent `nextEvent` calls on that window also return `"close"`. Button event names must be nonblank and cannot be `"close"`. The event queue holds up to 1,024 pending clicks; extra clicks are dropped. Closing clears pending clicks. Using controls after their window closes produces a runtime error.

The UI runs on Java's event thread while Falcon waits for events. This first version provides a vertical layout and string events; callbacks, custom layouts, menus, and image widgets are future additions. A desktop display is required. The regular Falcon execution-step limit still applies to long-running event loops; increase it with `--max-steps` when needed.

Public wrappers are editable in `stdlib/gui.fc`; Java widget operations are implemented in `src/main/java/falcon/GuiRuntime.java`.
