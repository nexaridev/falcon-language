# Falcon (.fc)

Falcon is a small, statically checked programming language implemented in Java.
Version 0.4 is a working reference interpreter with a separate lexer, parser,
abstract syntax tree, semantic checker, and execution backend.

```fc
func main() {
    fix name = "Falcon"
    say("Hello from " + name)

    each (i in 0..5) {
        say(square(i))
    }
}

func square(n: int) -> int {
    give n * n
}
```

## Run on Windows

Requires **JDK 17 or newer**. No Maven, Gradle, or external Java dependencies.
The scripts discover a JDK through `JAVA_HOME`, `javac` on `PATH`, or your
`%USERPROFILE%\.jdks` directory. They do not change system settings.

```powershell
.\falcon.ps1 run examples/hello.fc
.\falcon.ps1 run examples/functions.fc
.\falcon.ps1 check examples/newlines.fc
.\falcon.ps1 tokens examples/hello.fc
.\build.ps1 -Test
```

The launcher builds if the JAR is missing. After editing the Java implementation,
run `.\build.ps1` to rebuild. Editing `.fc` programs does not require a rebuild.
After a build, run the distributable JAR directly using a Java 17+ runtime:

```sh
java -jar build/falcon.jar run examples/hello.fc
java -jar build/falcon.jar --help
```

## Install the `falcon` command on Windows

From this repository, run once:

```powershell
.\install.ps1
```

This builds Falcon and adds this workspace's `bin` folder to your **user PATH**.
It preserves existing entries and does not add the same folder twice. No admin
rights or machine-wide PATH changes are needed. Restart existing terminals;
restart VS Code too if you use its integrated terminal. The shell that directly
runs the installer is updated immediately.

Then run from any directory:

```powershell
falcon --version
falcon run "C:\path\to\project\main.fc"
falcon check "C:\path\to\project\main.fc"
```

When inside this repository, try `falcon run examples/modules/main.fc`.
Relative CLI paths are resolved from your current directory. The installed command
continues to use this checkout, so keep it in place. The Windows shim uses
Windows PowerShell with a process-local execution-policy setting to launch the
local script; it does not change your persistent execution policy.

On Linux/macOS with a JDK on `PATH`, build and test with:

```sh
mkdir -p build/classes build/test-classes
javac --release 17 -encoding UTF-8 -d build/classes src/main/java/falcon/*.java
cp -R stdlib build/classes/
jar --create --file build/falcon.jar --main-class falcon.Main -C build/classes .
javac --release 17 -encoding UTF-8 -cp build/classes -d build/test-classes src/test/java/falcon/*.java
java -cp build/classes:build/test-classes falcon.FalconTest
```

## Language basics

| Syntax | Meaning |
| --- | --- |
| `func name(a: int) -> int { ... }` | Declare a typed function |
| `var score = 10` | Mutable binding with inferred type |
| `var score: float = 10` | Explicit type; integer widens to float |
| `var score: number = 10` | Numeric binding that accepts either int or float |
| `fix limit = 5` | Binding that cannot be reassigned |
| `give value` | Return a value |
| `give` | Return from a void function |
| `if (condition) { ... } else { ... }` | Conditional branches |
| `elseif (condition) { ... }` | Additional conditional branch |
| `while (condition) { ... }` | Repeat while true |
| `each (i in 0..5) { ... }` | Iterate over 0, 1, 2, 3, 4 |
| `say(value, ...)` | Print values separated by spaces and end with a newline |

`func main()` is required and must take no parameters and return no value.
Parameters need explicit types. Omit `-> type` for a void function. Functions
can call later declarations and themselves. Nested function declarations are
not supported. Every path through a non-void function must explicitly return;
the current checker conservatively treats loops as potentially executing zero times.

Types are `int` (signed 64-bit), `float` (64-bit floating point), `number` (either
numeric representation), `bool`, `string`, `list`, `map`, `any`, and `void` for
function returns. Range values can be inferred (`fix r = 0..5`)
and used in `each`; they have no explicit type keyword yet. Variables retain
their inferred or annotated type. Conversion from `int` to `float` is allowed;
conversion back is not implicit. Mixed numeric operations use floating point,
which may lose precision for very large integers.

