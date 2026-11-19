package com.wanderwildwood.yumecho.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** The server is shown one way, for Home's account and one signed in here alike. */
class HostOfTest {
    @Test fun schemeAndSlashGo() {
        assertEquals("cloud.example.org", hostOf("https://cloud.example.org/"))
        assertEquals("10.0.2.2:8080", hostOf("http://10.0.2.2:8080"))
    }

    @Test fun defaultPortGoesOtherStays() {
        assertEquals("cloud.example.org", hostOf("https://cloud.example.org:443"))
        assertEquals("cloud.example.org", hostOf("http://cloud.example.org:80/"))
        assertEquals("cloud.example.org:8443", hostOf("https://Cloud.Example.org:8443"))
    }

    @Test fun pathStaysAndEmptyIsEmpty() {
        assertEquals("example.org/nextcloud", hostOf("https://example.org/nextcloud/"))
        assertEquals("", hostOf(null))
        assertEquals("cloud.example.org", hostOf("cloud.example.org"))
    }
}
