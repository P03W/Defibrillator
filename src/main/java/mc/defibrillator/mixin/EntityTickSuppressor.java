/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package mc.defibrillator.mixin;

import mc.defibrillator.DefibState;
import mc.defibrillator.Defibrillator;
import net.minecraft.entity.Entity;
import net.minecraft.util.crash.CrashException;
import net.minecraft.util.crash.CrashReport;
import net.minecraft.util.crash.CrashReportSection;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.util.function.Consumer;

@Mixin(World.class)
public class EntityTickSuppressor {
    /**
     * @reason The alternative here is 2-3 short circuit injects, so this is much cleaner overall
     *
     * @author P03W
     */
    @Overwrite
    public <T extends Entity> void tickEntity(Consumer<T> tickConsumer, T entity) {
        if (DefibState.suppressedEntities.containsKey(entity)) {
            if (Defibrillator.getConfig().errorManagement.retryDelay != -1) {
                int amount = DefibState.suppressedEntities.get(entity);
                DefibState.suppressedEntities.put(entity, --amount);
                if (amount == 0) {
                    DefibState.suppressedEntities.remove(entity);
                }
            }
            return;
        }
    
        try {
            tickConsumer.accept(entity);
        } catch (Throwable err) {
            CrashReport crashReport = CrashReport.create(err, "Ticking entity");
            CrashReportSection crashReportSection = crashReport.addElement("Entity being ticked");
            entity.populateCrashReport(crashReportSection);
            CrashException newError = new CrashException(crashReport);
            if (Defibrillator.getConfig().errorManagement.autoPauseCrashingEntities) {
                Defibrillator.LOGGER.severe(newError.getReport().asString());
                DefibState.suppressedEntities.put(entity, Defibrillator.getConfig().errorManagement.retryDelay);
                Defibrillator.LOGGER.warning("Automatically froze entity");
            } else {
                throw newError;
            }
        }
    }
}
