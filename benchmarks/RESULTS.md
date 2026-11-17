# Falcon speed check

Measured 17 November 2026 on Windows 11 Pro, Intel Core i5-1135G7 (4 cores / 8 logical processors), OpenJDK 25.0.1, CPython 3.12.4, Rust 1.99.1. Times are milliseconds, medians of three separate runs. Timers measure the operation body and exclude process startup and input generation. All checksums matched.

| Workload | Falcon 0.8 interpreter | Java | Python | Rust `-O` |
|---|---:|---:|---:|---:|
| Sort 1,500 integers | 3.6 ms | 0.023 ms | 0.125 ms | 0.019 ms |
| 100,000 integer arithmetic loop, original workload | 63.5 ms | 0.35 ms | 12.4 ms | 0.14 ms |
| Same modular sum via `math.sumModuloRange` | 0.98 ms | — | — | — |

The initial Falcon release used insertion sort and took about 1,348 ms. After replacing it with the runtime's stable O(n log n) sort, the median is 3.6 ms (about 375× faster). The other languages use built-in optimized sorts. Falcon remains about 157× slower than Java, 29× slower than Python, and 189× slower than Rust on this operation.

The arithmetic loop applies the same formula to the same 100,000 indices: `total += (i * 17 + 13) % 1009`. The checksum was 50,396,093 in every language. Falcon's ordinary interpreted loop took about 180× Java, 5.1× Python, and 450× Rust in these runs. `math.sumModuloRange(0, 100000, 17, 13, 1009)` now computes that exact sum with a logarithmic floor-sum algorithm in about 0.98 ms, roughly 65× faster than Falcon's loop. This special-purpose aggregation is not a faster general-purpose loop. `collections.sumNumbers` also sums lists in the Java runtime; the 1,500-number checksum reduction measured about 1.8 ms including interpreter-to-native dispatch. Java and Rust are compiled; Falcon is currently a tree-walking interpreter. These are single-PC measurements, not universal language rankings.

Reproduce with the source files in this folder. Falcon sorting needs `--max-steps 100000000` because the current algorithm exceeds the default execution budget at this input size.
