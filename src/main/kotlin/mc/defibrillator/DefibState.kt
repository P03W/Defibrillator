/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package mc.defibrillator

import it.unimi.dsi.fastutil.longs.Long2IntArrayMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import mc.defibrillator.gui.data.AdvancementMenuState
import mc.defibrillator.gui.data.NBTMenuState
import net.minecraft.entity.Entity
import net.minecraft.server.network.ServerPlayerEntity
import org.github.p03w.quecee.util.DualHashMap
import java.util.*

object DefibState {
    // A map of players we need input from and what method to call once we get input
    @JvmField
    val awaitingInput: HashMap<ServerPlayerEntity, (String) -> Unit> = hashMapOf()

    // Active sessions to prevent overwriting
    @JvmField
    val activeNBTSessions: DualHashMap<UUID, ServerPlayerEntity, NBTMenuState> = DualHashMap()
    val activeAdvancementSessions: DualHashMap<UUID, ServerPlayerEntity, AdvancementMenuState> = DualHashMap()

    // Scope for coroutine management
    val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default)

    // TODO: Replace Boolean with a chunk reference
    val activeChunkSessions: DualHashMap<UUID, Boolean, MutableList<UUID>> = DualHashMap()

    // Frozen entities
    @JvmField
    val suppressedEntities: HashMap<Entity, Int> = hashMapOf()
    @JvmField
    val suppressedBlockEntities = Long2IntArrayMap()
}
