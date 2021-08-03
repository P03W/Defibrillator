/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package mc.defibrillator

import com.github.p03w.aegis.*
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.context.CommandContext
import io.github.ladysnake.pal.VanillaAbilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import mc.defibrillator.command.OfflinePlayerSuggester
import mc.defibrillator.dimension.EmptyDimension
import mc.defibrillator.gui.data.AdvancementMenuState
import mc.defibrillator.gui.data.NBTMenuState
import mc.defibrillator.gui.util.openAdvancementGui
import mc.defibrillator.gui.util.openNBTGui
import mc.defibrillator.util.copyableText
import mc.defibrillator.util.toNBT
import me.basiqueevangelist.nevseti.OfflineAdvancementUtils
import me.basiqueevangelist.nevseti.OfflineDataCache
import me.basiqueevangelist.nevseti.OfflineNameCache
import me.basiqueevangelist.nevseti.nbt.NbtCompoundView
import net.fabricmc.fabric.api.dimension.v1.FabricDimensions
import net.minecraft.block.BlockState
import net.minecraft.block.entity.BlockEntity
import net.minecraft.command.argument.EntityArgumentType
import net.minecraft.entity.EntityType
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.item.ItemStack
import net.minecraft.server.MinecraftServer
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.server.world.ServerWorld
import net.minecraft.text.LiteralText
import net.minecraft.text.MutableText
import net.minecraft.text.Text
import net.minecraft.text.TranslatableText
import net.minecraft.util.Formatting
import net.minecraft.util.Hand
import net.minecraft.util.Util
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import net.minecraft.world.TeleportTarget
import net.minecraft.world.World
import java.util.*
import kotlin.time.ExperimentalTime

@Suppress("UNUSED_PARAMETER")
object EventHandlers {
    fun onServerClosed(server: MinecraftServer) {
        // Any jobs we dispatched
        DefibState.coroutineScope.cancel(CancellationException("Server closing"))

        // Active sessions go bye bye
        DefibState.activeAdvancementSessions.clear()
        DefibState.activeChunkSessions.clear()
        DefibState.activeNBTSessions.clear()

        // No more awaiting input
        DefibState.awaitingInput.clear()
    }

    fun onWorldEndTick(world: ServerWorld) {
        val server = world.server
        val emptyWorld: ServerWorld? = server.getWorld(EmptyDimension.WORLD_KEY)

        world.players.forEach {
            // Stop limiting world modification if they are no longer in edit world
            if (Defibrillator.canModifyWorldAbility.grants(it, VanillaAbilities.LIMIT_WORLD_MODIFICATIONS)) {
                if (world != emptyWorld) {
                    Defibrillator.canModifyWorldAbility.revokeFrom(it, VanillaAbilities.LIMIT_WORLD_MODIFICATIONS)
                }
            } else if (world == emptyWorld) {
                Defibrillator.canModifyWorldAbility.grantTo(it, VanillaAbilities.LIMIT_WORLD_MODIFICATIONS)
            }
        }

        // Remove unapproved players from the edit world
        if (world == emptyWorld) {
            val toRespawn = world.players.filterNot {
                DefibState.activeChunkSessions.any { uuid, _, mutableList ->
                    uuid == it.uuid || mutableList.contains(it.uuid)
                }
            }
            toRespawn.forEach {
                FabricDimensions.teleport(
                    it,
                    server.getWorld(it.spawnPointDimension),
                    TeleportTarget(
                        Vec3d.ofCenter(it.spawnPointPosition),
                        Vec3d.ZERO,
                        0f,
                        0f
                    )
                )

                it.sendSystemMessage(
                    LiteralText("That dimension is restricted!").formatted(Formatting.BOLD)
                        .formatted(Formatting.RED),
                    Util.NIL_UUID
                )
            }
        }
    }

    // Prevent block breaking if not permitted
    fun onBeforeBreakBlock(
        world: World,
        playerEntity: PlayerEntity,
        blockPos: BlockPos,
        blockState: BlockState,
        blockEntity: BlockEntity?
    ): Boolean {
        return !Defibrillator.canModifyWorldAbility.grants(playerEntity, VanillaAbilities.LIMIT_WORLD_MODIFICATIONS)
    }

    @ExperimentalTime
    fun onOfflineDataChanged(uuid: UUID, data: NbtCompoundView) {
        try {
            // Rebuild the session if available
            val state = DefibState.activeNBTSessions.getB(uuid)
            state.rootTag = data.copy()
            if (!state.isInAddMenu) {
                state.factory?.rebuild()
            }
        } catch (ignored: NullPointerException) {
            // Don't have a session
        }
    }

