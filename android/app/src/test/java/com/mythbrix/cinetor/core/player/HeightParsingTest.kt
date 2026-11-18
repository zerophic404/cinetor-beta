package com.mythbrix.cinetor.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeightParsingTest {

    @Test
    fun digits_win() {
        assertEquals(1080, GatewaySourceResolver.heightOf("1080p"))
        assertEquals(2160, GatewaySourceResolver.heightOf("2160P"))
        assertEquals(720, GatewaySourceResolver.heightOf(" 720 "))
    }

    @Test
    fun namedTiers_mapToTheirHeights() {
        assertEquals(2160, GatewaySourceResolver.heightOf("4K"))
        assertEquals(2160, GatewaySourceResolver.heightOf("4k UHD"))
        assertEquals(2160, GatewaySourceResolver.heightOf("Ultra HD"))
        assertEquals(1440, GatewaySourceResolver.heightOf("2K"))
        assertEquals(1080, GatewaySourceResolver.heightOf("FHD"))
        assertEquals(720, GatewaySourceResolver.heightOf("HD"))
    }

    @Test
    fun unknownLabels_haveNoHeight() {
        assertNull(GatewaySourceResolver.heightOf(null))
        assertNull(GatewaySourceResolver.heightOf(""))
        assertNull(GatewaySourceResolver.heightOf("Auto"))
    }
}
