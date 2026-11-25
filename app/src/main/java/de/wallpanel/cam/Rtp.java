package de.wallpanel.cam;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/** RFC 6184 H264 RTP and SDP helpers. No Android dependencies. */
public final class Rtp {
    public static final int PT = 96;
    public static final int CLOCK = 90000;

    private Rtp() {}

    public static final class Packet {
        public final byte[] bytes;
        public final int sequence;
        public final boolean marker;

        Packet(byte[] bytes, int sequence, boolean marker) {
            this.bytes = bytes;
            this.sequence = sequence;
            this.marker = marker;
        }

        public byte[] payload() {
            return Arrays.copyOfRange(bytes, 12, bytes.length);
        }
    }

    public static final class Sets {
        public final byte[] sps;
        public final byte[] pps;
        public final List<Packet> packets;

        Sets(byte[] sps, byte[] pps, List<Packet> packets) {
            this.sps = sps;
            this.pps = pps;
            this.packets = packets;
        }
    }

    public static final class Packetizer {
        private int sequence;
        private final int ssrc;
        private final int maxPayload;

        public Packetizer(int ssrc, int maxPayload) {
            if (maxPayload < 3) {
                throw new IllegalArgumentException("maxPayload");
            }
            this.ssrc = ssrc;
            this.maxPayload = maxPayload;
        }

        public int sequence() {
            return sequence & 0xFFFF;
        }

        public Sets absorb(byte[] data, byte[] sps, byte[] pps) {
            byte[] curSps = sps;
            byte[] curPps = pps;
            for (byte[] nal : nalUnits(data)) {
                if (nal.length == 0) {
                    continue;
                }
                int type = nal[0] & 0x1F;
                if (type == 7) {
                    curSps = Arrays.copyOf(nal, nal.length);
                } else if (type == 8) {
                    curPps = Arrays.copyOf(nal, nal.length);
                }
            }
            return new Sets(curSps, curPps, new ArrayList<>());
        }

        public List<Packet> packetizeNal(byte[] nal, long timestamp, boolean marker) {
            List<Packet> out = new ArrayList<>();
            if (nal == null || nal.length == 0) {
                return out;
            }
            if (nal.length <= maxPayload) {
                out.add(packet(nal, timestamp, marker));
                return out;
            }
            int nalType = nal[0] & 0x1F;
            int fuIndicator = (nal[0] & 0x60) | 28;
            int maxFrag = maxPayload - 2;
            int offset = 1;
            while (offset < nal.length) {
                int size = Math.min(maxFrag, nal.length - offset);
                boolean start = offset == 1;
                boolean end = offset + size >= nal.length;
                int fuHeader = (start ? 0x80 : 0) | (end ? 0x40 : 0) | nalType;
                byte[] payload = new byte[2 + size];
                payload[0] = (byte) fuIndicator;
                payload[1] = (byte) fuHeader;
                System.arraycopy(nal, offset, payload, 2, size);
                out.add(packet(payload, timestamp, marker && end));
                offset += size;
            }
            return out;
        }

        /** Sends SPS and PPS before an IDR when this access unit does not already contain them. */
        public Sets packetizeAccessUnit(byte[] data, long timestamp, byte[] sps, byte[] pps) {
            byte[] curSps = sps;
            byte[] curPps = pps;
            List<byte[]> nals = nalUnits(data);
            for (byte[] nal : nals) {
                if (nal.length == 0) {
                    continue;
                }
                int type = nal[0] & 0x1F;
                if (type == 7) {
                    curSps = Arrays.copyOf(nal, nal.length);
                } else if (type == 8) {
                    curPps = Arrays.copyOf(nal, nal.length);
                }
            }
            boolean sentSps = false;
            boolean sentPps = false;
            List<byte[]> send = new ArrayList<>();
            for (byte[] nal : nals) {
                if (nal.length == 0) {
                    continue;
                }
                int type = nal[0] & 0x1F;
                if (type == 9 || type == 10 || type == 11 || type == 12) {
                    continue;
                }
                if (type == 5) {
                    if (!sentSps && curSps != null) {
                        send.add(curSps);
                        sentSps = true;
                    }
                    if (!sentPps && curPps != null) {
                        send.add(curPps);
                        sentPps = true;
                    }
                }
                send.add(nal);
                if (type == 7) {
                    sentSps = true;
                } else if (type == 8) {
                    sentPps = true;
                }
            }
            List<Packet> packets = new ArrayList<>();
            for (int i = 0; i < send.size(); i++) {
                packets.addAll(packetizeNal(send.get(i), timestamp, i == send.size() - 1));
            }
            return new Sets(curSps, curPps, packets);
        }

