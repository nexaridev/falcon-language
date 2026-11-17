# Optional Falcon libraries

These are ordinary, opt-in `.fc` modules, **outside `stdlib`**. They require Falcon 0.6 or newer because their native transport and image primitives are implemented in its Java runtime. They are not Python packages and do not require Python, pip, or a separate JAR.

- [telebot](../docs/telebot.md): Telegram bots, polling, messages, replies, uploads, inline keyboards, and logs.
- [pillow](../docs/pillow.md): creating, opening, editing, drawing, and saving images.

Copy the desired `.fc` file into your project's `lib` folder, then import it:

```fc
use "lib/telebot.fc" as telebot
use "lib/pillow.fc" as image
```

For example, from your project folder in PowerShell:

```powershell
New-Item -ItemType Directory -Force lib
Copy-Item 'C:\Users\ASUS\OneDrive\Desktop\Falcon (.fc)\libraries\telebot.fc' lib
Copy-Item 'C:\Users\ASUS\OneDrive\Desktop\Falcon (.fc)\libraries\pillow.fc' lib
```

Alternatively, use a relative import to these files, as the bundled examples do. Neither `use telebot` nor `use pillow` searches this folder automatically. No package manager or registry has been added. VS Code discovers functions from the imported `.fc` file.
