package de.wallpanel.cam;

import org.junit.Test;

import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class StreamFormatTest {
    @Test
    public void parsesOnAndOff() {
        assertEquals(Boolean.TRUE, Payloads.parseOnOff("ON"));
        assertEquals(Boolean.TRUE, Payloads.parseOnOff("on"));
        assertEquals(Boolean.TRUE, Payloads.parseOnOff("o"));
        assertEquals(Boolean.TRUE, Payloads.parseOnOff("1"));
        assertEquals(Boolean.TRUE, Payloads.parseOnOff("true"));
        assertEquals(Boolean.TRUE, Payloads.parseOnOff("an"));
        assertEquals(Boolean.TRUE, Payloads.parseOnOff(" ein "));
        assertEquals(Boolean.FALSE, Payloads.parseOnOff("OFF"));
        assertEquals(Boolean.FALSE, Payloads.parseOnOff("c"));
        assertEquals(Boolean.FALSE, Payloads.parseOnOff("0"));
        assertEquals(Boolean.FALSE, Payloads.parseOnOff("false"));
        assertEquals(Boolean.FALSE, Payloads.parseOnOff("aus"));
        assertNull(Payloads.parseOnOff(""));
        assertNull(Payloads.parseOnOff("maybe"));
        assertNull(Payloads.parseOnOff(null));
    }

    @Test
    public void discoveryUsesCameraTopics() {
        String json = Payloads.discovery("1.0");
        assertTrue(json.contains("\"name\":null"));
        assertTrue(json.contains("\"name\":\"Wallpanel Kamera\""));
        assertTrue(json.contains("\"unique_id\":\"rk3566_wallpanel_kamera\""));
        assertTrue(json.contains("\"object_id\":\"wallpanel_kamera\""));
        assertTrue(json.contains("\"default_entity_id\":\"switch.wallpanel_kamera\""));
        assertTrue(json.contains("\"command_topic\":\"wallpanel/camera/set\""));
        assertTrue(json.contains("\"state_topic\":\"wallpanel/camera/state\""));
        assertTrue(json.contains("\"availability_topic\":\"wallpanel/camera/status\""));
        assertTrue(json.contains("\"payload_on\":\"ON\""));
        assertTrue(json.contains("\"payload_off\":\"OFF\""));
        assertTrue(json.contains("\"state_on\":\"ON\""));
        assertTrue(json.contains("\"state_off\":\"OFF\""));
        assertTrue(json.contains("\"payload_available\":\"online\""));
        assertTrue(json.contains("\"payload_not_available\":\"offline\""));
        assertTrue(json.contains("\"qos\":1"));
        assertTrue(json.contains("\"retain\":true"));
        assertTrue(json.contains("\"icon\":\"mdi:camera\""));
        assertTrue(json.contains("\"identifiers\":[\"rk3566-wallpanel-camera\"]"));
        assertTrue(json.contains("\"model\":\"rk3566\""));
        assertTrue(json.contains("\"manufacturer\":\"Rockchip\""));
        assertFalse(json.contains("wallpanel/status\""));
    }

    @Test
    public void spropParameterSetsRoundTrip() {
        byte[] sps = new byte[] {0x67, 0x42, (byte) 0xC0, 0x1F, (byte) 0xDA, 0x01};
        byte[] pps = new byte[] {0x68, (byte) 0xCE, 0x38, (byte) 0x80};
        String sprop = Rtp.spropParameterSets(sps, pps);
        String[] parts = sprop.split(",");
        assertEquals(2, parts.length);
        assertArrayEquals(sps, Base64.getDecoder().decode(parts[0]));
        assertArrayEquals(pps, Base64.getDecoder().decode(parts[1]));
        assertEquals("42C01F", Rtp.profileLevelId(sps));
        String sdp = Rtp.sdp(sps, pps);
        assertTrue(sdp.contains("a=rtpmap:96 H264/90000"));
        assertTrue(sdp.contains("packetization-mode=1"));
        assertTrue(sdp.contains("profile-level-id=42C01F"));
        assertTrue(sdp.contains("sprop-parameter-sets=" + sprop));
        assertFalse(sdp.contains("a=sendonly"));
        assertFalse(sdp.contains("a=recvonly"));
        assertFalse(sprop.contains(" "));
        assertFalse(parts[0].contains("\n"));
    }

    @Test
    public void fuAFragmentsAndReassembles() {
        byte[] nal = new byte[50];
        nal[0] = 0x65;
        for (int i = 1; i < nal.length; i++) {
            nal[i] = (byte) (i + 3);
        }
        Rtp.Packetizer packetizer = new Rtp.Packetizer(0x11223344, 20);
        List<Rtp.Packet> packets = packetizer.packetizeNal(nal, 4500, true);
        assertEquals(3, packets.size());
        byte[] body = new byte[49];
        int filled = 0;
        int previous = -1;
        for (int i = 0; i < packets.size(); i++) {
            Rtp.Packet packet = packets.get(i);
            assertEquals(0x80, packet.bytes[0] & 0xFF);
            assertEquals(96, packet.bytes[1] & 0x7F);
            assertEquals(i == packets.size() - 1, packet.marker);
            assertTrue(packet.sequence > previous);
            previous = packet.sequence;
            byte[] payload = packet.payload();
            assertEquals(28, payload[0] & 0x1F);
            assertEquals(0x60, payload[0] & 0xE0);
            int fu = payload[1] & 0xFF;
            assertEquals(5, fu & 0x1F);
            assertEquals(i == 0, (fu & 0x80) != 0);
            assertEquals(i == packets.size() - 1, (fu & 0x40) != 0);
            int size = payload.length - 2;
            System.arraycopy(payload, 2, body, filled, size);
            filled += size;
        }
        assertEquals(49, filled);
        assertArrayEquals(Arrays.copyOfRange(nal, 1, nal.length), body);
    }

    @Test
    public void smallNalStaysSingleAndIdrIsPrefixed() {
        byte[] slice = new byte[] {0x41, 0x01, 0x02, 0x03};
        Rtp.Packetizer single = new Rtp.Packetizer(7, 1200);
        List<Rtp.Packet> one = single.packetizeNal(slice, 90000, true);
        assertEquals(1, one.size());
        assertArrayEquals(slice, one.get(0).payload());
        assertTrue(one.get(0).marker);
        assertEquals(1, one.get(0).payload()[0] & 0x1F);

        byte[] sps = new byte[] {0x67, 0x42, (byte) 0xC0, 0x1F};
        byte[] pps = new byte[] {0x68, (byte) 0xCE, 0x38, (byte) 0x80};
        byte[] idr = new byte[] {0x65, 0x11, 0x22};
        byte[] au = annex(idr);
        Rtp.Packetizer packetizer = new Rtp.Packetizer(9, 1200);
        Rtp.Sets sets = packetizer.packetizeAccessUnit(au, 4500, sps, pps);
        assertEquals(3, sets.packets.size());
        assertEquals(7, nalType(sets.packets.get(0)));
        assertEquals(8, nalType(sets.packets.get(1)));
        assertEquals(5, nalType(sets.packets.get(2)));
        assertFalse(sets.packets.get(0).marker);
        assertFalse(sets.packets.get(1).marker);
        assertTrue(sets.packets.get(2).marker);
        assertArrayEquals(sps, sets.packets.get(0).payload());
        assertArrayEquals(pps, sets.packets.get(1).payload());
        assertArrayEquals(idr, sets.packets.get(2).payload());
    }

    private static int nalType(Rtp.Packet packet) {
        byte[] payload = packet.payload();
        int type = payload[0] & 0x1F;
        if (type == 28) {
            return payload[1] & 0x1F;
        }
        return type;
    }

    private static byte[] annex(byte[]... nals) {
        int size = 0;
        for (byte[] nal : nals) {
            size += 4 + nal.length;
        }
        byte[] out = new byte[size];
        int pos = 0;
        for (byte[] nal : nals) {
            out[pos + 2] = 0;
            out[pos + 3] = 1;
            System.arraycopy(nal, 0, out, pos + 4, nal.length);
            pos += 4 + nal.length;
        }
        return out;
    }
}
