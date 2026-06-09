package com.myreading.epub.model;

public final class ParseIssue {
    public enum Severity {
        INFO,
        WARNING,
        ERROR
    }

    private final Severity severity;
    private final String message;
    private final String path;

    public ParseIssue(Severity severity, String message, String path) {
        this.severity = severity;
        this.message = message;
        this.path = path;
    }

    public Severity getSeverity() {
        return severity;
    }

    public String getMessage() {
        return message;
    }

    public String getPath() {
        return path;
    }
}

