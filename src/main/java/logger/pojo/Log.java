package main.java.logger.pojo;

import main.java.logger.enums.Severity;

import java.io.Serializable;
import java.sql.Timestamp;

public class Log implements Serializable {

    private static final long serialVersionUID = 1234L;

    private String data;
    private Timestamp timestamp;
    private String threadId;
    private String threadName;
    private Severity severity;
    private String stackTrace;

    public Log() {
    }

    public Log(String data) {
        this.data = data;
    }

    public Log(String data, Severity severity) {
        this.data = data;
        this.severity = severity;
    }

    public String getData() {
        return data;
    }
    public void setData(String data) {
        this.data = data;
    }

    public Timestamp getTimestamp() {
        return timestamp;
    }
    public void setTimestamp(Timestamp timestamp) {
        this.timestamp = timestamp;
    }

    public String getThreadId() {
        return threadId;
    }
    public void setThreadId(String threadId) {
        this.threadId = threadId;
    }

    public String getThreadName() {
        return threadName;
    }
    public void setThreadName(String threadName) {
        this.threadName = threadName;
    }

    public Severity getSeverity() {
        return severity;
    }
    public void setSeverity(Severity severity) {
        this.severity = severity;
    }

    public String getStackTrace() {
        return stackTrace;
    }
    public void setStackTrace(String stackTrace) {
        this.stackTrace = stackTrace;
    }

    @Override
    public String toString() {
        return "[" + timestamp + "] [" + severity + "] [" + threadName + "#" + threadId + "] " + data;
    }
}
