# Falcon logging module

The folder initializer is `__init__.fc`; `module.json` is optional when a
module uses the conventional initializer name.

Timestamped console log helpers: `debug`, `info`, `warn`, and `error`.

Install this subfolder from the public Falcon repository:

```sh
falcon install nexaridev/falcon-language --path packages/logging --name logging --ref main
```

Then import it from a `.fc` file:

```fc
use "logging" as log

func main() {
    log.info("Bot started")
    log.warn("Retrying a request")
    log.error("Request failed")
}
```

This module uses Falcon's standard `time` module and prints to the console.
