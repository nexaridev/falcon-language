# Falcon for VS Code

Licensed under the Apache License, Version 2.0. See the repository's
[`LICENSE`](https://github.com/nexaridev/falcon-language/blob/main/LICENSE).

Falcon support for `.fc` files:

- Theme-aware highlighting, comments, matching brackets, and snippets.
- Live compiler errors with red squiggles and Problems entries.
- Checks on open, after typing pauses, on save, and when workspace modules change.
- Keyword, local function, variable, module alias, and `module.` member suggestions.
- Signatures and completions for all twelve standard modules, including `number`,
  `any`, `list`, and `map` types and square-bracket editing support.
- Tab accepts the selected suggestion; functions have parameter placeholders.
- Snippets: `main`, `func`, `each`, `when`, `use`, and `usepath`.
- **Falcon: Check Current File** in the Command Palette.

## Install

The packaged VSIX is attached to each Falcon release at
https://github.com/nexaridev/falcon-language/releases. Install it with:

```sh
code --install-extension falcon-language-0.12.0.vsix
```

To package from a checkout, use the steps below.

From the Falcon repository root, build, package, and install into normal VS Code:

```powershell
.\editors\vscode\package.ps1 -Install
```

Packaging uses npm's `@vscode/vsce` tool and requires Node/npm and a JDK 17+.
Using the installed extension only requires a **Java 17+ runtime**. Its bundled
Falcon compiler JAR does not depend on a shell PATH refresh or a development
checkout. Checks never execute Falcon programs. Reload an existing VS Code
window after installation if it has not picked up the extension.

## Editing

Save a document with the `.fc` extension; subsequent unsaved edits are checked
through compiler stdin without changing the file on disk. Module files do not
require `main`. The compiler currently reports one error per check. Imported
dependencies are checked from saved contents: save a module after changing its
functions to update checks in its callers. Runtime failures such as division by
zero are still reported by `falcon run`.

Type `math.` after `use math` to see functions from `math.fc`. Local variable
suggestions use declarations earlier in the file, not a full semantic scope model.
The compiler remains authoritative about scope. Use Ctrl+Space to open suggestions
manually and Tab to accept the highlighted item. Tab moves between placeholders
after inserting a function or snippet. Ordinary indentation still works when no
suggestion or snippet is active.

Falcon-only defaults enable Tab completion, quick suggestions, snippets first,
and four-space indentation. Other languages keep their settings. Explicit user
settings and keybindings can override extension defaults.

## Settings

- `falcon.javaPath`: explicit Java executable if automatic discovery fails.
- `falcon.compilerPath`: optional JAR override; otherwise use the bundled compiler.
- `falcon.stdlibPath`: editable standard-library folder for checks and completions;
  otherwise use the bundled Falcon `.fc` sources.
- `falcon.diagnostics.enabled`: enable/disable checks.

Open **Output → Falcon** for compiler launch failures. Checking requires a trusted
workspace. Untitled files must be saved before module paths can resolve.

## Development

```powershell
node --test editors/vscode/test/language.test.js
code --extensionDevelopmentPath="$PWD\editors\vscode" "$PWD"
```

Package first to refresh `runtime/falcon.jar` after Java changes. The extension
has no npm runtime dependencies. `test/integration.js` runs inside a VS Code test
host to verify activation, error positions, unsaved edits, module completions,
library checking, suggestion acceptance, and Falcon-specific Tab defaults.
