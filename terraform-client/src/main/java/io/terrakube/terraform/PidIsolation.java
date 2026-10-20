package io.terrakube.terraform;

/**
 * Modes for Linux PID namespace isolation in {@link ProcessLauncher}.
 */
public enum PidIsolation {
    /** Never isolate (default). */
    OFF,
    /** Isolate when the host supports it, otherwise log a warning once and run unisolated. */
    BEST_EFFORT,
    /** Isolate or refuse to launch: throws {@link PidIsolationUnavailableException} (fail closed). */
    REQUIRED
}
