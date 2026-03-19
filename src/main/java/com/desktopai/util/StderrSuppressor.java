package com.desktopai.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

/**
 * Redirects native stderr (file descriptor 2) to /dev/null so that llama.cpp,
 * ggml, and Metal/CUDA backend messages that bypass the Java log callback are
 * silenced. The Java callback (LlamaModel.setLogger) still routes intercepted
 * messages through SLF4J with the configured level filter.
 *
 * Uses Java 25 Foreign Function & Memory API. Requires JVM flag:
 *   --enable-native-access=ALL-UNNAMED
 *
 * Unix only (macOS / Linux). On Windows the call is a no-op.
 */
public final class StderrSuppressor {
    private static final Logger log = LoggerFactory.getLogger(StderrSuppressor.class);

    private static volatile int savedFd = -1;
    private static volatile boolean active = false;

    private StderrSuppressor() {}

    /**
     * Redirects native fd 2 to /dev/null. Idempotent — safe to call multiple times.
     */
    public static synchronized void suppress() {
        if (active) return;
        if (isWindows()) return;
        try {
            Linker linker = Linker.nativeLinker();
            SymbolLookup stdlib = linker.defaultLookup();

            // dup(int fd) → int  (save a copy of fd 2 so we can restore later)
            MethodHandle dup = linker.downcallHandle(
                    stdlib.find("dup").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

            // dup2(int oldfd, int newfd) → void  (we discard the return value)
            MethodHandle dup2 = linker.downcallHandle(
                    stdlib.find("dup2").orElseThrow(),
                    FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

            // open(const char* path, int oflag) → int  (O_WRONLY = 1 on POSIX)
            MethodHandle open = linker.downcallHandle(
                    stdlib.find("open").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

            // close(int fd) → void
            MethodHandle close = linker.downcallHandle(
                    stdlib.find("close").orElseThrow(),
                    FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT));

            int saved = (int) dup.invokeExact(2);
            if (saved < 0) {
                log.warn("StderrSuppressor: dup(2) returned {}", saved);
                return;
            }
            savedFd = saved;

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment path = arena.allocateFrom("/dev/null");
                int devNullFd = (int) open.invokeExact(path, 1 /* O_WRONLY */);
                if (devNullFd < 0) {
                    log.warn("StderrSuppressor: open(/dev/null) failed");
                    close.invokeExact(savedFd);
                    savedFd = -1;
                    return;
                }
                dup2.invokeExact(devNullFd, 2);
                close.invokeExact(devNullFd);
            }

            active = true;
            log.debug("StderrSuppressor: native stderr suppressed");
        } catch (Throwable e) {
            log.warn("StderrSuppressor: could not redirect fd 2: {}", e.getMessage());
        }
    }

    /**
     * Restores native fd 2 to its original target. No-op if not currently suppressed.
     */
    public static synchronized void restore() {
        if (!active || savedFd < 0) return;
        if (isWindows()) return;
        try {
            Linker linker = Linker.nativeLinker();
            SymbolLookup stdlib = linker.defaultLookup();

            MethodHandle dup2 = linker.downcallHandle(
                    stdlib.find("dup2").orElseThrow(),
                    FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
            MethodHandle close = linker.downcallHandle(
                    stdlib.find("close").orElseThrow(),
                    FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT));

            dup2.invokeExact(savedFd, 2);
            close.invokeExact(savedFd);
            savedFd = -1;
            active = false;
            log.debug("StderrSuppressor: native stderr restored");
        } catch (Throwable e) {
            log.warn("StderrSuppressor: could not restore fd 2: {}", e.getMessage());
        }
    }

    public static boolean isActive() { return active; }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().startsWith("win");
    }
}
