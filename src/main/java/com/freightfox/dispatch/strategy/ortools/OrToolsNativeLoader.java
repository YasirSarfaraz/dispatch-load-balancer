package com.freightfox.dispatch.strategy.ortools;

import com.google.ortools.Loader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Loads the OR-Tools native (JNI) libraries lazily, exactly once, and remembers the outcome.
 *
 * <p>Loading is deliberately NOT done at bean creation: if the platform has no matching native
 * library (e.g. an unsupported OS or a musl/alpine image), the application must still start and
 * the OR-Tools strategy simply falls back to greedy. The first {@link #ensureLoaded()} call
 * performs the load; later calls return the cached result without retrying. Thread-safe.
 */
@Component
public class OrToolsNativeLoader {

    private static final Logger log = LoggerFactory.getLogger(OrToolsNativeLoader.class);

    private final Runnable loadAction;

    /** {@code null} = not attempted yet; otherwise the cached outcome. Guarded by {@code this}. */
    private Boolean loaded;

    /** Production constructor: loads the libraries shipped in the ortools-java jars. */
    public OrToolsNativeLoader() {
        this(Loader::loadNativeLibraries);
    }

    /** Test seam: {@code loadAction} replaces the real native load (e.g. to simulate a failure). */
    OrToolsNativeLoader(Runnable loadAction) {
        this.loadAction = Objects.requireNonNull(loadAction, "loadAction");
    }

    /**
     * Loads the native libraries on the first call.
     *
     * @return {@code true} if the libraries are available, {@code false} if loading failed (the
     *         failure is logged once at WARN and cached; it is never retried or rethrown)
     */
    public synchronized boolean ensureLoaded() {
        if (loaded == null) {
            loaded = tryLoad();
        }
        return loaded;
    }

    private boolean tryLoad() {
        long startNanos = System.nanoTime();
        try {
            loadAction.run();
            log.info("OR-Tools native libraries loaded in {} ms", (System.nanoTime() - startNanos) / 1_000_000);
            return true;
        } catch (RuntimeException | LinkageError e) {
            // LinkageError covers UnsatisfiedLinkError and ExceptionInInitializerError.
            log.warn("OR-Tools native libraries could not be loaded; the ortools strategy will fall back: {}",
                    e.toString());
            return false;
        }
    }
}
