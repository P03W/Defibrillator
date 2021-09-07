package mc.defibrillator.command

import com.github.p03w.aegis.*
import com.mojang.brigadier.CommandDispatcher
import mc.defibrillator.DefibState
import mc.defibrillator.Defibrillator
import mc.defibrillator.dimension.EmptyDimension
import mc.defibrillator.util.copyableText
import me.basiqueevangelist.nevseti.OfflineNameCache
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.text.LiteralText
import net.minecraft.text.MutableText

object CommandGenerator {
    fun registerMainCommand(dispatcher: CommandDispatcher<ServerCommandSource>) {
        dispatcher.register("defib2") {
            // Make sure they have permission to use the command
            requires {
                it.hasPermissionLevel(Defibrillator.config.commands.minimumRequiredLevel)
            }
            // Attach debug commands if enabled
            if (Defibrillator.config.commands.enableDebugCommands) {
                attachDebugTree()
            }

        }
    }

    private fun AegisCommandBuilder.attachDebugTree() {
        literal("debug") {
            requires {
                it.hasPermissionLevel(2)
            }
            literal("dimension") {
                literal("join_session") {
                    executes {
                        EmptyDimension.join(it)
                    }
                }
                literal("open_session") {
                    executes {
                        DefibState.activeChunkSessions.set(it.source.player.uuid, false, mutableListOf())
                    }
                }
                literal("close_session") {
                    executes {
                        DefibState.activeChunkSessions.remove(it.source.player.uuid)
                    }
                }
            }
            literal("input") {
                executes {
                    if (DefibState.awaitingInput.isEmpty()) {
                        it.source.sendFeedback(LiteralText("Not awaiting any input"), false)
                    } else {
                        DefibState.awaitingInput.forEach { entry ->
                            it.source.sendFeedback(
                                (entry.key.displayName as MutableText),
                                false
                            )
                        }
                    }
                }
            }
            literal("sessions") {
                literal("clear") {
                    uuid("uuid") {
                        executes {
                            DefibState.activeNBTSessions.remove(it.getUUID("uuid"))
                            it.source.sendFeedback(LiteralText("Removed session (if present)"), true)
                        }
                    }
                }
                literal("clearAll") {
                    executes {
                        DefibState.activeNBTSessions.clear()
                        it.source.sendFeedback(LiteralText("Removed all sessions (if present)"), true)
                    }
                }
                literal("list") {
                    executes {
                        // NBT Editing
                        it.source.sendFeedback(LiteralText("NBT Editing:"), false)
                        if (DefibState.activeNBTSessions.isEmpty()) {
                            it.source.sendFeedback(LiteralText("No active sessions"), false)
                        } else {
                            DefibState.activeNBTSessions.forEach { uuid, playerEntity, _ ->
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

                        // Advancement Editing
                        it.source.sendFeedback(LiteralText("Advancement Editing:"), false)
                        if (DefibState.activeAdvancementSessions.isEmpty()) {
                            it.source.sendFeedback(LiteralText("No active sessions"), false)
                        } else {
                            DefibState.activeAdvancementSessions.forEach { uuid, playerEntity, _ ->
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
            literal("freeze") {
                literal("entity") {
                    entity("entity") {
                        executes {
                            val entity = it.getEntity("entity")
                            DefibState.suppressedEntities[entity] = Defibrillator.config.errorManagement.retryDelay
                            it.source.sendFeedback(LiteralText("Frozen entity ").append(entity.name), true)
                        }
                        literal("forever") {
                            executes {
                                val entity = it.getEntity("entity")
                                DefibState.suppressedEntities[entity] = -1
                                it.source.sendFeedback(
                                    LiteralText("Frozen entity ").append(entity.name).append(" forever"), true
                                )
                            }
                        }
                    }
                }
            }
            literal("unfreeze") {
                literal("entity") {
                    entity("entity") {
                        executes {
                            val entity = it.getEntity("entity")
                            DefibState.suppressedEntities.remove(entity)
                            it.source.sendFeedback(LiteralText("Unfrozen entity ").append(entity.name), true)
                        }
                    }
                }
            }
        }
    }
}