### One numeric type when you want it

`number` accepts `int` and `float` without manual conversion or type branching.
It preserves integer values exactly while they remain integers. It still rejects
strings, booleans, containers, and null.

```fc
use math

func double(value: number) -> number {
    give value * 2
}

func main() {
    var value: number = 5
    value = 2.5
    say(double(value))       // 5.0
    say(math.square(value))  // 6.25
}
```

`var x = 1` still infers `int`; use `var x: number = 1` when it should also accept
decimals later. A `number` parameter or return can hold either representation.
Converting `number` to `float` is automatic; converting it to `int` requires
`math.toInt`, which truncates toward zero and checks the 64-bit range.
Arithmetic on two runtime integers stays integer arithmetic, including division:
`7 / 2` is `3`, while `7 / 2.0` is `3.5`, also inside `number` variables.

### Lists, maps, and JSON values

```fc
use collections as c

func main() {
    var scores = [10, 2.5, 7]
    c.push(scores, 12)
    say(scores[0], c.sortNumbers(scores))
    each (score in scores) {
        say(c.asNumber(score) * 2)
    }

    var profile = c.newMap()
    c.put(profile, "name", "Falcon")
    say(profile["name"])
}
```

Lists and maps hold heterogeneous `any` values, including `null` and nested
containers. `any` is distinct from `number`: extract typed values with
`c.asNumber`, `c.asInt`, `c.asString`, `c.asList`, etc. These validate at runtime;
use `text.toNumber` to parse a string. Indexing accepts integer list/string indices
or string map keys. Bounds and missing keys are diagnosed. Use `c.set`/`c.put` to
change elements; index assignment syntax is not implemented.

Containers have reference semantics. `fix` prevents rebinding but does not freeze
their contents. `each` iterates over a shallow snapshot of a list and gives each
element type `any`. Equality compares container contents, with numeric int/float
comparison. Strings index by Unicode code point. Lists allow multiline elements
and a trailing comma.

Operators, from highest to lowest precedence:

1. Calls and indexing `(...)`, `[...]`
2. Unary `!`, `-`, `+`
3. `*`, `/`, `%`
4. `+`, `-`
5. Range `..`
6. `<`, `<=`, `>`, `>=`
7. `==`, `!=`
8. `&&`
9. `||`

Binary operators associate left to right. Integer division truncates toward zero.
Integer overflow, division by zero, and non-finite floating results produce
runtime diagnostics. Decimal literals use forms such as `12` or `12.5`; exponent,
hexadecimal, and underscore notation are not implemented. Integer literals must
fit the nonnegative signed 64-bit range before unary negation is applied.
`&&` and `||` short-circuit. Conditions require booleans. Strings support
`\n`, `\r`, `\t`, `\"`, and `\\`; `+` concatenates strings with scalar values.

Range ends are excluded. A range whose end is at or below its start is empty.
Ranges are lazy, so they do not allocate an array. Each iteration gets a fresh,
immutable loop binding. Ordinary parameters are mutable. Blocks create lexical
scopes; inner blocks may shadow outer variables. Variables cannot shadow function
names. Comments use `//` or nested `/* ... */`.

## Optional semicolons

A newline ends a simple statement. Use `;` to put multiple simple statements on
one line. A closing brace also ends the final statement in a block.

```fc
var first = 10; var second = 20
fix total = first +
    second
say(
    "Total:",
    total
)
```

Newlines inside parentheses or list brackets are ignored. A line also continues after an operator,
including the `=` in a declaration or assignment. Put an operator at the end of
the line when continuing without parentheses. Opening braces may go on a new
line, and `else` may follow a newline. Structured statements ending in `}` do not
need an extra separator.

`give` immediately followed by a newline returns **without a value**. For a
multiline return value, start parentheses on the same line:

```fc
give (
    first + second
)
```

## Modules

