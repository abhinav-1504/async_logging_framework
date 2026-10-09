package main.java.logger;

import main.java.logger.enums.Severity;
import main.java.logger.pojo.Log;
import main.java.logger.service.Logger;

import java.util.List;

public class App {
    public static void main(String[] args) throws InterruptedException {
        Logger logger = Logger.getInstance();

        logger.addLog(new Log("Starting from here"));
        logger.addLog(new Log("Read data from DB"));
        logger.addLog(new Log("got exception", Severity.HIGH));
        logger.addLog(new Log("done", Severity.WARN));
        logger.appendLog();

        // Multi-threaded test
        Thread[] threads = new Thread[5];
        for (int i = 0; i < threads.length; i++) {
            final int id = i;
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 100; j++) {
                    logger.addLog(new Log("Thread-" + id + " log " + j));
                }
                logger.appendLog();
            }, "worker-" + i);
            threads[i].start();
        }
        for (Thread t : threads) t.join();

        logger.shutdown();

        List<Log> stored = logger.getStoredLogs();
        System.out.println("Total logs stored: " + stored.size());
        stored.stream().limit(5).forEach(System.out::println);
    }
}
