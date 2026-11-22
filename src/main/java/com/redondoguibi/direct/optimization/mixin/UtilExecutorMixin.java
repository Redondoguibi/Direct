package com.redondoguibi.direct.optimization.mixin;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import com.redondoguibi.direct.optimization.ThreadPriorities;

import net.minecraft.Util;

/**
 * ModernFix perf.thread_priorities: {@code Util}'s background pool (chunk meshing, world
 * generation, resource loading) is created at normal priority and competes with the render thread
 * on equal terms. Its workers are started at priority 4 (one below normal) so frames keep the CPU
 * under contention; with idle cores nothing changes. Thread count and names are untouched.
 */
@Mixin(Util.class)
abstract class UtilExecutorMixin {
    @ModifyArg(method = "makeExecutor", index = 1, at = @At(value = "INVOKE",
            target = "Ljava/util/concurrent/ForkJoinPool;<init>(ILjava/util/concurrent/ForkJoinPool$ForkJoinWorkerThreadFactory;Ljava/lang/Thread$UncaughtExceptionHandler;Z)V"))
    private static ForkJoinPool.ForkJoinWorkerThreadFactory direct$lowerPriority(ForkJoinPool.ForkJoinWorkerThreadFactory factory) {
        return pool -> {
            ForkJoinWorkerThread thread = factory.newThread(pool);
            if (thread != null) thread.setPriority(ThreadPriorities.BACKGROUND);
            return thread;
        };
    }
}
