/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package mc.defibrillator

import com.mojang.brigadier.CommandDispatcher
import io.github.ladysnake.pal.AbilitySource
import io.github.ladysnake.pal.Pal
import kotlinx.coroutines.CoroutineExceptionHandler
import mc.defibrillator.command.CommandGenerator
import mc.defibrillator.dimension.EmptyDimension
import mc.microconfig.MicroConfig
import me.basiqueevangelist.nevseti.OfflineDataChanged
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents
import net.minecraft.server.command.ServerCommandSource
import java.util.logging.Logger
import kotlin.time.ExperimentalTime

class Defibrillator : ModInitializer {
    @OptIn(ExperimentalTime::class)
    override fun onInitialize() {
        // Clean up awaits and state on close
        ServerLifecycleEvents.SERVER_STOPPING.register(EventHandlers::onServerClosed)

        // Remove GUI items from players
        ServerTickEvents.END_WORLD_TICK.register(EventHandlers::onWorldEndTick)

        // Refresh sessions when data changes
        OfflineDataChanged.EVENT.register(EventHandlers::onOfflineDataChanged)

        // Prevent block breaking
        PlayerBlockBreakEvents.BEFORE.register(EventHandlers::onBeforeBreakBlock)

        // Main command
        CommandRegistrationCallback.EVENT.register { dispatcher: CommandDispatcher<ServerCommandSource>, _ ->
            CommandGenerator.registerMainCommand(dispatcher)
        }

        // Add dimension
        EmptyDimension.register()
    }

    companion object {
        @JvmField
        val LOGGER: Logger = Logger.getLogger("Defibrillator")

        @JvmStatic
        val config: DefibrillatorConfig = MicroConfig.getOrCreate("defib", DefibrillatorConfig())

        val canModifyWorldAbility: AbilitySource = Pal.getAbilitySource("defib", "dimension_edit_limiter")

        val crashHandler = CoroutineExceptionHandler { context, exception ->
            println("DEFIBRILLATOR ASYNC EXCEPTION")
            println("CONTEXT: $context")
            println("EXCEPTION MESSAGE: ${exception.message}")
            if (config.rethrowAsyncErrors) {
                throw exception
            }
        }
    }
}
