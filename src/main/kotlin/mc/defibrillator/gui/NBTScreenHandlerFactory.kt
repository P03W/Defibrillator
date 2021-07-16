/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package mc.defibrillator.gui

import mc.defibrillator.DefibState
import mc.defibrillator.Defibrillator
import mc.defibrillator.exception.InvalidArgument
import mc.defibrillator.gui.data.NBTMenuState
import mc.defibrillator.gui.data.RightClickMode
import mc.defibrillator.gui.util.TexturingConstants
import mc.defibrillator.gui.util.getTextEntry
import mc.defibrillator.gui.util.toGuiEntry
import mc.defibrillator.util.*
import net.fabricmc.fabric.api.util.NbtType
import net.minecraft.item.Items
import net.minecraft.nbt.*
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.LiteralText
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Util
import org.github.p03w.quecee.api.gui.QueCeeHandlerFactory
import org.github.p03w.quecee.api.gui.inventory.ItemActionMap
import org.github.p03w.quecee.api.util.applySkull
import org.github.p03w.quecee.api.util.guiStack
import org.github.p03w.quecee.api.util.withGlint
import org.github.p03w.quecee.api.util.withLore
import kotlin.time.ExperimentalTime

class NBTScreenHandlerFactory(
    private val player: ServerPlayerEntity,
    private val title: Text,
    menuState: NBTMenuState,
    private val allowEditing: Boolean,
    onClose: (NBTMenuState) -> Unit
) : QueCeeHandlerFactory<NBTMenuState>(
    title,
    6,
    menuState,
    onClose
) {
    init {
        menuState.factory = this
    }

    override fun getDisplayName(): Text {
        return title
    }

    @ExperimentalTime
    fun makeNBTViewer(
        state: NBTMenuState
    ): ItemActionMap<NBTMenuState> {
        val actionMap = ItemActionMap<NBTMenuState> {
            // Previous page if not on first page
            if (state.page > 0) {
                addEntry(
                    0,
                    Items.PLAYER_HEAD.guiStack("Last Page")
                        .applySkull(TexturingConstants.BACK_TEXTURE, TexturingConstants.BACK_ID)
                ) { _, _ ->
                    state.page -= 1
                    rebuild()
                }
            }

            // Delete option
            when (state.clickMode) {
                RightClickMode.PASS -> addEntry(2, Items.GLASS.guiStack("Right Click: None")) { _, _ ->
                    state.clickMode = RightClickMode.COPY
                    rebuild()
                }
                RightClickMode.COPY -> addEntry(2, Items.EMERALD.guiStack("Right Click: Copy")) { _, _ ->
                    state.clickMode = RightClickMode.DELETE
                    rebuild()
                }
                RightClickMode.DELETE -> addEntry(
                    2,
                    Items.TNT.guiStack("Right Click: Delete", Formatting.RED).withGlint()
                ) { _, _ ->
                    state.clickMode = RightClickMode.PASS
                    rebuild()
                }
            }

            // Up / parent
            addEntry(
                3,
                Items.PLAYER_HEAD.guiStack("Up/Parent")
                    .applySkull(TexturingConstants.OUT_TEXTURE, TexturingConstants.OUT_ID)
            ) { _, _ ->
                state.keyStack.removeLastOrNull()
                state.page = 0
                rebuild()
            }

            // Info
            val info = (listOf(
                "Current page: ${state.page}",
                "Entries: ${state.getAvailableKeys().size}",
                "Path:",
                "  <ROOT>"
            ) union (state.keyStack.map { "  $it" })).toList()
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
                    DefibState.activeNBTSessions.remove(state.playerUUID)
                    state.suppressOnClose.set(false)
                }
            }

            // Add entry
            if (allowEditing) {
                addEntry(
                    6,
                    Items.PLAYER_HEAD.guiStack("Add Tag/Entry")
                        .applySkull(TexturingConstants.PLUS_TEXTURE, TexturingConstants.PLUS_ID)
                ) { _, _ ->
                    state.isInAddMenu = true
                    rebuild()
                }
            }

            // Next page if more pages to go to
            if (state.getAvailableKeys().size - ((state.page + 1) * NBTMenuState.PER_PAGE) > 0) {
                addEntry(
                    8,
                    Items.PLAYER_HEAD.guiStack("Next Page")
                        .applySkull(TexturingConstants.NEXT_TEXTURE, TexturingConstants.NEXT_ID)
                ) { _, _ ->
                    state.page += 1
                    rebuild()
                }
            }

            // Add all the tags
            var index = 9
            try {
                for (entry in (state.page * NBTMenuState.PER_PAGE) until ((state.page + 1) * NBTMenuState.PER_PAGE)) {
                    val possible = state.getAvailableKeys()[entry]
                    try {
                        val converted = state.getActiveTag().retrieve(possible)!!.toGuiEntry(possible)
                        addEntry(index++, converted.first, converted.second)
                    } catch (ignored: NotImplementedError) {
                        println(
                            "Not implemented: $possible ${
                                state.getActiveTag().retrieve(possible)!!::class.java
                            }"
                        )
                    }
                }
            } catch (ignored: IndexOutOfBoundsException) {
            }
        }

        state.isInAddMenu = false
        return actionMap
    }

    @ExperimentalTime
    private fun makeNBTAdder(
        state: NBTMenuState
    ): ItemActionMap<NBTMenuState> {
        val actionMap = ItemActionMap<NBTMenuState> {
            // Cancel
            addEntry(
                0,
                Items.PLAYER_HEAD.guiStack("Cancel")
                    .applySkull(TexturingConstants.OUT_TEXTURE, TexturingConstants.OUT_ID)
            ) { _, _ ->
                state.page = 0
                state.isInAddMenu = false
                rebuild()
            }

            fun punchInAndExit(tag: NbtElement, name: String, state: NBTMenuState) {
                when (val active = state.getActiveTag()) {
                    is NbtCompound -> active.put(name, tag)
                    is AbstractNbtList<*> -> {
                        if (active.heldType == tag.type || active.heldType == 0.toByte()) {
                            try {
                                val index = name.toInt()
                                active.setElement(index, tag)
                            } catch (ignored: Throwable) {
                                active.setElement(active.size, tag)
                            }
                        }
                    }
                }
                state.page = 0
                state.isInAddMenu = false
                rebuild()
                player.openHandledScreen(this@NBTScreenHandlerFactory)
            }

            var index = 9

            val active = state.getActiveTag()

            fun canAdd(nbtType: Int): Boolean {
                return if (active is AbstractNbtList<*>) {
                    active.wouldAccept(nbtType)
                } else {
                    true
                }
            }

            if (canAdd(NbtType.COMPOUND))
                addEntry(index++, Items.SHULKER_BOX.guiStack("Compound Tag")) { _, composite ->
                    getTextEntry(state, "compound name") {
                        punchInAndExit(NbtCompound(), it, composite)
                    }
                }

            if (canAdd(NbtType.LIST))
                addEntry(index++, Items.WRITABLE_BOOK.guiStack("List Tag")) { _, composite ->
                    getTextEntry(composite, "list name") {
                        punchInAndExit(NbtList(), it, composite)
                    }
                }

            if (canAdd(NbtType.INT_ARRAY))
                addEntry(index++, Items.WRITABLE_BOOK.guiStack("Int Array Tag")) { _, composite ->
                    getTextEntry(composite, "int array name") {
                        punchInAndExit(NbtIntArray(listOf()), it, composite)
                    }
                }

            if (canAdd(NbtType.BYTE_ARRAY))
                addEntry(index++, Items.WRITABLE_BOOK.guiStack("Byte Array Tag")) { _, composite ->
                    getTextEntry(composite, "byte array name") {
                        punchInAndExit(NbtByteArray(listOf()), it, composite)
                    }
                }

            if (canAdd(NbtType.LONG_ARRAY))
                addEntry(index++, Items.WRITABLE_BOOK.guiStack("Long Array Tag")) { _, composite ->
                    getTextEntry(composite, "long array name") {
                        punchInAndExit(NbtLongArray(listOf()), it, composite)
                    }
                }

            // Give a generic number option if multiple would be supported
            // And enabled in config
            if (canAdd(NbtType.END) && Defibrillator.config.collapseNumberOptions) {
                addEntry(index++, Items.PLAYER_HEAD.guiStack("Number").asHashtag()) { _, composite ->
                    getDoubleTextEntry(composite, "number value") { name, value ->
                        punchInAndExit(convertEntryToNumberTag(value), name, composite)
                    }
                }
            } else {
                if (canAdd(NbtType.BYTE))
                    addEntry(index++, Items.PLAYER_HEAD.guiStack("Byte").asHashtag()) { _, composite ->
                        getDoubleTextEntry(composite, "byte value") { name, value ->
                            punchInAndExit(NbtByte.of(value.toInt().toByte()), name, composite)
                        }
                    }

                if (canAdd(NbtType.FLOAT))
                    addEntry(index++, Items.PLAYER_HEAD.guiStack("Float").asHashtag()) { _, composite ->
                        getDoubleTextEntry(composite, "float value") { name, value ->
                            punchInAndExit(NbtFloat.of(value.toFloat()), name, composite)
                        }
                    }

                if (canAdd(NbtType.DOUBLE))
                    addEntry(index++, Items.PLAYER_HEAD.guiStack("Double").asHashtag()) { _, composite ->
                        getDoubleTextEntry(composite, "double value") { name, value ->
                            punchInAndExit(NbtDouble.of(value.toDouble()), name, composite)
                        }
                    }

                if (canAdd(NbtType.INT))
                    addEntry(index++, Items.PLAYER_HEAD.guiStack("Int").asHashtag()) { _, composite ->
                        getDoubleTextEntry(composite, "integer value") { name, value ->
                            punchInAndExit(NbtInt.of(value.toInt()), name, composite)
                        }
                    }

                if (canAdd(NbtType.LONG))
                    addEntry(index++, Items.PLAYER_HEAD.guiStack("Long").asHashtag()) { _, composite ->
                        getDoubleTextEntry(composite, "long value") { name, value ->
                            punchInAndExit(NbtLong.of(value.toLong()), name, composite)
                        }
                    }

                if (canAdd(NbtType.SHORT))
                    addEntry(index++, Items.PLAYER_HEAD.guiStack("Short").asHashtag()) { _, composite ->
                        getDoubleTextEntry(composite, "short value") { name, value ->
                            punchInAndExit(NbtShort.of(value.toShort()), name, composite)
                        }
                    }
            }

            if (canAdd(NbtType.STRING))
                addEntry(index, Items.PAPER.guiStack("String")) { _, composite ->
                    getDoubleTextEntry(composite, "string value") { name, value ->
                        punchInAndExit(NbtString.of(value), name, composite)
                    }
                }
        }
        state.isInAddMenu = true
        return actionMap
    }


    /**
     * Gets 2 text entries from the player
     *
     * TODO: This is very hardcoded
     */
    @ExperimentalTime
    private fun getDoubleTextEntry(
        state: NBTMenuState,
        value2For: String,
        onSuccess: (String, String) -> Unit
    ) {
        getTextEntry(state, "tag name/index") { value1 ->
            getTextEntry(state, value2For) { value2 ->
                try {
                    onSuccess(value1, value2)
                } catch (err: Throwable) {
                    player.sendMessage(
                        LiteralText("Failed to parse and/or handle!").formatted(Formatting.RED),
                        false
                    )
                    err.printStackTrace()
                }
            }
        }
    }

    companion object {
        private fun convertEntryToNumberTag(input: String): AbstractNbtNumber {
            try {
                val value = input.cleanNumber()

                // Decimal, default to double if not specified
                if (input.contains('.')) {
                    if (input.endsWith('F', true)) {
                        return NbtFloat.of(value.toFloat())
                    }
                    return NbtDouble.of(value.toDouble())
                }

                // Fixed number, default to int
                if (input.endsWith("L", true)) {
                    return NbtLong.of(value.toLong())
                }

                if (input.endsWith("B", true)) {
                    return NbtByte.of(value.toByte())
                }

                if (input.endsWith("S", true)) {
                    return NbtShort.of(value.toShort())
                }

                return NbtInt.of(value.toInt())
            } catch (err: Throwable) {
                err.printStackTrace()
                throw InvalidArgument()
            }
        }
    }

    /**
     * Makes a different map depending on state
     */
    @OptIn(ExperimentalTime::class)
    override fun generateActionMap(state: NBTMenuState): ItemActionMap<NBTMenuState> {
        return if (state.isInAddMenu) {
            makeNBTAdder(state)
        } else {
            makeNBTViewer(state)
        }
    }
}
