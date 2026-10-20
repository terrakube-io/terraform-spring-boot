package io.terrakube.terraform;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
final class PidIsolationSupport {

    static final String SHIM = "trap 'kill -TERM $c 2>/dev/null' TERM INT; \"$@\" <&0 & c=$!; while :; do wait $c; s=$?; kill -0 $c 2>/dev/null || break; done; exit $s";

    private static final AtomicBoolean WARNED = new AtomicBoolean();
    private static volatile List<String> testOverridePrefix = null;

    private static final class Holder {
        static final List<String> PREFIX = detect();
    }

    private PidIsolationSupport() {}

    static boolean isAvailable() {
        if (testOverridePrefix != null) {
            return !testOverridePrefix.isEmpty();
        }
        return !Holder.PREFIX.isEmpty();
    }

    static List<String> prefix() {
        if (testOverridePrefix != null) {
            return testOverridePrefix;
        }
        return Holder.PREFIX;
    }

    static void overrideForTests(List<String> prefix) {
        testOverridePrefix = prefix;
    }

    static void resetForTests() {
        testOverridePrefix = null;
        WARNED.set(false);
    }

    static void warnUnavailableOnce() {
        if (WARNED.compareAndSet(false, true)) {
            log.warn("PID namespace isolation unavailable (unshare missing or insufficient privileges) - "
                    + "terraform child processes will not be isolated from this JVM's PID namespace");
        }
    }

    static List<String> wrap(List<String> originalCommand) {
        List<String> wrapped = new ArrayList<>(prefix());
        wrapped.add("--");
        wrapped.add("/bin/sh");
        wrapped.add("-c");
        wrapped.add(SHIM);
        wrapped.add("tf-pid-init");
        wrapped.addAll(originalCommand);
        return Collections.unmodifiableList(wrapped);
    }

    private static List<String> detect() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux")) {
            return Collections.emptyList();
        }
        // First try unprivileged user namespace unshare (standard Linux containers)
        List<String> unprivileged = List.of("unshare", "--map-root-user", "--user",
                "--pid", "--fork", "--mount-proc", "--kill-child");
        if (probe(unprivileged)) {
            return unprivileged;
        }
        // Fallback: root or privileged container without --user
        List<String> privileged = List.of("unshare", "--pid", "--fork", "--mount-proc", "--kill-child");
        if (probe(privileged)) {
            return privileged;
        }
        return Collections.emptyList();
    }

    private static boolean probe(List<String> prefix) {
        List<String> cmd = new ArrayList<>(prefix);
        cmd.add("--");
        cmd.add("/bin/sh");
        cmd.add("-c");
        cmd.add("exit 0");
        Process p = null;
        try {
            p = new ProcessBuilder(cmd).start();
            boolean finished = p.waitFor(2, TimeUnit.SECONDS);
            return finished && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (p != null && p.isAlive()) {
                p.destroyForcibly();
            }
        }
    }
}
