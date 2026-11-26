# Falcon modules

Falcon modules are installed into the current project's `modules/` directory.
Use either a folder module with an initializer or a module with a `module.json`
manifest. Folder modules are a good default for modules with several files.

## Create a folder module

Create this layout in a repository:

```text
logging/
├── __init__.fc
├── format.fc
└── README.md
```

`__init__.fc` is the module entry point. Falcon also accepts `init.fc` for the
entry point. Files inside the folder can import siblings by relative `.fc` path:

```fc
// Disclaimer: Falcon and NexariDev do not maintain third-party modules.
use "format.fc" as format

func info(message: string) {
    say("[INFO] " + format.timestamp() + " " + message)
}
```

If you want to use a manifest instead, put a `module.json` in the module folder:

```json
{
  "name": "logging",
  "version": "0.1.0",
  "description": "Logging helpers for Falcon programs.",
  "main": "__init__.fc"
}
```

When both the conventional initializer and `module.json` exist, Falcon uses
`__init__.fc` first, then `init.fc`, then the manifest's `main` entry. A plain
file module such as `logging.fc` still works. Don't publish both `logging.fc`
and `modules/logging/__init__.fc` for the same import name; Falcon reports that
collision and shows both locations.

## Test and publish a module

Check the entry point before publishing:

```sh
falcon check logging/__init__.fc --library
```

Commit the module sources, `README.md`, and optional manifest to a public GitHub
repository. Don't commit access tokens, bot keys, or other secrets. Use the
repository's Releases/tags to mark stable versions, and explain its API, Falcon
version requirements, and dependencies in its README.

Install a module that is the repository root:

```sh
falcon install owner/logging
```

Install a module stored in a repository subfolder:

```sh
falcon install nexaridev/falcon-language --path packages/logging --name logging --ref main
```

The installer clones the selected public repository/ref, copies the requested
module folder into `./modules/logging`, checks its initializer or manifest, and
prints the installed path. Then import it:

```fc
use "logging" as log

func main() {
    log.info("Application started")
}
```

Falcon doesn't run the module during installation. Imported code runs when your
program runs, so inspect third-party source and only install modules you trust.

## GitHub Packages

The sample `packages/logging` module also has a Maven package descriptor. A
repository maintainer can run the **Publish logging module** GitHub Actions
workflow to publish `io.github.nexaridev:logging:0.1.0` to GitHub Packages.
This provides a Maven distribution; `falcon install owner/repository` installs
Falcon source from GitHub so it can place the module files directly in the
project's `modules/` folder.
