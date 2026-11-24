package com.example.agentic.workspace;

/** A change set does not apply cleanly to the current workspace (e.g. the code drifted). */
public class ChangeConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ChangeConflictException(String message) {
        super(message);
    }
}
