package de.wallpanel.cam;

import java.util.Locale;

/** MQTT payloads and Home Assistant discovery. No Android dependencies. */
public final class Payloads {
    public static final String COMMAND = "wallpanel/camera/set";
    public static final String STATE = "wallpanel/camera/state";
    /** Not the LED app topic wallpanel/status. */
    public static final String AVAILABILITY = "wallpanel/camera/status";
    public static final String DISCOVERY = "homeassistant/switch/wallpanel_kamera/config";

    private Payloads() {}

    public static Boolean parseOnOff(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "on", "o", "1", "true", "an", "ein" -> true;
            case "off", "c", "0", "false", "aus" -> false;
            default -> null;
        };
    }

    public static String statePayload(boolean on) {
        return on ? "ON" : "OFF";
    }

    public static String discovery(String version) {
        return "{"
                + "\"name\":null"
                + ",\"unique_id\":\"rk3566_wallpanel_kamera\""
                + ",\"object_id\":\"wallpanel_kamera\""
                + ",\"default_entity_id\":\"switch.wallpanel_kamera\""
                + ",\"command_topic\":\"" + COMMAND + "\""
                + ",\"state_topic\":\"" + STATE + "\""
                + ",\"availability_topic\":\"" + AVAILABILITY + "\""
                + ",\"payload_on\":\"ON\""
                + ",\"payload_off\":\"OFF\""
                + ",\"state_on\":\"ON\""
                + ",\"state_off\":\"OFF\""
                + ",\"payload_available\":\"online\""
                + ",\"payload_not_available\":\"offline\""
                + ",\"qos\":1"
                + ",\"retain\":true"
                + ",\"icon\":\"mdi:camera\""
                + ",\"device\":{"
                + "\"identifiers\":[\"rk3566-wallpanel-camera\"]"
                + ",\"name\":\"Wallpanel Kamera\""
                + ",\"model\":\"rk3566\""
                + ",\"manufacturer\":\"Rockchip\""
                + ",\"sw_version\":" + js(version == null ? "" : version)
                + "}}";
    }

    static String js(String s) {
        StringBuilder out = new StringBuilder(s.length() + 2);
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                default -> out.append(c);
            }
        }
        out.append('"');
        return out.toString();
    }
}
