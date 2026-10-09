package main.java.logger.enums;

public enum Severity {
    LOW("low"),
    HIGH("high"),
    WARN("warn");

    private final String name;

    Severity(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
}