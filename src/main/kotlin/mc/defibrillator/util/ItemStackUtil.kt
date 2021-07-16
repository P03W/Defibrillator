/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package mc.defibrillator.util

import mc.defibrillator.gui.util.TexturingConstants
import net.minecraft.item.ItemStack
import org.github.p03w.quecee.api.util.applySkull

/**
 * [applySkull] but hardcoded to the hashtag
 */
fun ItemStack.asHashtag(): ItemStack {
    this.applySkull(
        TexturingConstants.HASH_TEXTURE,
        TexturingConstants.HASH_ID
    )
    return this
}
