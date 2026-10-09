package main.java.logger.data;

import main.java.logger.pojo.Log;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

public class FileStore implements Datastore {

    private static final long LOCK_TIMEOUT_MS = 2000;
    private static final double DELETE_RATIO = 0.30;

    private final File file;
    private final long maxFileSizeBytes;
    private final ReentrantLock lock = new ReentrantLock();

    public FileStore() {
        this("test.log", 1024 * 1024);
    }

    public FileStore(String path, long maxFileSizeBytes) {
        this.file = new File(path);
        this.maxFileSizeBytes = maxFileSizeBytes;
    }


    @Override
    public void appendLog(Collection<Log> logs) throws TimeoutException {
        if (logs == null || logs.isEmpty()) return;

        try {
            if (!lock.tryLock(LOCK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw new TimeoutException("Could not acquire file lock in " + LOCK_TIMEOUT_MS + "ms");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TimeoutException("Interrupted while waiting for file lock");
        }

        try {
            if (file.exists() && file.length() >= maxFileSizeBytes) {
                deleteLog();
            }

            boolean fileHasData = file.exists() && file.length() > 0;

            try (FileOutputStream fos = new FileOutputStream(file, true);
                 ObjectOutputStream oos = fileHasData
                         ? new AppendableObjectOutputStream(fos)
                         : new ObjectOutputStream(fos)) {
                for (Log log : logs) {
                    oos.writeObject(log);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to append logs to " + file.getName(), e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<Log> readLogs() {
        lock.lock();
        try {
            return readAllInternal();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void deleteLog() {
        lock.lock();
        try {
            List<Log> all = readAllInternal();
            if (all.isEmpty()) return;

            int removeCount = (int) Math.ceil(all.size() * DELETE_RATIO);
            List<Log> remaining = all.subList(removeCount, all.size());

            File tmp = new File(file.getPath() + ".tmp");
            try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(tmp))) {
                for (Log log : remaining) {
                    oos.writeObject(log);
                }
            }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE);

            System.out.println("Deleted " + removeCount + " old logs, " + remaining.size() + " remain");
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete old logs", e);
        } finally {
            lock.unlock();
        }
    }

    private List<Log> readAllInternal() {
        List<Log> result = new ArrayList<>();
        if (!file.exists() || file.length() == 0) return result;

        try (ObjectInputStream ois = new ObjectInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            while (true) {
                try {
                    result.add((Log) ois.readObject());
                } catch (EOFException eof) {
                    break; // file khatam
                }
            }
        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException("Failed to read logs", e);
        }
        return result;
    }


    private static class AppendableObjectOutputStream extends ObjectOutputStream {
        AppendableObjectOutputStream(OutputStream out) throws IOException {
            super(out);
        }

        @Override
        protected void writeStreamHeader() throws IOException {
            reset();
        }
    }
}