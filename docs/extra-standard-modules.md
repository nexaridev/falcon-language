# Additional standard modules

`cmd`, `crypto`, `regex`, and `compression` are included with Falcon and require no dependency installation.

## cmd

`cmd.run(command: string, timeoutMs: int) -> map` runs a shell command in Falcon's current working directory. The result contains `exit_code`, `stdout`, `stderr`, and `timed_out`. Timeout is limited to 1–300,000 ms; output is limited to 1 MiB per stream. `cmd.which(executable: string) -> string` returns the executable path or an empty string. `cmd.run` intentionally executes shell text, so pass only commands your program trusts.

## crypto

`sha256` and `md5` return lowercase hex digests. MD5 is provided for legacy checksums only. `base64Encode/base64Decode` and `hexEncode/hexDecode` encode UTF-8 text. `encrypt/decrypt` use AES-GCM; the key is SHA-256-derived, and the encrypted value is Base64 of a fresh 12-byte nonce plus authenticated ciphertext. Encryption is reversible, not password hashing: store keys separately and keep them secret.

## regex

`matches` checks whether the entire string matches a Java regular expression. `find` returns the first match or `""`; `findAll` returns all matched strings. `replace` uses Java regex replacement syntax, and `split` treats its pattern as a regex.

## compression

`gzip(text)` returns Base64-encoded gzip data; `gunzip(encoded)` returns UTF-8 text. `zip(source, archive)` archives a file or directory. `unzip(archive, destination)` extracts entries while rejecting paths that escape the destination folder.
