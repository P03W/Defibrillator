/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package mc.defibrillator.mixin;

import mc.defibrillator.DefibState;
import mc.defibrillator.Defibrillator;
import net.minecraft.world.World;
import net.minecraft.world.chunk.BlockEntityTickInvoker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(World.class)
public class BlockEntityTickSuppressor {
    @Redirect(method = "tickBlockEntities", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/chunk/BlockEntityTickInvoker;tick()V"))
    void tick(BlockEntityTickInvoker blockEntityTickInvoker) {
        long location = blockEntityTickInvoker.getPos().asLong();
        if (!DefibState.suppressedBlockEntities.containsKey(location)) {
            try {
                blockEntityTickInvoker.tick();
            } catch (Throwable any) {
                if (Defibrillator.getConfig().errorManagement.autoPauseCrashingBlocks) {
                    Defibrillator.LOGGER.severe(any.toString());
                    DefibState.suppressedBlockEntities.put(location, Defibrillator.getConfig().errorManagement.retryDelay);
                    Defibrillator.LOGGER.warning("Automatically froze ticking block entity");
                } else {
                    throw any;
                }
            }
        } else {
            if (Defibrillator.getConfig().errorManagement.retryDelay != -1) {
                int amount = DefibState.suppressedBlockEntities.get(location);
                DefibState.suppressedBlockEntities.put(location, --amount);
                if (amount == 0) {
                    DefibState.suppressedBlockEntities.remove(location);
                }
            }
        }
    }
}
