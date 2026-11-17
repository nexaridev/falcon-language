# Falcon standard library

All seventeen modules below are implemented and included with Falcon. Their public APIs live in editable `stdlib/*.fc` files. No package installation is needed. Java provides typed host primitives; Falcon implements library algorithms and composition.

## Imports and editing

```fc
use math
use collections as c
use "std/io.fc" as io
```

Named imports prefer a sibling `.fc` file, then the installed standard-library folder. `std/<name>.fc` explicitly selects the standard library; ordinary quoted paths stay local. Save changes in `stdlib/` and rerun with the installed launcher: no Java rebuild is needed. New `.fc` modules in that folder are discovered automatically. Standard modules may import sibling standard modules; cycles are rejected. `native/<name>.fc` imports internal runtime primitives.

The standalone JAR embeds a source copy, refreshed by `build.ps1`. VS Code checks and completions use `falcon.stdlibPath` when configured, otherwise its packaged source copy. Repackage the extension to distribute updated sources. See [additional module details](extra-standard-modules.md).

## Numeric and container types

`number` accepts both `int` and `float`, retaining the runtime representation. It rejects strings and booleans. Int-only APIs (indices, factorial, random bounds) require `int`; use `math.toInt` for an explicit checked conversion. `any` is the separate heterogeneous value type used by containers and JSON; extract it with `collections.asNumber`, `asString`, etc. `null` has type `any`. See the main README for literals, indexing, and iteration.

```fc
use math
use collections as c
use json

func main() {
    var value: number = 5
    value = 2.5
    say(math.square(value))
    var data = c.asMap(json.parse("{\"score\":2.5}"))
    say(c.asNumber(data["score"]) * 2)
}
```

## io

Console I/O. print has no newline; println adds one. prompt prints its message then reads a line. hasInput reads ahead and may block until a line or EOF arrives. readLine/prompt fail at EOF; readInt/readNumber parse one line through text. Input/output use UTF-8.

```fc
func print(value: any)
func println(value: any)
func readLine() -> string
func hasInput() -> bool
func prompt(message: string) -> string
func readInt() -> int
func readNumber() -> number
```

## math

`sumModuloRange(start, end, multiplier, offset, modulus)` quickly sums `(i * multiplier + offset) % modulus` for `start <= i < end`. It uses a logarithmic floor-sum algorithm, so it can replace very large loops when the result is only the aggregate. The modulus must be positive; the exact result must fit in `int`.

number arguments accept integers and decimals. square, abs, min, max and clamp preserve the selected/computed numeric representation. factorial requires an int from 0 through 20. sqrt requires a nonnegative input; pow rejects non-finite/non-real results. toInt truncates toward zero and checks the 64-bit range; round chooses the nearest integer with ties toward positive infinity. floor/ceil return float; pi returns a float constant.

```fc
func factorial(value: int) -> int
func square(value: number) -> number
func abs(value: number) -> number
func min(value: number, other: number) -> number
func max(value: number, other: number) -> number
func sqrt(value: number) -> float
func pow(value: number, other: number) -> float
func floor(value: number) -> float
func ceil(value: number) -> float
func round(value: number) -> int
func toInt(value: number) -> int
func pi() -> float
func clamp(value: number, minimum: number, maximum: number) -> number
```

## text

length, substring and string indexing count Unicode code points. substring end is exclusive. split uses a literal separator and preserves trailing empty pieces; an empty separator splits into code points. join requires strings. Conversion trims surrounding whitespace; toNumber retains integer text as int and decimal/exponent text as float. Invalid or out-of-range input fails. from formats a value for display, including collections.

```fc
func length(value: string) -> int
func trim(value: string) -> string
func lower(value: string) -> string
func upper(value: string) -> string
func contains(value: string, part: string) -> bool
func startsWith(value: string, part: string) -> bool
func endsWith(value: string, part: string) -> bool
func replace(value: string, oldText: string, newText: string) -> string
func substring(value: string, start: int, end: int) -> string
func split(value: string, separator: string) -> list
func join(items: list, separator: string) -> string
func toInt(value: string) -> int
func toFloat(value: string) -> float
func toNumber(value: string) -> number
func from(value: any) -> string
```

## collections

Lists and string-keyed maps are mutable reference values. push/set/put mutate; remove returns the removed list value. erase reports whether a map key existed, even if its value was null. get/lookup and indexing fail for absent indices/keys. reverse and sortNumbers return new lists. sortNumbers is a stable insertion sort written in Falcon; it accepts only numbers and uses the interpreter step budget. Lists/maps contain any values. asNumber/asInt/asString/etc validate and extract typed values; asFloat also widens int. kind returns int, float, bool, string, list, map or null. size accepts lists/maps; use text.length for strings.

