/*
 * Mercury Modem for Android — runs the Rhizomatica Mercury HF modem.
 * Copyright (C) 2026. GPL-3.0-or-later; see assets/LICENSE.txt.
 */
package org.w3bguru.mercurymodem;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioDeviceInfo;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The modem's saved settings: ports, sound devices, the transmit delay, and Mercury's own options (OPTIONS, written to
 * mercury.ini). Restore defaults resets the transmit delay and the options, not the ports or sound devices.
 */
final class MercurySettings {
    /** Control port; Mercury's data port is always the next number. */
    int controlPort = 8400;
    /** Broadcast port; Mercury's own default 8100 is the Dire Wolf Modem app's AGW port. */
    int broadcastPort = 8102;
    /** Android audio device IDs; 0 means the phone's default. Names find a re-plugged device. */
    int inputId, outputId;
    String inputName = "", outputName = "";
    /** Wait after PTT ON before sound starts (Mercury tx_delay_ms), so the radio is keyed in time. */
    int txDelayMs = 150;
    static final int TX_DELAY_DEFAULT = 150;

    /** One Mercury option from its mercury.ini.example: ini section and key, screen label, default, limits. */
    static final class Option {
        final String group, section, key, label, def, help;
        final double min, max;
        final String[] choices;     // non-null: one of these words
        final boolean flag, decimal;
        Option(String group, String section, String key, String label, String def, double min, double max, boolean decimal, String[] choices, boolean flag, String help) {
            this.group = group; this.section = section; this.key = key; this.label = label; this.def = def;
            this.min = min; this.max = max; this.decimal = decimal; this.choices = choices; this.flag = flag; this.help = help;
        }
        String id() { return section + ":" + key; }
        /** Null when the text is a valid value, or what is wrong. */
        String check(String v) {
            if (flag) return "true".equals(v) || "false".equals(v) ? null : label + ": on or off.";
            if (choices != null) { for (String c : choices) if (c.equals(v)) return null; return label + ": one of " + String.join(", ", choices) + "."; }
            double d;
            try { d = decimal ? Double.parseDouble(v) : Integer.parseInt(v); } catch (NumberFormatException e) { return label + ": needs a " + (decimal ? "number" : "whole number") + "."; }
            return d < min || d > max ? label + ": " + range() + "." : null;
        }
        String range() { return decimal ? fmt(min) + " to " + fmt(max) : (int) min + " to " + (int) max; }
        private static String fmt(double d) { return d == Math.rint(d) ? String.valueOf((int) d) : String.valueOf(d); }
    }

    private static Option num(String group, String section, String key, String label, int def, int min, int max, String help) {
        return new Option(group, section, key, label, String.valueOf(def), min, max, false, null, false, help);
    }

    /** Mercury 1.9.17's options with the limits its cfg_utils.c accepts (the two link timeouts only need more than 0). */
    static final Option[] OPTIONS = {
        num("Speed ladder and link", "arq", "startup_max_s", "Slowest-mode start, s", 10, 2, 60, "Only DATAC16 is used for this long while the link proves stable."),
        num("Speed ladder and link", "arq", "ladder_up_successes", "Clean answers to step up one speed", 2, 1, 16, null),
        num("Speed ladder and link", "arq", "retry_downgrade_threshold", "Retries in a row to step down one speed", 2, 1, 16, null),
        num("Speed ladder and link", "arq", "mode_hold_after_downgrade_s", "Stay at the lower speed after a step down, s", 6, 0, 60, null),
        num("Speed ladder and link", "arq", "data_retry_slots", "Data frame retries before a step down", 10, 1, 64, null),
        num("Speed ladder and link", "arq", "peer_payload_hold_s", "Keep the other station's speed after activity, s", 15, 1, 120, null),
        num("Speed ladder and link", "arq", "channel_guard_ms", "Wait before answering a decoded frame, ms", 700, 200, 3000, "Raise if the radio switches from transmit to receive slowly and answers get lost."),
        num("Speed ladder and link", "arq", "iss_post_ack_guard_ms", "Wait after an answer before sending data again, ms", 900, 200, 3000, null),
        num("Speed ladder and link", "arq", "retry_stagger_ms", "Retry offset between the two stations, ms", 2000, 0, 5000, "Stops both stations retrying at the same moment. 0 turns it off."),
        num("Speed ladder and link", "arq", "keepalive_interval_s", "Keepalive every, s", 20, 5, 120, null),
        num("Speed ladder and link", "arq", "keepalive_miss_limit", "Missed keepalives before the link is dropped", 5, 2, 20, null),
        num("Speed ladder and link", "arq", "no_progress_timeout_s", "Drop the link after no progress for, s", 180, 1, 3600, null),
        num("Speed ladder and link", "arq", "disconnect_drain_timeout_s", "Longest wait to send the last bytes at disconnect, s", 30, 1, 3600, null),
        new Option("Busy-channel detector", "channel", "busy_detect", "Report a busy channel (BUSY ON / OFF)", "false", 0, 0, false, null, true,
                "Off by default: a detector that fires on noise stops programs that wait for a clear channel from sending."),
        num("Busy-channel detector", "channel", "busy_threshold_db", "Busy threshold above the noise floor, dB", 10, 3, 40, "Lower is more sensitive."),
        num("Busy-channel detector", "channel", "busy_hysteresis_db", "Clear again this far below the threshold, dB", 3, 0, 20, null),
        num("Busy-channel detector", "channel", "busy_on_debounce_ms", "Signal must last this long to count as busy, ms", 300, 0, 5000, null),
        num("Busy-channel detector", "channel", "busy_hang_ms", "Quiet must last this long to count as clear, ms", 1500, 0, 10000, null),
        new Option("Sound", "audio", "tx_gain_db", "TX gain, dB", "0.0", -20, 20, true, null, false, "Mercury's transmit level without changing the phone's volume. 0 = no change."),
        new Option("Sound", "main", "capture_channel", "Capture channel", "left", 0, 0, false, new String[] {"left", "right", "stereo"}, false,
                "Which channel of the sound input Mercury listens to."),
        num("TNC (the program driving Mercury)", "tnc", "keepalive_s", "IAMALIVE to the program every, s", 60, 5, 600, null),
        num("TNC (the program driving Mercury)", "tnc", "buffer_report_ms", "BUFFER report every, ms", 1000, 100, 10000, null),
    };

