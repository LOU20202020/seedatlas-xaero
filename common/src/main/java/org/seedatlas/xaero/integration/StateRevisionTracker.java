package org.seedatlas.xaero.integration;

import java.util.function.LongConsumer;

/** Client-thread revision gate. A paused or failed application must remain pending. */
public final class StateRevisionTracker {
    private long appliedRevision = Long.MIN_VALUE;

    public void synchronize(long revision, boolean paused, LongConsumer apply) {
        if (paused || revision == appliedRevision) return;
        apply.accept(revision);
        appliedRevision = revision;
    }
}
