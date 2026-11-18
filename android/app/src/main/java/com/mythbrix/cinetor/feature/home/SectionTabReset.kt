package com.mythbrix.cinetor.feature.home

import com.mythbrix.cinetor.core.data.Space
import java.util.Collections

/**
 * Sections whose category tab snaps back to the first one (`All` / `For You`) the next time they
 * are shown. Requested when the user switches in from a different section; returning from Details
 * or Search inside the same section keeps the chosen tab.
 */
object SectionTabReset {
    private val pending: MutableSet<Space> = Collections.synchronizedSet(mutableSetOf())

    fun request(space: Space) {
        pending += space
    }

    /** True once per request: the caller resets the tab. */
    fun consume(space: Space): Boolean = pending.remove(space)
}
