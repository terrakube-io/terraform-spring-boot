package io.terrakube.terraform;

/**
 * Thrown when PID namespace isolation is required but unavailable on the host.
 */
public class PidIsolationUnavailableException extends IllegalStateException {
    public PidIsolationUnavailableException(String message) {
        super(message);
    }
}
