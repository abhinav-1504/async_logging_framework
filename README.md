# Async Logger

A **thread-safe, asynchronous, file-based logging library** for Java. Application threads write logs into an in-memory buffer, and a dedicated background thread persists them to disk in batches, so your application never blocks on disk I/O.

![Java](https://img.shields.io/badge/Java-8%2B-orange)
![Build](https://img.shields.io/badge/build-maven-blue)
![License](https://img.shields.io/badge/license-MIT-green)

---

## Table of Contents

- [Features](#features)
- [Why this project?](#why-this-project)
- [Architecture](#architecture)
- [Project Structure](#project-structure)
- [Getting Started](#getting-started)
- [Usage](#usage)
- [How It Works](#how-it-works)
- [Design Decisions](#design-decisions)
- [Concurrency Guarantees](#concurrency-guarantees)
- [Configuration](#configuration)
- [Extending the Library](#extending-the-library)
- [Known Limitations](#known-limitations)

---

## Features

- **Asynchronous writes**: callers return immediately; a single background thread handles disk I/O.
- **Thread-safe**: safe to call from any number of producer threads.
- **Automatic enrichment**: every log gets a timestamp, thread ID, thread name, severity, and stack trace.
- **O(1) buffer swap**: no deep copying of logs when flushing.
- **Append-safe serialization**: custom `ObjectOutputStream` avoids stream-header corruption.
- **Timed locking**: `ReentrantLock.tryLock(timeout)` prevents indefinite blocking.
- **Log rotation**: when the file exceeds its size limit, the oldest **30%** of logs are evicted.
- **Crash-safe deletion**: uses a temp file and an atomic move, so the log file is never half-written.
- **Retry and graceful shutdown**: failed writes are retried; pending logs are flushed on `shutdown()`.
- **Pluggable storage**: the `Datastore` interface lets you swap the file backend for a DB or cloud store.

---

## Why this project?

A naive logger writes to disk on every call. That causes:

| Problem | Impact |
|---|---|
| Disk I/O on the caller's thread | Slow requests, poor throughput |
| No synchronization | Lost, duplicated, or interleaved logs in multi-threaded apps |
| Ever-growing log file | Disk fills up |
| Bare messages | No context on *when*, *which thread*, or *where* |

This library solves them with **buffering, async batch writes, log rotation, and automatic metadata enrichment**.

---

## Architecture

```
 Producer threads                          Background writer thread
 ────────────────                          ────────────────────────
 addLog(log)
   │  attach timestamp, thread info,
   │  stack trace, severity
   ▼
 ┌──────────────────────┐   appendLog()    ┌──────────────────────────┐
 │ In-memory buffer     │ ───────────────► │ Single-thread executor   │
 │ (synchronized list)  │   O(1) swap      │ (FIFO, ordered batches)  │
 └──────────────────────┘                  └────────────┬─────────────┘
                                                        │ writeWithRetry()
                                                        ▼
                                           ┌──────────────────────────┐
                                           │ Datastore (interface)    │
                                           └────────────┬─────────────┘
                                                        │
                                                        ▼
                                           ┌──────────────────────────┐
                                           │ FileStore                │
                                           │  1. tryLock(timeout)     │
                                           │  2. size >= max? rotate  │
                                           │  3. append batch         │
                                           └──────────────────────────┘
```

### Layers

| Layer | Class | Responsibility |
|---|---|---|
| Model | `Log`, `Severity` | Data carried by each log entry |
| Service | `Logger` | Public API, buffering, async hand-off, retries |
| Abstraction | `Datastore` | Storage contract |
| Implementation | `FileStore` | File persistence, locking, rotation |

---

## Project Structure

```
src/main/java/logger/
├── App.java                  # Demo / entry point
├── data/
│   ├── Datastore.java        # Storage interface
│   └── FileStore.java        # File-based implementation
├── enums/
│   └── Severity.java         # LOW, HIGH, WARN
├── pojo/
│   └── Log.java              # Log entry model
└── service/
    └── Logger.java           # Core logger (singleton)
```

---

## Getting Started

### Prerequisites

- Java 8 or higher
- Maven (or any Java build tool)

### Build and run

```bash
git clone <your-repo-url>
cd <project-folder>
mvn clean compile
mvn exec:java -Dexec.mainClass="logger.App"
```

Or with plain `javac`:

```bash
javac -d out $(find src -name "*.java")
java -cp out logger.App
```

The demo writes logs to `test.log` in the working directory.

---

## Usage

```java
import logger.enums.Severity;
import logger.pojo.Log;
import logger.service.Logger;

public class Demo {
    public static void main(String[] args) {
        Logger logger = Logger.getInstance();

        // Severity defaults to LOW when omitted
        logger.addLog(new Log("Application started"));
        logger.addLog(new Log("Cache miss for key=42", Severity.WARN));
        logger.addLog(new Log("Payment service unreachable", Severity.HIGH));

        // Hand the buffered batch to the background writer (non-blocking)
        logger.appendLog();

        // Always call shutdown before exit: flushes pending logs and stops the writer
        logger.shutdown();

        // Read back what was persisted
        logger.getStoredLogs().forEach(System.out::println);
    }
}
```

### Sample output

```
[2025-01-15 10:42:07.311] [LOW]  [main#1] Application started
[2025-01-15 10:42:07.312] [WARN] [main#1] Cache miss for key=42
[2025-01-15 10:42:07.312] [HIGH] [main#1] Payment service unreachable
```

### Multi-threaded usage

```java
for (int i = 0; i < 5; i++) {
    final int id = i;
    new Thread(() -> {
        for (int j = 0; j < 100; j++) {
            logger.addLog(new Log("Thread-" + id + " log " + j));
        }
        logger.appendLog();
    }, "worker-" + i).start();
}
```

No synchronization is needed on your side.

---

## How It Works

### 1. Enrich and buffer: `addLog(Log)`

The logger attaches the timestamp, thread ID, thread name, stack trace, and default severity, then appends the log to an in-memory `ArrayList` inside a `synchronized` block. Only memory is touched, so this is fast.

### 2. Hand off: `appendLog()`

Inside a short synchronized block, the current buffer becomes the batch and a fresh empty list replaces it (**O(1) swap**). The batch is submitted to a single-thread executor and the caller returns immediately.

### 3. Persist: `FileStore.appendLog(...)`

The writer thread:

1. Acquires the file lock with a 2 second timeout (`TimeoutException` on failure).
2. If the file has reached `maxFileSizeBytes`, runs `deleteLog()`.
3. Opens the file in append mode and writes each log. If the file already has data, it uses `AppendableObjectOutputStream` to avoid a second stream header.

### 4. Rotate: `deleteLog()`

1. Read all logs from the file.
2. Drop the oldest 30% (`ceil(size * 0.30)`).
3. Write the remaining logs to `<file>.tmp`.
4. Atomically move the temp file over the original.

A crash at any point leaves either the complete old file or the complete new file.

### 5. Retry: `writeWithRetry(...)`

On `TimeoutException`, the logger triggers rotation to free space and retries (max 3 attempts). After that the batch is dropped and an error is printed, so a failing disk can never crash the host application.

### 6. Shutdown: `shutdown()`

Flushes pending logs, stops accepting new tasks, and waits up to 10 seconds for the writer to finish before forcing termination.

---

## Design Decisions

| Decision | Reason |
|---|---|
| **Single writer thread** (not a pool) | One file means one writer. A pool gives no speedup, causes lock contention, and can reorder batches. |
| **Buffer swap** (not deep copy) | After the swap, the writer exclusively owns the old list. No sharing means no copying; O(1) vs O(n) serialization. |
| **`ArrayList`** (not `HashSet`) | Log order matters, and a set would drop equal entries. |
| **`tryLock` with timeout** | Gives a clean failure path (retry or drop) instead of a stuck thread. |
| **Temp file + atomic move** | Makes rotation crash-safe. |
| **Holder-idiom singleton** | Lazy and thread-safe through JVM class-loading guarantees, with no explicit locking. |
| **`Datastore` interface** | Strategy pattern and Dependency Inversion; swappable backends and easy mocking in tests. |
| **Immutable `Severity`** | Enum constants should not have setters. |

---

## Concurrency Guarantees

| Guarantee | Mechanism |
|---|---|
| No lost logs | Buffer guarded by a single lock |
| No interleaved writes | One writer thread, plus an internal `ReentrantLock` in `FileStore` |
| Ordered batches | Single-thread executor is FIFO |
| Non-blocking callers | Only the O(1) swap runs on the caller's thread |
| Safe singleton | Initialization-on-demand holder |

---

## Configuration

`FileStore` can be configured through its constructor:

```java
Datastore store = new FileStore("logs/app.log", 5 * 1024 * 1024); // 5 MB limit
```

| Parameter | Default | Description |
|---|---|---|
| `path` | `test.log` | Log file location |
| `maxFileSizeBytes` | `1 MB` | Size that triggers rotation |
| `LOCK_TIMEOUT_MS` | `2000` | Max wait for the file lock |
| `DELETE_RATIO` | `0.30` | Fraction of oldest logs evicted on rotation |
| `MAX_RETRIES` (`Logger`) | `3` | Write attempts before a batch is dropped |

---

## Extending the Library

Implement `Datastore` to add a new backend:

```java
public class DatabaseStore implements Datastore {

    @Override
    public void appendLog(Collection<Log> logs) throws TimeoutException {
        // batch insert into a logs table
    }

    @Override
    public List<Log> readLogs() {
        // SELECT * FROM logs ORDER BY timestamp
        return List.of();
    }

    @Override
    public void deleteLog() {
        // DELETE the oldest 30% of rows
    }
}
```

Then inject it in `Logger` in place of `FileStore`.

---

## Known Limitations

- **Unflushed logs are lost on crash**: logs still in the buffer are not persisted until `appendLog()` runs.
- **Java serialization**: slow, fragile across class changes, and not human-readable. Production loggers use text or JSON.
- **Rotation loads the whole file into memory**: fine at 1 MB, not at gigabytes. A rolling-file strategy scales better.
- **Unbounded buffer**: if producers outpace the disk, memory grows.
- **Stack trace capture on every log is expensive**: ideally only for `HIGH` severity.

---

## License

This project is licensed under the MIT License.