    fun registerMainCommand(dispatcher: CommandDispatcher<ServerCommandSource>) {
        dispatcher.register("defib") {
            requires {
                it.hasPermissionLevel(Defibrillator.config.commands.minimumRequiredLevel)
            }
            if (Defibrillator.config.commands.enableDebugCommands) {
                attachDebugTree()
            }
            literal("view") {
                requires {
                    it.hasPermissionLevel(Defibrillator.config.commands.viewRequiredLevel)
                }
                literal("item") {
                    executes {
                        openViewItem(it)
                    }
                }
                literal("block") {
                    blockPos("blockPos") {
                        executes {
                            openViewBlock(it)
                        }
                    }
                }
                literal("player") {
                    literal("data") {
                        string("playerData") {
                            suggests(OfflinePlayerSuggester()::getSuggestions)
                            executes {
                                openViewPlayer(it)
                            }
                        }
                    }
                    literal("advancements") {
                        string("playerData") {
                            suggests(OfflinePlayerSuggester()::getSuggestions)
                            executes {
                                openViewAdvancements(it)
                            }
                        }
                    }
                }
                literal("entity") {
                    entity("entity") {
                        executes {
                            openViewEntity(it)
                        }
                    }
                }
            }
            literal("modify") {
                requires {
                    it.hasPermissionLevel(Defibrillator.config.commands.editRequiredLevel)
                }
                literal("item") {
                    executes {
                        openEditItem(it)
                    }
                }
                literal("block") {
                    blockPos("blockPos") {
                        executes {
                            openEditBlock(it)
                        }
                    }
                }
                literal("player") {
                    literal("data") {
                        string("playerData") {
                            suggests(OfflinePlayerSuggester()::getSuggestions)
                            executes {
                                openEditPlayer(it)
                            }
                        }
                    }
                    literal("advancements") {
                        string("playerData") {
                            suggests(OfflinePlayerSuggester()::getSuggestions)
                            executes {
                                openEditAdvancements(it)
                            }
                        }
                    }
                }
                literal("entity") {
                    entity("entity") {
                        executes {
                            openEditEntity(it)
                        }
                    }
                }
            }
        }
    }

    /*

    Edit Commands

    */
    private fun openEditEntity(it: CommandContext<ServerCommandSource>) {
        val entity = it.getEntity("entity")
            .also { if (it is PlayerEntity) throw EntityArgumentType.NOT_ALLOWED_EXCEPTION.create() }
        val tag = entity.toNBT()

        if (DefibState.activeNBTSessions.contains(entity.uuid)) {
            it.source.sendError(
                LiteralText(
                    "${DefibState.activeNBTSessions[entity.uuid].first.entityName} already has a session open for that uuid!"
                ).formatted(Formatting.RED)
            )
            return
        }

        val state = openNBTGui(
            it.source.player,
            ((if (tag.contains("CustomName"))
                Text.Serializer.fromJson(tag.getString("CustomName"))
            else TranslatableText("entity.${EntityType.getId(entity.type).toString().replace(':', '.')}"))
                ?: LiteralText("[ERROR]")),
            NBTMenuState(
                tag,
                Util.NIL_UUID,
                it.source.player
            )
        ) {
            DefibState.activeNBTSessions.remove(entity.uuid)
            entity.readNbt(it.rootTag)
        }

        DefibState.activeNBTSessions.set(
            entity.uuid,
            it.source.player,
            state
        )
    }

