package com.freightfox.dispatch.strategy.ortools;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class OrToolsNativeLoaderTest {

    @Test
    void realNativeLibraryLoads() {
        assertThat(new OrToolsNativeLoader().ensureLoaded()).isTrue();
    }

    @Test
    void successIsCachedAndLoadedOnce() {
        AtomicInteger calls = new AtomicInteger();
        OrToolsNativeLoader loader = new OrToolsNativeLoader(calls::incrementAndGet);

        assertThat(loader.ensureLoaded()).isTrue();
        assertThat(loader.ensureLoaded()).isTrue();
        assertThat(calls).hasValue(1);
    }

    @Test
    void linkageFailureIsCachedAndNeverThrown() {
        AtomicInteger calls = new AtomicInteger();
        OrToolsNativeLoader loader = new OrToolsNativeLoader(() -> {
            calls.incrementAndGet();
            throw new UnsatisfiedLinkError("simulated");
        });

        assertThat(loader.ensureLoaded()).isFalse();
        assertThat(loader.ensureLoaded()).isFalse();
        assertThat(calls).hasValue(1);
    }

    @Test
    void runtimeFailureIsReportedAsNotLoaded() {
        OrToolsNativeLoader loader = new OrToolsNativeLoader(() -> {
            throw new IllegalStateException("simulated extraction failure");
        });

        assertThat(loader.ensureLoaded()).isFalse();
    }
}
