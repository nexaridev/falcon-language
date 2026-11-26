# Contributing to Falcon

Thanks for helping improve Falcon. For a useful change:

1. Open an issue first for large language, runtime, or standard-library changes.
2. Keep changes focused and include a small example when syntax or behavior changes.
3. Run the checks before opening a pull request:

   - Windows: `./build.ps1 -Test`
   - Linux/macOS: `./build.sh`
   - VS Code language tests: `node --test editors/vscode/test/language.test.js`

4. Describe user-visible changes and any compatibility impact in the pull request.

Contributions to Falcon are submitted under the Apache License, Version 2.0,
as described in `LICENSE`.