        private Packet packet(byte[] payload, long timestamp, boolean marker) {
            byte[] bytes = new byte[12 + payload.length];
            bytes[0] = (byte) 0x80;
            bytes[1] = (byte) ((marker ? 0x80 : 0) | PT);
            int seq = sequence & 0xFFFF;
            sequence = (sequence + 1) & 0xFFFF;
            bytes[2] = (byte) ((seq >> 8) & 0xFF);
            bytes[3] = (byte) (seq & 0xFF);
            int ts = (int) timestamp;
            bytes[4] = (byte) ((ts >> 24) & 0xFF);
            bytes[5] = (byte) ((ts >> 16) & 0xFF);
            bytes[6] = (byte) ((ts >> 8) & 0xFF);
            bytes[7] = (byte) (ts & 0xFF);
            bytes[8] = (byte) ((ssrc >> 24) & 0xFF);
            bytes[9] = (byte) ((ssrc >> 16) & 0xFF);
            bytes[10] = (byte) ((ssrc >> 8) & 0xFF);
            bytes[11] = (byte) (ssrc & 0xFF);
            System.arraycopy(payload, 0, bytes, 12, payload.length);
            return new Packet(bytes, seq, marker);
        }
    }

    public static List<byte[]> nalUnits(byte[] data) {
        List<byte[]> out = new ArrayList<>();
        if (data == null || data.length == 0) {
            return out;
        }
        if (looksLikeAvcc(data)) {
            return parseAvcc(data);
        }
        List<Integer> starts = new ArrayList<>();
        List<Integer> headers = new ArrayList<>();
        int i = 0;
        while (i < data.length - 2) {
            if (data[i] == 0 && data[i + 1] == 0) {
                int header;
                if (i + 3 < data.length && data[i + 2] == 0 && data[i + 3] == 1) {
                    header = i + 4;
                } else if (data[i + 2] == 1) {
                    header = i + 3;
                } else {
                    i++;
                    continue;
                }
                starts.add(i);
                headers.add(header);
                i = header;
            } else {
                i++;
            }
        }
        for (int n = 0; n < headers.size(); n++) {
            int begin = headers.get(n);
            int end = n + 1 < starts.size() ? starts.get(n + 1) : data.length;
            if (end > begin) {
                out.add(Arrays.copyOfRange(data, begin, end));
            }
        }
        if (out.isEmpty()) {
            out.add(Arrays.copyOf(data, data.length));
        }
        return out;
    }

    static boolean looksLikeAvcc(byte[] data) {
        return data.length > 7
                && data[0] == 1
                && (data[4] & 0xFC) == 0xFC
                && (data[5] & 0xE0) == 0xE0;
    }

    static List<byte[]> parseAvcc(byte[] avcc) {
        List<byte[]> out = new ArrayList<>();
        int nSps = avcc[5] & 0x1F;
        int offset = 6;
        for (int i = 0; i < nSps && offset + 2 <= avcc.length; i++) {
            int len = ((avcc[offset] & 0xFF) << 8) | (avcc[offset + 1] & 0xFF);
            offset += 2;
            if (len <= 0 || offset + len > avcc.length) {
                return out;
            }
            out.add(Arrays.copyOfRange(avcc, offset, offset + len));
            offset += len;
        }
        if (offset >= avcc.length) {
            return out;
        }
        int nPps = avcc[offset] & 0xFF;
        offset += 1;
        for (int i = 0; i < nPps && offset + 2 <= avcc.length; i++) {
            int len = ((avcc[offset] & 0xFF) << 8) | (avcc[offset + 1] & 0xFF);
            offset += 2;
            if (len <= 0 || offset + len > avcc.length) {
                return out;
            }
            out.add(Arrays.copyOfRange(avcc, offset, offset + len));
            offset += len;
        }
        return out;
    }

    public static String profileLevelId(byte[] sps) {
        if (sps == null || sps.length < 4) {
            return null;
        }
        return String.format(Locale.US, "%02X%02X%02X", sps[1] & 0xFF, sps[2] & 0xFF, sps[3] & 0xFF);
    }

    public static String spropParameterSets(byte[] sps, byte[] pps) {
        Base64.Encoder enc = Base64.getEncoder();
        return enc.encodeToString(sps) + "," + enc.encodeToString(pps);
    }

    public static String sdp(byte[] sps, byte[] pps) {
        StringBuilder fmtp = new StringBuilder("packetization-mode=1");
        String plid = profileLevelId(sps);
        if (plid != null) {
            fmtp.append(";profile-level-id=").append(plid);
        }
        if (sps != null && pps != null && sps.length > 0 && pps.length > 0) {
            fmtp.append(";sprop-parameter-sets=").append(spropParameterSets(sps, pps));
        }
        return "v=0\r\n"
                + "o=- 0 1 IN IP4 0.0.0.0\r\n"
                + "s=Wallpanel\r\n"
                + "t=0 0\r\n"
                + "a=range:npt=0-\r\n"
                + "m=video 0 RTP/AVP 96\r\n"
                + "a=rtpmap:96 H264/90000\r\n"
                + "a=fmtp:96 " + fmtp + "\r\n"
                + "a=control:trackID=0\r\n";
    }
}
