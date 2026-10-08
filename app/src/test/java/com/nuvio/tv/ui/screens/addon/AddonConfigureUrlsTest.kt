package com.nuvio.tv.ui.screens.addon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AddonConfigureUrlsTest {

    @Test
    fun `configure url keeps the current settings path`() {
        assertEquals(
            "https://torrentio.strem.fun/providers=yts|sort=quality/configure",
            addonConfigureUrl("https://torrentio.strem.fun/providers=yts|sort=quality")
        )
        assertEquals(
            "https://comet.elfhosted.com/eyJrZXkiOiJ4In0/configure",
            addonConfigureUrl("https://comet.elfhosted.com/eyJrZXkiOiJ4In0/manifest.json")
        )
        assertEquals("https://example.com/configure", addonConfigureUrl("https://example.com/"))
    }

    @Test
    fun `detects install links from configure pages`() {
        assertEquals(
            "stremio://torrentio.strem.fun/realdebrid=KEY/manifest.json",
            extractManifestUrl("stremio://torrentio.strem.fun/realdebrid=KEY/manifest.json")
        )
        assertEquals(
            "https://comet.elfhosted.com/abc/manifest.json",
            extractManifestUrl("  https://comet.elfhosted.com/abc/manifest.json ")
        )
        assertEquals(
            "https://addon.example/cfg/manifest.json?x=1",
            extractManifestUrl("https://addon.example/cfg/manifest.json?x=1")
        )
    }

    @Test
    fun `ignores ordinary pages and other schemes`() {
        assertNull(extractManifestUrl("https://torrentio.strem.fun/configure"))
        assertNull(extractManifestUrl("https://example.com/manifest.json.html"))
        assertNull(extractManifestUrl("intent://scan/#Intent;scheme=zxing;end"))
        assertNull(extractManifestUrl("copied text"))
    }
}
