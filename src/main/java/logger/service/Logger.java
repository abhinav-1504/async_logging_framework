package main.java.logger.service;

import main.java.logger.data.Datastore;
import main.java.logger.data.FileStore;
import main.java.logger.enums.Severity;
import main.java.logger.pojo.Log;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class Logger {

    private static final int MAX_RETRIES = 3;

    private final Datastore datastore;
    private final Object bufferLock = new Object();
    private List<Log> buffer = new ArrayList<>();

    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "logger-writer");
        t.setDaemon(false);
        return t;
    });

    private Logger() {
        this.datastore = new FileStore();
    }

    // Thread-safe lazy singleton (Initialization-on-demand holder)
    private static class Holder {
        private static final Logger INSTANCE = new Logger();
    }

    public static Logger getInstance() {
        return Holder.INSTANCE;
    }

    public void addLog(Log log) {
        Thread currentThread = Thread.currentThread();

        StackTraceElement[] elements = currentThread.getStackTrace();
        StringBuilder builder = new StringBuilder();
        for (int i = 2; i < elements.length; i++) {
            builder.append(i == 2 ? "" : "\tat ").append(elements[i]).append("\n");
        }

        log.setStackTrace(builder.toString());
        log.setTimestamp(new Timestamp(System.currentTimeMillis()));
        log.setThreadId(Long.toString(currentThread.getId()));
        log.setThreadName(currentThread.getName());
        if (log.getSeverity() == null) {
            log.setSeverity(Severity.LOW);
        }

        synchronized (bufferLock) {
            buffer.add(log);
        }
    }

    public void appendLog() {
        List<Log> batch;
        synchronized (bufferLock) {
            if (buffer.isEmpty()) return;
            batch = buffer;
            buffer = new ArrayList<>();
        }
        final List<Log> toWrite = batch;
        writer.submit(() -> writeWithRetry(toWrite));
    }

    private void writeWithRetry(List<Log> batch) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                datastore.appendLog(batch);
                return;
            } catch (TimeoutException e) {
                System.err.println("Write timeout (attempt " + attempt + "): " + e.getMessage());
                deleteLogs();
            } catch (Exception e) {
                System.err.println("Write failed: " + e.getMessage());
                return;
            }
        }
        System.err.println("Dropping batch of " + batch.size() + " logs after " + MAX_RETRIES + " retries");
    }

    private void deleteLogs() {
        try {
            datastore.deleteLog();
        } catch (Exception e) {
            System.err.println("Delete failed: " + e.getMessage());
        }
    }

    public List<Log> getStoredLogs() {
        return datastore.readLogs();
    }

    public void shutdown() {
        appendLog();
        writer.shutdown();
        try {
            if (!writer.awaitTermination(10, TimeUnit.SECONDS)) {
                writer.shutdownNow();
            }
        } catch (InterruptedException e) {
            writer.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}