Each `.fc` file is a module. Named imports first look for a sibling file, then
fall back to the installed standard library. All thirteen modules are available:
`io`, `math`, `text`, `collections`, `fs`, `path`, `random`, `time`, `json`, `os`,
`test`, `http`, and `gui`. No downloads are needed. See the
[standard-library API](docs/standard-library.md) for signatures and behavior.
For example:

```fc
// main.fc
use math

func main() {
    say(math.square(5))
}
```

```fc
// math.fc
func square(n: int) -> int {
    give n * n
}
```

Use an explicit relative path and alias for other locations:

```fc
use "lib/math.fc" as math
use "../shared/text.fc" as text
use math as numbers
```

These lines show independent import forms; aliases must be unique within a file.
`use math as numbers` loads local or bundled `math.fc` as `numbers`. Quoted paths
must include `.fc` and an `as` alias. Use forward slashes in quoted paths to avoid
backslash string escapes. Imports are top-level declarations with the same
newline/semicolon rules as simple statements.

Use `use "std/math.fc" as math` to explicitly select bundled math even if a local
`math.fc` exists. Quoted paths such as `use "math.fc" as math` always refer to local
files, except the reserved `std/` namespace.

Bundled math uses `number` for general numeric inputs. `square`, `abs`, `min`,
`max`, and `clamp` return `number`; square roots, powers, floor, ceil, and pi return
`float`. `factorial` takes an `int` from 0 through 20 and returns `int`. `round`
and `toInt` also return `int`. Invalid domains and overflow produce diagnostics.

Standard-library APIs are **Falcon source files** in `stdlib/`. Factorial,
arithmetic helpers, list reverse/sort, assertions, and input conversions contain
Falcon logic. Host operations (I/O, storage, HTTP, JSON, and numerical primitives)
use typed Java runtime bridges imported as `native/<module>.fc`; these are
internal APIs. There are no empty module placeholders.

The installed `falcon` launcher reads the checkout's `stdlib` folder each run.
Save edits to `stdlib/math.fc` and rerun your program—no Java rebuild is needed.
VS Code's `falcon.stdlibPath` setting can point to that same folder for checking
and completions. The extension otherwise uses its bundled `.fc` sources.
Standard modules can import other standard modules, with cycle detection. Add
your own `stdlib/name.fc` and it is available through `use name` or
`use "std/name.fc" as name` without changing Java. Standard-module imports resolve
within the standard-library folder rather than the application's folder.

`build.ps1` also includes these sources inside the JAR for standalone distribution.
Rebuild to update that embedded copy; `.\editors\vscode\package.ps1 -Install`
refreshes the extension's distributed copy and autocomplete from the same source.
Direct JAR users can select editable sources with
`java -Dfalcon.stdlib="path/to/stdlib" -jar build/falcon.jar run main.fc`.

Paths resolve relative to the importing file, regardless of the shell's current
directory. Imported functions are called as `alias.function(...)`. All functions
are public for now. Each module has its own function namespace; an unqualified
call resolves within that file or to the built-in `say`. Imports do not leak or
implicitly re-export other modules' functions. Module aliases cannot be reused
as function names, variables, parameters, or loop bindings in that module.

Modules can import other modules. Files are canonicalized and loaded once even
when reached through multiple paths. Circular imports are rejected, and import
nesting is limited to 128 files. The entire imported graph is checked before
execution. Errors include the actual module file and source location.

Only the entry file needs `func main()`. A library's `main` is an ordinary function
and is not automatically executed. Modules currently contain imports and functions;
there are no top-level variables, initialization code, selective imports, private
exports, package downloads, or registry resolution.

## Diagnostics and execution limits

All functions are checked before any program output, even unused functions.
Errors include the source file, line, column, source line, and a caret:

```text
example.fc:3:5: error: Cannot reassign 'limit'; it was declared with 'fix'.
      limit = 6
      ^
```

Commands exit with `0` on success, `1` for a language/runtime error, and `2` for
invalid CLI arguments or file read errors. The checker currently reports the
first error. `check` never executes the program. `tokens` only runs the lexer.

