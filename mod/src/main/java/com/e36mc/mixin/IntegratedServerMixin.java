package com.e36mc.mixin;

import com.e36mc.E36mcMod;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.world.GameMode;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin into IntegratedServer to detect when the player opens their world to LAN.
 * 
 * When openToLan() is called, we capture the LAN port and notify e36mc
 * to start the tunnel connection.
 * 
 * Target: net.minecraft.server.integrated.IntegratedServer#openToLan(GameMode, boolean, int)
 * In 1.21+, the method signature is: openToLan(GameMode gameMode, boolean cheatsAllowed, int port)
 * Returns true if successful.
 */
@Mixin(IntegratedServer.class)
public class IntegratedServerMixin {

    /**
     * Inject at the TAIL of openToLan — after the LAN server has been successfully opened.
     * We capture the port parameter to know which local port Minecraft is listening on.
     */
    @Inject(method = "openToLan", at = @At("RETURN"))
    private void onOpenToLan(@Nullable GameMode gameMode, boolean cheatsAllowed, int port,
                             CallbackInfoReturnable<Boolean> cir) {
        // Only proceed if opening to LAN was successful
        if (cir.getReturnValue()) {
            E36mcMod.LOGGER.info("[e36mc] Detected LAN open on port {}", port);
            E36mcMod.onLanOpened(port);
        }
    }
}
