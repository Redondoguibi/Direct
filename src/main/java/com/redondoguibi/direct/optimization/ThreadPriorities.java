package com.redondoguibi.direct.optimization;

/** Shared by the thread-priority mixins (mixin classes cannot be referenced at runtime). */
public final class ThreadPriorities {
    /** One below {@link Thread#NORM_PRIORITY}; mapped to THREAD_PRIORITY_BELOW_NORMAL on Windows. */
    public static final int BACKGROUND = Thread.NORM_PRIORITY - 1;

    private ThreadPriorities() {}
}
