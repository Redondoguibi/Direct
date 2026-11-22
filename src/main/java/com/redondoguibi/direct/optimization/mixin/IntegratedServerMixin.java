package com.redondoguibi.direct.optimization.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.redondoguibi.direct.optimization.ThreadPriorities;

import net.minecraft.client.server.IntegratedServer;

/**
 * ModernFix perf.thread_priorities (client half): the singleplayer server thread runs one level
 * below normal priority, so under CPU contention the client's render thread is scheduled first.
 * Tick logic, rates and ordering are unchanged.
 */
@Mixin(IntegratedServer.class)
abstract class IntegratedServerMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void direct$lowerServerPriority(CallbackInfo callback) {
        Thread thread = ((IntegratedServer) (Object) this).getRunningThread();
        if (thread != null) thread.setPriority(ThreadPriorities.BACKGROUND);
    }
}
