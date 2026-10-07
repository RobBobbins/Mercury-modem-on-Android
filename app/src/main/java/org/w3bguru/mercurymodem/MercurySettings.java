/*
 * Mercury Modem for Android — runs the Rhizomatica Mercury HF modem.
 * Copyright (C) 2026. GPL-3.0-or-later; see assets/LICENSE.txt.
 */
package org.w3bguru.mercurymodem;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioDeviceInfo;

/** The modem's saved settings: ports, sound devices and the transmit delay. */
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
        return s;
    }

    void save(Context context) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
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
        return null;
    }

    /** The mercury.ini Mercury reads from its working folder (the app's files folder). */
    String ini() {
        return "; Written by the Mercury Modem app at Start. Settings are changed in the app.\n"
                + "[audio]\ntx_delay_ms = " + txDelayMs + "\n";
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
