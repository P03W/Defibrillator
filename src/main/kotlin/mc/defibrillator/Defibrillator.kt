/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package mc.defibrillator

import com.github.p03w.aegis.aegisCommand
import com.mojang.brigadier.CommandDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import mc.defibrillator.command.OfflinePlayerSuggester
import mc.defibrillator.gui.data.MenuState
import mc.defibrillator.gui.util.openNBTGui
import mc.defibrillator.util.copyableText
import mc.microconfig.MicroConfig
import me.basiqueevangelist.nevseti.OfflineDataCache
import me.basiqueevangelist.nevseti.OfflineDataChanged
import me.basiqueevangelist.nevseti.OfflineNameCache
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.command.argument.BlockPosArgumentType
import net.minecraft.command.argument.UuidArgumentType
import net.minecraft.item.ItemStack
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.text.LiteralText
import net.minecraft.text.MutableText
import net.minecraft.text.TranslatableText
import net.minecraft.util.Formatting
import net.minecraft.util.Hand
import net.minecraft.util.Util
import kotlin.time.ExperimentalTime

class Defibrillator : ModInitializer {
    @ExperimentalTime
    @ExperimentalStdlibApi
    override fun onInitialize() {
        println("----")
        println("DEFIBRILLATOR IS IN BETA")
        println("PLEASE REPORT ANY AND ALL ERRORS")
        println("----")

        // Remove GUI items from players
        ServerTickEvents.END_WORLD_TICK.register { world ->
            world.players.forEach {
                it.inventory.remove(
                    { stack -> stack.tag?.contains("defib-DELETE") ?: false },
                    Int.MAX_VALUE,
                    it.playerScreenHandler.method_29281()
                )
            }
        }

        // Refresh sessions when data changes
        OfflineDataChanged.EVENT.register { uuid, data ->
            try {
                val state = DefibState.activeSessions.getB(uuid)
                state.rootTag = data.copy()
                if (!state.isInAddMenu) {
                    state.factory?.makeAndUpdateNBTViewer(state.handler?.inv!!, state)
                }
            } catch (ignored: NullPointerException) {
                // Don't have a session
            }
        }

        // Main command
        CommandRegistrationCallback.EVENT.register { dispatcher: CommandDispatcher<ServerCommandSource>, _: Boolean ->
            dispatcher.register(aegisCommand("defib") {
                requires {
                    it.hasPermissionLevel(2)
                }
                literal("view") {
                    literal("item") {
                        executes {
                            openNBTGui(
                                it.source.player,
                                LiteralText("Held Item (VIEW)"),
                                MenuState(
                                    it.source.player.mainHandStack.toTag(CompoundTag()),
                                    Util.NIL_UUID
                                ),
                                false
                            ) { }
                        }
                    }
                    literal("block") {
                        blockPos("blockPos") {
                            executes(debug = true) {
                                val pos = BlockPosArgumentType.getBlockPos(it, "blockPos")
                                val world = it.source.world
                                val entity = world.getBlockEntity(pos)
                                if (entity != null) {
                                    val tag = entity.toTag(CompoundTag())
                                    openNBTGui(
                                        it.source.player,
                                        TranslatableText(world.getBlockState(pos).block.translationKey)
                                            .append(LiteralText("[${pos.x}, ${pos.y}, ${pos.z}] (VIEW)")),
                                        MenuState(
                                            tag,
                                            Util.NIL_UUID
                                        ),
                                        false
                                    ) { }
                                } else {
                                    it.source.sendError(
                                        LiteralText(
                                            "No block entity at $pos"
                                        ).formatted(Formatting.RED)
                                    )
                                }
                            }
                        }
                    }
                    literal("player") {
                        string("playerData") {
                            suggests(OfflinePlayerSuggester()::getSuggestions)
                            executes(debug = true) {
                                try {
                                    val uuid = OfflineNameCache.INSTANCE.getUUIDFromName(
                                        it.getArgument("playerData", String::class.java)
                                    )
                                    openNBTGui(
                                        it.source.player,
                                        LiteralText(it.getArgument("playerData", String::class.java))
                                            .append(LiteralText(" (VIEW)")),
                                        MenuState(
                                            OfflineDataCache.INSTANCE.get(uuid).copy(),
                                            uuid
                                        ),
                                        false
                                    ) { }
                                } catch (npe: NullPointerException) {
                                    it.source.sendError(
                                        LiteralText(
                                            "Could not load data for that user!"
                                        ).formatted(Formatting.RED)
                                    )
                                }
                            }
                        }
                    }
                }
                literal("modify") {
                    literal("item") {
                        executes {
                            openNBTGui(
                                it.source.player,
                                LiteralText("Held Item"),
                                MenuState(
                                    it.source.player.mainHandStack.toTag(CompoundTag()),
                                    Util.NIL_UUID
                                )
                            ) { state ->
                                it.source.player.setStackInHand(
                                    Hand.MAIN_HAND,
                                    ItemStack.fromTag(state.rootTag)
                                )
                                it.source.sendFeedback(LiteralText("Saved item data"), false)
                            }
                        }
                    }
                    literal("block") {
                        blockPos("blockPos") {
                            executes(debug = true) {
                                val pos = BlockPosArgumentType.getBlockPos(it, "blockPos")
                                val world = it.source.world
                                val entity = world.getBlockEntity(pos)
                                if (entity != null) {
                                    val tag = entity.toTag(CompoundTag())
                                    openNBTGui(
                                        it.source.player,
                                        TranslatableText(world.getBlockState(pos).block.translationKey)
                                            .append(LiteralText("[${pos.x}, ${pos.y}, ${pos.z}]")),
                                        MenuState(
                                            tag,
                                            Util.NIL_UUID
                                        )
                                    ) { state ->
                                        entity.fromTag(world.getBlockState(pos), state.rootTag)
                                        it.source.sendFeedback(LiteralText("Saved block data"), false)
                                    }
                                } else {
                                    it.source.sendError(
                                        LiteralText(
                                            "No block entity at $pos"
                                        ).formatted(Formatting.RED)
                                    )
                                }
                            }
                        }
                    }
                    literal("player") {
                        string("playerData") {
                            suggests(OfflinePlayerSuggester()::getSuggestions)
                            executes(debug = true) {
                                try {
                                    val uuid = OfflineNameCache.INSTANCE.getUUIDFromName(
                                        it.getArgument("playerData", String::class.java)
                                    )
                                    if (!DefibState.activeSessions.contains(uuid)) {
                                        val state = openNBTGui(
                                            it.source.player,
                                            LiteralText(it.getArgument("playerData", String::class.java)),
                                            MenuState(
                                                OfflineDataCache.INSTANCE.get(uuid).copy(),
                                                uuid
                                            )
                                        ) { state ->
                                            try {
                                                OfflineDataCache.INSTANCE.save(uuid, state.rootTag)
                                                it.source.sendFeedback(LiteralText("Saved user data"), true)
                                            } catch (ex: Exception) {
                                                it.source.sendError(
                                                    LiteralText("Failed to save user data").formatted(
                                                        Formatting.RED
                                                    )
                                                )
                                                ex.printStackTrace()
                                            } finally {
                                                DefibState.activeSessions.remove(uuid)
                                            }
                                        }

                                        DefibState.activeSessions.set(
                                            uuid,
                                            it.source.player,
                                            state
                                        )
                                    } else {
                                        it.source.sendError(
                                            LiteralText(
                                                "${DefibState.activeSessions[uuid].first.entityName} already has a session open for that uuid!"
                                            ).formatted(Formatting.RED)
                                        )
                                    }
                                } catch (npe: NullPointerException) {
                                    it.source.sendError(
                                        LiteralText(
                                            "Could not load data for that user!"
                                        ).formatted(Formatting.RED)
                                    )
                                }
                            }
                        }
                    }
                }
                literal("debug") {
                    literal("sessions") {
                        literal("clear") {
                            custom("uuid", UuidArgumentType()) {
                                executes {
                                    DefibState.activeSessions.remove(UuidArgumentType.getUuid(it, "uuid"))
                                    it.source.sendFeedback(LiteralText("Removed session (if present)"), false)
                                }
                            }
                        }
                        literal("clearAll") {
                            DefibState.activeSessions.clear()
                        }
                        literal("list") {
                            executes {
                                if (DefibState.activeSessions.isEmpty()) {
                                    it.source.sendFeedback(LiteralText("No active sessions"), false)
                                } else {
                                    DefibState.activeSessions.forEach { uuid, playerEntity, _ ->
                                        it.source.sendFeedback(
                                            (playerEntity.displayName as MutableText)
                                                .append(" -> ")
                                                .append(
                                                    copyableText(
                                                        uuid.toString(),
                                                        OfflineNameCache.INSTANCE.getNameFromUUID(uuid)
                                                    )
                                                ),
                                            false
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            })
        }
    }

    companion object {
        @JvmStatic
        val config: DefibrillatorConfig = MicroConfig.getOrCreate("defib", DefibrillatorConfig())

        val crashHandler = CoroutineExceptionHandler { context, exception ->
            println("DEFIBRILLATOR ASYNC EXCEPTION")
            println("CONTEXT: $context")
            println("EXCEPTION MESSAGE: ${exception.message}")
            throw exception
        }
    }
}