    private fun openEditAdvancements(it: CommandContext<ServerCommandSource>) {
        try {
            val uuid = OfflineNameCache.INSTANCE.getUUIDFromName(
                it.getString("playerData")
            )
            if (!DefibState.activeAdvancementSessions.contains(uuid)) {
                val state = openAdvancementGui(
                    it.source.player,
                    LiteralText(it.getString("playerData"))
                        .append(LiteralText("'s Advancements")),
                    AdvancementMenuState(
                        uuid,
                        it.source.player
                    ),
                    true
                ) { state ->
                    if (state.suppressOnClose.get().not()) {
                        state.overrides.forEach { (id, state) ->
                            val actual = it.source.player.server.advancementLoader[id]
                            if (state) {
                                OfflineAdvancementUtils.grant(uuid, actual)
                            } else {
                                OfflineAdvancementUtils.revoke(uuid, actual)
                            }
                        }
                    }
                }

                DefibState.activeAdvancementSessions.set(
                    uuid,
                    it.source.player,
                    state
                )
            } else {
                it.source.sendError(
                    LiteralText(
                        "${DefibState.activeAdvancementSessions[uuid].first} already has a session open for that uuid!"
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

    private fun openEditPlayer(it: CommandContext<ServerCommandSource>) {
        try {
            val uuid = OfflineNameCache.INSTANCE.getUUIDFromName(
                it.getString("playerData")
            )

            if (DefibState.activeNBTSessions.contains(uuid)) {
                it.source.sendError(
                    LiteralText(
                        "${DefibState.activeNBTSessions[uuid].first.entityName} already has a session open for that uuid!"
                    ).formatted(Formatting.RED)
                )
                return
            }

            val state = openNBTGui(
                it.source.player,
                LiteralText(it.getString("playerData")),
                NBTMenuState(
                    OfflineDataCache.INSTANCE.get(uuid).copy(),
                    uuid,
                    it.source.player
                )
            ) { state ->
                if (state.suppressOnClose.get().not()) {
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
                        DefibState.activeNBTSessions.remove(uuid)
                    }
                }
            }

            DefibState.activeNBTSessions.set(
                uuid,
                it.source.player,
                state
            )
        } catch (npe: NullPointerException) {
            it.source.sendError(
                LiteralText(
                    "Could not load data for that user!"
                ).formatted(Formatting.RED)
            )
        }
    }

    private fun openEditBlock(it: CommandContext<ServerCommandSource>) {
        val pos = it.getBlockPos("blockPos")
        val world = it.source.world
        val entity = world.getBlockEntity(pos)
        if (entity != null) {
            val tag = entity.toNBT()
            openNBTGui(
                it.source.player,
                TranslatableText(world.getBlockState(pos).block.translationKey)
                    .append(LiteralText("[${pos.x}, ${pos.y}, ${pos.z}]")),
                NBTMenuState(
                    tag,
                    Util.NIL_UUID,
                    it.source.player
                )
            ) { state ->
                if (state.suppressOnClose.get().not()) {
                    entity.readNbt(state.rootTag)
                    it.source.sendFeedback(LiteralText("Saved block data"), false)
                }
            }
        } else {
            it.source.sendError(
                LiteralText(
                    "No block entity at $pos"
                ).formatted(Formatting.RED)
            )
        }
    }

    private fun openEditItem(it: CommandContext<ServerCommandSource>) {
        openNBTGui(
            it.source.player,
            LiteralText("Held Item"),
            NBTMenuState(
                it.source.player.mainHandStack.toNBT(),
                Util.NIL_UUID,
                it.source.player
            )
        ) { state ->
            if (state.suppressOnClose.get().not()) {
                it.source.player.setStackInHand(
                    Hand.MAIN_HAND,
                    ItemStack.fromNbt(state.rootTag)
                )
                it.source.sendFeedback(LiteralText("Saved item data"), false)
            }
        }
    }

    /*

    View Commands

    */

    private fun openViewEntity(it: CommandContext<ServerCommandSource>) {
        val entity = it.getEntity("entity")
            .also { if (it is PlayerEntity) throw EntityArgumentType.NOT_ALLOWED_EXCEPTION.create() }

        val tag = entity.toNBT()

        openNBTGui(
            it.source.player,
            ((if (tag.contains("CustomName")) {
                Text.Serializer.fromJson(tag.getString("CustomName"))
            } else {
                TranslatableText("entity.${EntityType.getId(entity.type).toString().replace(':', '.')}")
            }) ?: LiteralText("[ERROR]")).append(LiteralText(" (VIEW)")),
            NBTMenuState(
                tag,
                Util.NIL_UUID,
                it.source.player
            )
        )
    }

    private fun openViewAdvancements(it: CommandContext<ServerCommandSource>) {
        try {
            val uuid = OfflineNameCache.INSTANCE.getUUIDFromName(
                it.getString("playerData")
            )
            openAdvancementGui(
                it.source.player,
                LiteralText(it.getString("playerData"))
                    .append(LiteralText("'s Advance. (VIEW)")),
                AdvancementMenuState(
                    uuid,
                    it.source.player
                ),
                false
            )
        } catch (npe: NullPointerException) {
            it.source.sendError(
                LiteralText(
                    "Could not load data for that user!"
                ).formatted(Formatting.RED)
            )
        }
    }

    private fun openViewPlayer(it: CommandContext<ServerCommandSource>) {
        try {
            val uuid = OfflineNameCache.INSTANCE.getUUIDFromName(
                it.getString("playerData")
            )
            openNBTGui(
                it.source.player,
                LiteralText(it.getString("playerData"))
                    .append(LiteralText(" (VIEW)")),
                NBTMenuState(
                    OfflineDataCache.INSTANCE.get(uuid).copy(),
                    uuid,
                    it.source.player
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

    private fun openViewBlock(it: CommandContext<ServerCommandSource>) {
        val pos = it.getBlockPos("blockPos")
        val world = it.source.world
        val entity = world.getBlockEntity(pos)
        if (entity != null) {
            val tag = entity.toNBT()
            openNBTGui(
                it.source.player,
                TranslatableText(world.getBlockState(pos).block.translationKey)
                    .append(LiteralText("[${pos.x}, ${pos.y}, ${pos.z}] (VIEW)")),
                NBTMenuState(
                    tag,
                    Util.NIL_UUID,
                    it.source.player
                ),
                false
            )
        } else {
            it.source.sendError(
                LiteralText(
                    "No block entity at $pos"
                ).formatted(Formatting.RED)
            )
        }
    }

    private fun openViewItem(it: CommandContext<ServerCommandSource>) {
        openNBTGui(
            it.source.player,
            LiteralText("Held Item (VIEW)"),
            NBTMenuState(
                it.source.player.mainHandStack.toNBT(),
                Util.NIL_UUID,
                it.source.player
            ),
            false
        )
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