Execution defaults to a million interpreter steps and a maximum of 256 nested
function calls. A step is an interpreter operation, not a source line. Increase
the step budget for larger programs:

```powershell
.\falcon.ps1 run examples/functions.fc --max-steps 10000000
```

These limits help catch accidental infinite loops; this interpreter is not a
security sandbox for untrusted programs.

Pass program arguments after `--` and read them with `os.args()`:

```powershell
falcon run examples/stdlib.fc -- hello "two words"
falcon run examples/interactive.fc
```

If invoking the PowerShell script directly instead of `falcon`, quote the
delimiter: `.\falcon.ps1 run examples/stdlib.fc '--' hello`.

Native calls have their own costs; the interpreter step budget does not bound
time spent waiting on console input, sleeping, network requests, or large host
operations. Standard-library failures currently stop execution; catchable errors
are not yet part of the language.

## VS Code

The local extension in [editors/vscode](editors/vscode/README.md) includes syntax
highlighting, live compiler error highlighting, function and module completions,
Tab selection, comments, brackets, and snippets. Install it into normal VS Code:

```powershell
.\editors\vscode\package.ps1 -Install
```

Reload an already-open VS Code window after installation if needed. Checks support
unsaved edits to saved `.fc` files and do not require `main` in library files.
Imported dependencies use saved contents. The compiler reports the first error
in each check. To open an extension development window instead:

```powershell
code --extensionDevelopmentPath="$PWD\editors\vscode" "$PWD"
```

Editor integration uses `falcon check file.fc --stdin --library`: `--stdin`
reads replacement source for an existing entry file without writing it, and
`--library` allows the entry file to omit `main`. These flags apply only to `check`.

## Implementation

```text
source -> Lexer -> tokens -> Parser -> AST -> Checker -> Interpreter
```

| File under `src/main/java/falcon` | Responsibility |
| --- | --- |
| `Token.java`, `Lexer.java` | Source positions, literals, comments, operators, newlines |
| `Ast.java`, `Parser.java` | Syntax tree, precedence, statement boundaries |
| `ModuleLoader.java` | Relative file imports, module graph, call linking, source diagnostics |
| `Checker.java` | Scopes, types, signatures, return paths, immutable bindings |
| `Interpreter.java` | Lexical environments, function calls, lazy ranges, runtime checks |
| `StandardLibrary.java`, `NativeRuntime.java` | Native signatures, host operations, per-program I/O and random state |
| `Json.java`, `Values.java` | JSON encoding/decoding, values, indexing and equality |
| `FalconError.java`, `Main.java` | Diagnostics and CLI |

The dependency-free test suite covers parsing, execution, type failures, scope,
newlines, runtime limits, numeric behavior, source locations, CLI exit codes,
and module resolution, isolation, aliases, cycles, and imported-file diagnostics.

## Scope of version 0.5

This backend walks the syntax tree. It does **not** generate JVM bytecode for
Falcon programs, and no speed advantage over Python is claimed. The JAR packages
the Java implementation, not a compiled Falcon program.

The first usable APIs for all thirteen standard modules are implemented. Next
milestones include catchable errors, richer container typing, JVM bytecode
generation, a language server, and package management. Classes, tooling plugins,
package downloads, and an online registry are not implemented yet.

## Desktop GUI

Falcon includes a small Java Swing toolkit: `use gui`. Run `falcon run examples/gui.fc` for a window with a text input and button. See [GUI guide](docs/gui.md). No extra package is required.

Conditionals use `if`, `elseif`, and `else`; `else if` also works. Existing `when` programs remain supported.

## Optional libraries (Falcon 0.6)

[telebot](docs/telebot.md) provides Telegram polling, messages, uploads, inline keyboards, and logs. [pillow](docs/pillow.md) provides image creation, editing, drawing, and PNG/JPEG saving. These public modules live in `libraries/`, outside the standard library. See [copy/import instructions](libraries/README.md). Their native backends are included in the Falcon runtime; importing a module starts no network activity.