```fc
func newList() -> list
func newMap() -> map
func size(value: any) -> int
func push(items: list, value: any)
func get(items: list, index: int) -> any
func set(items: list, index: int, value: any)
func remove(items: list, index: int) -> any
func put(entries: map, key: string, value: any)
func lookup(entries: map, key: string) -> any
func hasKey(entries: map, key: string) -> bool
func erase(entries: map, key: string) -> bool
func keys(entries: map) -> list
func values(entries: map) -> list
func reverse(items: list) -> list
func sortNumbers(items: list) -> list
func asNumber(value: any) -> number
func asInt(value: any) -> int
func asFloat(value: any) -> float
func asString(value: any) -> string
func asBool(value: any) -> bool
func asList(value: any) -> list
func asMap(value: any) -> map
func isNull(value: any) -> bool
func kind(value: any) -> string
```

## fs

File paths resolve from the program working directory. readText/writeText/appendText use UTF-8. writeText replaces an existing file. mkdir creates parent directories. listDir returns sorted path strings. remove deletes a file or empty directory, returns false if absent, and never deletes a directory recursively. I/O failures are source-positioned runtime errors.

```fc
func readText(filename: string) -> string
func writeText(filename: string, content: string)
func appendText(filename: string, content: string)
func exists(filename: string) -> bool
func isFile(filename: string) -> bool
func isDir(filename: string) -> bool
func mkdir(directory: string)
func listDir(directory: string) -> list
func remove(filename: string) -> bool
```

## path

Platform-native path operations. join normalizes the result; an absolute second path replaces the first. absolute resolves from the working directory. dirname returns a dot for a bare filename. extension includes the final dot suffix; a dotfile without another dot has no extension. stem removes that suffix. These operations do not access the filesystem.

```fc
func join(first: string, second: string) -> string
func normalize(value: string) -> string
func absolute(value: string) -> string
func filename(value: string) -> string
func dirname(value: string) -> string
func extension(value: string) -> string
func stem(value: string) -> string
```

## random

Generators belong to each program run. seed makes subsequent calls repeatable. next returns a float in [0, 1). integer uses an inclusive minimum and exclusive maximum, both ints. choice returns an any element and rejects empty lists. This module is not a cryptographic random generator.

```fc
func seed(value: int)
func next() -> float
func integer(minimum: int, maximum: int) -> int
func choice(items: list) -> any
```

## time

now returns Unix epoch milliseconds as int. monotonic returns seconds from an unspecified monotonic origin: subtract two readings to measure duration. sleep blocks for nonnegative milliseconds. iso formats epoch milliseconds in UTC ISO-8601 form.

```fc
func now() -> int
func monotonic() -> float
func sleep(milliseconds: int)
func iso(milliseconds: int) -> string
```

## json

parse returns any: null, bool, string, int, float, list, or map. Extract containers using collections.asMap/asList and index them. Integers must fit signed 64-bit; decimals/exponents must produce finite floats. The parser rejects malformed text, duplicate keys and trailing data. Parsing/serialization limit nesting to 128. stringify escapes strings and rejects cyclic containers. Maps preserve insertion order. JSON null uses the language null literal.

```fc
func parse(source: string) -> any
func stringify(value: any) -> string
```

## os

args returns arguments after the CLI -- delimiter, excluding the program filename. cwd/home/platform provide process information. env returns an empty string for a missing variable; hasEnv distinguishes missing from present-but-empty. This initial module does not execute shell commands or mutate environment variables.

```fc
func args() -> list
func cwd() -> string
func env(name: string) -> string
func hasEnv(name: string) -> bool
func platform() -> string
func home() -> string
```

## test

Assertions are implemented in Falcon. assert requires a condition and failure message; equal compares values/containers; near compares numeric values with a nonnegative absolute tolerance; fail stops the program with its message. Run an assertion program with falcon run tests.fc: success exits 0, a failed assertion exits 1. Test discovery and catchable errors are not yet implemented.

```fc
func assert(condition: bool, message: string)
func equal(actual: any, expected: any)
func near(actual: number, expected: number, tolerance: number)
func fail(message: string)
```

## http

get sends a GET with a 10-second request timeout. request takes method, URL, UTF-8 body and timeoutMs (1–60000). Both return a map with status (int), body (UTF-8 string), and headers (map of lowercase names to lists of strings). HTTP error statuses such as 404 are returned normally; connection/timeout failures raise runtime errors. Only http/https URLs are accepted. Normal redirects are followed. The initial API has no custom request headers, streaming, server hosting, or retries.

```fc
func get(url: string) -> map
func request(method: string, url: string, body: string, timeoutMs: int) -> map
```

## Current limits

This is the first supported API for each module, not a replacement for every feature of mature libraries. Library errors stop execution; try/catch and result types are future work. Checking a program performs no I/O or network requests. Running one executes the operations it calls. The interpreter step limit does not bound time or memory spent in host operations.

Try `falcon run examples/stdlib.fc -- hello`, `falcon run examples/number.fc`, or `falcon run examples/interactive.fc`.

## gui

Desktop windows, labels, inputs, buttons, dialogs, and event handling. Included with Falcon, backed by Java Swing. See [GUI API and example](gui.md); editable wrappers are in `stdlib/gui.fc`.
