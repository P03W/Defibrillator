/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package mc.defibrillator.gui

import mc.defibrillator.DefibState
import mc.defibrillator.gui.data.AdvancementMenuState
import mc.defibrillator.gui.util.TexturingConstants
import me.basiqueevangelist.nevseti.OfflineAdvancementCache
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.LiteralText
import net.minecraft.text.Text
import net.minecraft.text.TranslatableText
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier
import net.minecraft.util.Util
import org.github.p03w.quecee.api.gui.QueCeeHandlerFactory
import org.github.p03w.quecee.api.gui.inventory.ItemActionMap
import org.github.p03w.quecee.api.util.applySkull
import org.github.p03w.quecee.api.util.guiStack
import org.github.p03w.quecee.api.util.withGlint
import org.github.p03w.quecee.api.util.withLore
import org.github.p03w.quecee.util.GuiAction

class AdvancementScreenHandlerFactory(
    private val player: ServerPlayerEntity,
    private val title: Text,
    menuState: AdvancementMenuState,
    private val allowEditing: Boolean,
    onClose: (AdvancementMenuState) -> Unit
) : QueCeeHandlerFactory<AdvancementMenuState>(
    title,
    6,
    menuState,
    onClose
) {
    init {
        state.factory = this
    }

    override fun getDisplayName(): Text {
        return title
    }

    @OptIn(ExperimentalStdlibApi::class)
    override fun generateActionMap(state: AdvancementMenuState): ItemActionMap<AdvancementMenuState> {
        val cache = OfflineAdvancementCache.INSTANCE.get(state.targetUUID)
        return ItemActionMap {
            val all = player.server.advancementLoader.advancements.filter { !it.id.path.startsWith("recipe") }
            val complete = buildList<Pair<Identifier, Boolean>> {
                all.forEach {
                    val defaultState = cache[it.id]?.isDone ?: false
                    val trueState = state.overrides[it.id] ?: defaultState
                    add(Pair(it.id, trueState))
                }
            }

            state.size = complete.size

            // Last page if not on first page
            if (state.page > 0) {
                addEntry(
                    0,
                    Items.PLAYER_HEAD.guiStack("Last Page").applySkull(
                        TexturingConstants.BACK_TEXTURE,
                        TexturingConstants.BACK_ID
                    )
                ) { _, _ ->
                    state.page -= 1
                    rebuild()
                }
            }

            // Info
            val info = listOf("Current page: ${state.page}", "Entries: ${state.size}")
            addEntry(
                4, Items.PLAYER_HEAD
                    .guiStack("Info", Formatting.GOLD)
                    .applySkull(TexturingConstants.INFO_TEXTURE, TexturingConstants.INFO_ID)
                    .withLore(info)
            ) { _, _ -> }

            // Cancel button
            addEntry(5, Items.BARRIER.guiStack("Cancel changes (Right Click)")) { data, _ ->
                if (data == 1) {
                    state.suppressOnClose.set(true)
                    player.closeHandledScreen()
                    player.sendSystemMessage(LiteralText("Discarded changes"), Util.NIL_UUID)
                    DefibState.activeAdvancementSessions.remove(state.player.uuid)
                    state.suppressOnClose.set(false)
                }
            }

            // Next page if more pages to go to
            if (state.size - ((state.page + 1) * AdvancementMenuState.PER_PAGE) > 0) {
                addEntry(
                    8,
                    Items.PLAYER_HEAD.guiStack("Next Page").applySkull(
                        TexturingConstants.NEXT_TEXTURE,
                        TexturingConstants.NEXT_ID
                    )
                ) { _, _ ->
                    state.page += 1
                    rebuild()
                }
            }

            // Add all the tags
            var index = 9
            try {
                for (entry in (state.page * AdvancementMenuState.PER_PAGE) until ((state.page + 1) * AdvancementMenuState.PER_PAGE)) {
                    val possible = complete[entry]
                    val converted = advancementToGuiEntry(possible)
                    addEntry(index++, converted.first, converted.second)
                }
            } catch (ignored: IndexOutOfBoundsException) {
            }
        }
    }

    private fun advancementToGuiEntry(advancement: Pair<Identifier, Boolean>): Pair<ItemStack, GuiAction<AdvancementMenuState>> {
        val namespace = "advancements.${advancement.first.path.replace('/', '.')}"
        return Pair(
            Items.PAPER
                .guiStack(
                    TranslatableText("$namespace.title"),
                    if (advancement.second) Formatting.GREEN else Formatting.RED
                )
                .withGlint(advancement.second)
        ) { _: Int, state: AdvancementMenuState ->
            if (allowEditing) {
                state.overrides[advancement.first] = advancement.second.not()
                state.factory.rebuild()
            }
        }
    }
}