    /** Values of OPTIONS by id ("section:key"), as text. */
    final Map<String, String> values = new LinkedHashMap<>();

    MercurySettings() { restoreDefaults(); }

    /** Transmit delay and Mercury's options back to their defaults; ports and sound devices unchanged. */
    void restoreDefaults() {
        txDelayMs = TX_DELAY_DEFAULT;
        for (Option o : OPTIONS) values.put(o.id(), o.def);
    }

    int dataPort() {
        return controlPort + 1;
    }

    static MercurySettings load(Context context) {
        SharedPreferences p = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        MercurySettings s = new MercurySettings();
        s.controlPort = p.getInt("controlPort", s.controlPort);
        s.broadcastPort = p.getInt("broadcastPort", s.broadcastPort);
        s.inputId = p.getInt("inputId", 0);
        s.outputId = p.getInt("outputId", 0);
        s.inputName = p.getString("inputName", "");
        s.outputName = p.getString("outputName", "");
        s.txDelayMs = p.getInt("txDelayMs", s.txDelayMs);
        for (Option o : OPTIONS) s.values.put(o.id(), p.getString("opt:" + o.id(), o.def));
        return s;
    }

    void save(Context context) {
        SharedPreferences.Editor e = context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit();
        for (Option o : OPTIONS) e.putString("opt:" + o.id(), values.get(o.id()));
        e
                .putInt("controlPort", controlPort).putInt("broadcastPort", broadcastPort)
                .putInt("inputId", inputId).putInt("outputId", outputId)
                .putString("inputName", inputName).putString("outputName", outputName)
                .putInt("txDelayMs", txDelayMs)
                .apply();
    }

    /** Returns null when the settings are usable, or what is wrong. */
    String problem() {
        if (controlPort < 1024 || controlPort > 65534) return "Control port must be 1024 to 65534 (the data port is the next number).";
        if (broadcastPort < 1024 || broadcastPort > 65535) return "Broadcast port must be 1024 to 65535.";
        if (broadcastPort == controlPort || broadcastPort == dataPort())
            return "Broadcast port must differ from the control port and the data port (" + controlPort + " and " + dataPort() + ").";
        if (txDelayMs < 0 || txDelayMs > 2000) return "TX delay must be 0 to 2000 ms.";
        for (Option o : OPTIONS) { String bad = o.check(values.get(o.id())); if (bad != null) return bad; }
        return null;
    }

    /** The mercury.ini Mercury reads from its working folder (the app's files folder). */
    String ini() {
        Map<String, StringBuilder> sections = new LinkedHashMap<>();
        sections.computeIfAbsent("audio", k -> new StringBuilder()).append("tx_delay_ms = ").append(txDelayMs).append('\n');
        for (Option o : OPTIONS)
            sections.computeIfAbsent(o.section, k -> new StringBuilder()).append(o.key).append(" = ").append(values.get(o.id())).append('\n');
        StringBuilder out = new StringBuilder("; Written by the Mercury Modem app at Start. Settings are changed in the app.\n");
        for (Map.Entry<String, StringBuilder> e : sections.entrySet()) out.append('[').append(e.getKey()).append("]\n").append(e.getValue());
        return out.toString();
    }

    /** A sound device as shown in the lists: type, then product name. */
    static String describe(AudioDeviceInfo d) {
        String type;
        switch (d.getType()) {
            case AudioDeviceInfo.TYPE_BUILTIN_MIC: type = "Built-in microphone"; break;
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: type = "Built-in speaker"; break;
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE: type = "Earpiece"; break;
            case AudioDeviceInfo.TYPE_USB_DEVICE: type = "USB sound card"; break;
            case AudioDeviceInfo.TYPE_USB_HEADSET: type = "USB headset"; break;
            case AudioDeviceInfo.TYPE_WIRED_HEADSET: type = "Wired headset"; break;
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES: type = "Wired headphones"; break;
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO: type = "Bluetooth (calls)"; break;
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: type = "Bluetooth (media)"; break;
            default: type = "Type " + d.getType();
        }
        CharSequence name = d.getProductName();
        String clean = name == null ? "" : name.toString().replaceAll("\\p{Cntrl}", "").trim();
        return type + (clean.isEmpty() ? "" : ": " + clean);
    }
}
