package me.bsdnme.tvbox.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DlnaTest {
    @Test fun findsEmbeddedRendererWithoutUsingParentDeviceName() {
        val description = """
            <root xmlns="urn:schemas-upnp-org:device-1-0"><URLBase>http://192.168.1.2:8080/base/</URLBase>
              <device><deviceType>urn:schemas-upnp-org:device:Basic:1</deviceType><friendlyName>Parent</friendlyName>
                <deviceList><device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
                  <friendlyName>Living Room</friendlyName><serviceList><service>
                    <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>control</controlURL>
                  </service></serviceList></device></deviceList>
              </device></root>
        """.trimIndent()
        assertEquals(CastDevice("Living Room", "http://192.168.1.2:8080/base/control", "urn:schemas-upnp-org:service:AVTransport:1"), Dlna.parseDeviceDescription("http://192.168.1.2/device.xml", description))
    }

    @Test fun ignoresNonRendererDevices() {
        val description = """<root><device><deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>
          <serviceList><service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
          <controlURL>/control</controlURL></service></serviceList></device></root>"""
        assertNull(Dlna.parseDeviceDescription("http://192.168.1.2/device.xml", description))
    }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsDeviceDescriptionsWithEntityDeclarations() {
        Dlna.parseDeviceDescription("http://192.168.1.2/device.xml", """<!DOCTYPE root [<!ENTITY x SYSTEM "file:///etc/passwd">]><root>&x;</root>""")
    }
}
