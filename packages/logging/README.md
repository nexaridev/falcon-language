# Falcon logging module

The folder initializer is `__init__.fc`; `module.json` is optional when a
module uses the conventional initializer name.

Timestamped console log helpers: `debug`, `info`, `warn`, and `error`.

This package is named `logging`, version `0.1.0`, and published in the
`nexaridev/logging-falcon` repository as release `v0.1.0`. Install it with:

```sh
falcon install nexaridev/logging-falcon --release v0.1.0
```

Then import it from a `.fc` file:

```fc
use logging as log

func main() {
    log.info("Bot started")
    log.warn("Retrying a request")
    log.error("Request failed")
}
```

This module uses Falcon's standard `time` module and prints to the console.
