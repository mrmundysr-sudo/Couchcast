package com.mundymorning.couchcast

import com.mundymorning.couchcast.roku.RokuParse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RokuParseTest {
    @Test
    fun parsesDeviceInfo() {
        val xml = "<device-info><user-device-name>Living Room</user-device-name>" +
            "<model-name>Roku Ultra</model-name><is-tv>true</is-tv><power-mode>PowerOn</power-mode></device-info>"
        val d = RokuParse.deviceInfo(xml)
        assertEquals("Living Room", d.name)
        assertEquals("Roku Ultra", d.model)
        assertTrue(d.isTv)
    }

    @Test
    fun parsesApps() {
        val xml = "<apps><app id=\"12\" type=\"appl\" version=\"4.1\">Netflix</app>" +
            "<app id=\"2285\" type=\"appl\" version=\"1\">Hulu &amp; More</app></apps>"
        val apps = RokuParse.apps(xml)
        assertEquals(2, apps.size)
        assertEquals("12", apps[0].id)
        assertEquals("Hulu & More", apps[1].name)
    }

    @Test
    fun parsesSsdpLocation() {
        val r = "HTTP/1.1 200 OK\r\nST: roku:ecp\r\nLOCATION: http://192.168.1.50:8060/\r\n\r\n"
        assertEquals("192.168.1.50", RokuParse.ssdpLocationHost(r))
    }
}
