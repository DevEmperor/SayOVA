package net.devemperor.parley.core

import net.devemperor.parley.core.phone.PhoneLinks
import net.devemperor.parley.core.phone.PhoneLinks.When
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class PhoneLinksTest {

    @Test
    fun `numbers with a country code become WhatsApp's digits`() {
        assertEquals("491712345678", PhoneLinks.internationalDigits("+49 171 2345678"))
        assertEquals("491712345678", PhoneLinks.internationalDigits("0049 (171) 234-56-78"))
        assertNull(PhoneLinks.internationalDigits("0171 2345678"))
        assertNull(PhoneLinks.internationalDigits("+49 12"))
    }

    @Test
    fun `message texts are encoded with spaces as %20`() {
        assertEquals("https://wa.me/491712345678?text=Bin%20gleich%20da%20%26%20bringe%20Brot%20mit", PhoneLinks.whatsApp("491712345678", "Bin gleich da & bringe Brot mit"))
        assertEquals("https://wa.me/?text=Hallo%21", PhoneLinks.whatsApp(null, "Hallo!"))
        assertEquals("sms:+491712345678?body=10%20Minuten%20sp%C3%A4ter", PhoneLinks.sms("+49 171 2345678", "10 Minuten später"))
        assertEquals("sms:?body=Hi", PhoneLinks.sms(null, "Hi"))
    }

    @Test
    fun `an e-mail keeps a valid address and drops a wrong one`() {
        assertEquals("mailto:lena@example.org?subject=Morgen&body=Um%2010%3F", PhoneLinks.email("lena@example.org", "Morgen", "Um 10?"))
        assertEquals("mailto:?body=Hallo", PhoneLinks.email("Lena", null, "Hallo"))
        assertTrue(PhoneLinks.isEmailAddress("a.b@c.de"))
        assertFalse(PhoneLinks.isEmailAddress("a b@c.de"))
    }

    @Test
    fun `calendar events last an hour or the day by default`() {
        val zone = ZoneId.of("Europe/Berlin")
        val start = PhoneLinks.parseWhen("2026-11-02 15:00")!!
        assertEquals(When.Time(LocalDateTime.of(2026, 11, 2, 15, 0)), start)
        val timed = PhoneLinks.calendarEvent("Zahnarzt", start, null, "Hauptstraße 1", null, zone)
        assertEquals(
            "https://calendar.google.com/calendar/render?action=TEMPLATE&text=Zahnarzt&dates=20261102T150000/20261102T160000" +
                "&ctz=Europe%2FBerlin&location=Hauptstra%C3%9Fe%201",
            timed,
        )
        // An end before the start is ignored.
        assertTrue("dates=20261102T150000/20261102T160000" in PhoneLinks.calendarEvent("A", start, PhoneLinks.parseWhen("2026-11-02T14:00"), null, null, zone))
        val day = PhoneLinks.parseWhen("2026-12-24")!!
        assertEquals(When.Day(LocalDate.of(2026, 12, 24)), day)
        assertTrue("dates=20261224/20261225" in PhoneLinks.calendarEvent("Heiligabend", day, null, null, null, zone))
        assertTrue("dates=20261224/20261227" in PhoneLinks.calendarEvent("Feiertage", day, PhoneLinks.parseWhen("2026-12-26"), null, null, zone))
        assertNull(PhoneLinks.parseWhen("next Monday"))
    }

    @Test
    fun `routes take a known travel mode only`() {
        assertEquals("https://www.google.com/maps/dir/?api=1&destination=K%C3%B6ln%20Hbf&travelmode=walking", PhoneLinks.route("Köln Hbf", "Walking"))
        assertEquals("https://www.google.com/maps/dir/?api=1&destination=Berlin", PhoneLinks.route("Berlin", "teleport"))
    }
}
