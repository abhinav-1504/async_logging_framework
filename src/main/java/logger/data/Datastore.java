package main.java.logger.data;

import main.java.logger.pojo.Log;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeoutException;

public interface Datastore {
    void appendLog(Collection<Log> logs) throws TimeoutException;
    List<Log> readLogs();
    void deleteLog();
}
