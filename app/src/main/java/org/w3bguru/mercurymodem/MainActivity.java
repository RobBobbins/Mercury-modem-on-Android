/*
 * Mercury Modem for Android — runs the Rhizomatica Mercury HF modem.
 * Copyright (C) 2026. GPL-3.0-or-later; see assets/LICENSE.txt.
 */
package org.w3bguru.mercurymodem;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.text.InputType;
import android.widget.ArrayAdapter;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 1;
    private static final int LOG_LINES_SHOWN = 20;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView statusView;
    private TextView logView;
    private Button startButton;
    private Button stopButton;
    private TextView portsView, settingsNote;
    private EditText controlPort, broadcastPort, txDelay;
    private Spinner inputDevice, outputDevice;
    private final List<AudioDeviceInfo> inputs = new ArrayList<>(), outputs = new ArrayList<>();
    /** The field of each Mercury option (EditText, CheckBox or Spinner), by option id. */
    private final Map<String, View> optionFields = new HashMap<>();

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            String status = MercuryService.status();
            statusView.setText("Status: " + status);
            boolean stopped = status.startsWith("Stopped") || status.startsWith("Could not");
            startButton.setEnabled(stopped);
            stopButton.setEnabled(!stopped && !status.startsWith("Stopping"));
            logView.setText(String.join("\n", MercuryService.lastLogLines(LOG_LINES_SHOWN)));
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        statusView = new TextView(this);
        statusView.setTextSize(16);
        root.addView(statusView);

        portsView = new TextView(this);
        root.addView(portsView);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, pad / 2, 0, pad / 2);
        startButton = new Button(this);
        startButton.setText("Start");
        startButton.setOnClickListener(v -> startWithPermissions());
        stopButton = new Button(this);
        stopButton.setText("Stop");
        stopButton.setOnClickListener(v -> startService(
                new Intent(this, MercuryService.class).setAction(MercuryService.ACTION_STOP)));
        buttons.addView(startButton);
        buttons.addView(stopButton);
        root.addView(buttons);

        TextView logTitle = new TextView(this);
        logTitle.setText("Mercury log (last " + LOG_LINES_SHOWN + " lines)");
        root.addView(logTitle);

        logView = new TextView(this);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(10);
        logView.setTextIsSelectable(true);
        root.addView(logView);

        addSettings(root, pad);

        TextView about = new TextView(this);
        about.setPadding(0, pad, 0, 0);
        about.setText("Unofficial Android build of the Rhizomatica Mercury HF modem, "
                + "version 1.9.17 (git b2ad7863). Free software under the GNU General "
                + "Public License version 3 or later; FreeDV/codec2 parts under LGPL 2.1. "
                + "Source: github.com/Rhizomatica/mercury plus this app's Android changes.");
        root.addView(about);

        Button licence = new Button(this);
        licence.setText("Show licence");
        licence.setOnClickListener(v -> showLicence());
        root.addView(licence);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    /** The settings form: ports, sound devices, transmit delay. */
    private void addSettings(LinearLayout root, int pad) {
        MercurySettings s = MercurySettings.load(this);
        TextView title = new TextView(this);
        title.setText("Settings");
        title.setTextSize(18);
        title.setPadding(0, pad, 0, pad / 4);
        root.addView(title);
        hint(root, "Changes are used at the next Start. The program that drives Mercury (FieldMail) must use the same control port.");

        controlPort = number(root, "Control port (1024 to 65534; data port is the next number)", s.controlPort);
        broadcastPort = number(root, "Broadcast port (1024 to 65535)", s.broadcastPort);
        hint(root, "Defaults 8400 (data 8401) and 8102. The ports are on this phone only.");

        AudioManager am = getSystemService(AudioManager.class);
        inputDevice = deviceChoice(root, "Sound input (receive)", am.getDevices(AudioManager.GET_DEVICES_INPUTS), inputs, s.inputId, s.inputName);
        outputDevice = deviceChoice(root, "Sound output (transmit)", am.getDevices(AudioManager.GET_DEVICES_OUTPUTS), outputs, s.outputId, s.outputName);

        txDelay = number(root, "TX delay before sound, ms (0 to 2000; default 150)", s.txDelayMs);
        hint(root, "Mercury waits this long after PTT ON before sending sound, so the radio is keyed in time "
                + "when another program (FieldMail) keys it. Mercury's own default is 10.");

        // Mercury's own options (mercury.ini), grouped as in Mercury's mercury.ini.example
        String group = null;
        for (MercurySettings.Option o : MercurySettings.OPTIONS) {
            if (!o.group.equals(group)) {
                group = o.group;
                TextView g = new TextView(this);
                g.setText(group);
                g.setTextSize(16);
                g.setPadding(0, pad, 0, 0);
                root.addView(g);
            }
            optionFields.put(o.id(), optionField(root, o, s.values.get(o.id())));
            if (o.help != null) hint(root, o.help);
        }

        Button defaults = new Button(this);
        defaults.setText("Restore defaults");
        defaults.setOnClickListener(v -> restoreDefaults());
        root.addView(defaults);
        hint(root, "Puts the TX delay (150 ms) and every Mercury option above back to its default and saves. "
                + "Ports and sound devices are not changed.");

        Button save = new Button(this);
        save.setText("Save settings");
        save.setOnClickListener(v -> saveSettings());
        root.addView(save);
        settingsNote = new TextView(this);
        root.addView(settingsNote);
    }

    private void saveSettings() {
        MercurySettings s = new MercurySettings();
        try {
            s.controlPort = Integer.parseInt(controlPort.getText().toString().trim());
            s.broadcastPort = Integer.parseInt(broadcastPort.getText().toString().trim());
            s.txDelayMs = Integer.parseInt(txDelay.getText().toString().trim());
            for (MercurySettings.Option o : MercurySettings.OPTIONS) s.values.put(o.id(), fieldValue(o));
        } catch (NumberFormatException e) {
            settingsNote.setText("Not saved: every number field needs a whole number.");
            return;
        }
        int in = inputDevice.getSelectedItemPosition(), out = outputDevice.getSelectedItemPosition();
        s.inputId = in <= 0 ? 0 : inputs.get(in - 1).getId();
        s.inputName = in <= 0 ? "" : MercurySettings.describe(inputs.get(in - 1));
        s.outputId = out <= 0 ? 0 : outputs.get(out - 1).getId();
        s.outputName = out <= 0 ? "" : MercurySettings.describe(outputs.get(out - 1));
        String problem = s.problem();
        if (problem != null) {
            settingsNote.setText("Not saved: " + problem);
            return;
        }
        s.save(this);
        String state = MercuryService.status();
        boolean running = !(state.startsWith("Stopped") || state.startsWith("Could not"));
        settingsNote.setText(running ? "Saved. Stop and Start again to use them." : "Saved. They are used at the next Start.");
        showPorts();
    }

    /** TX delay and Mercury's options back to their defaults on the screen, then saved; ports and devices kept. */
    private void restoreDefaults() {
        txDelay.setText(String.valueOf(MercurySettings.TX_DELAY_DEFAULT));
        for (MercurySettings.Option o : MercurySettings.OPTIONS) setField(o, o.def);
        saveSettings();
        if (settingsNote.getText().toString().startsWith("Saved")) settingsNote.setText("Defaults restored. " + settingsNote.getText());
    }

    private View optionField(LinearLayout root, MercurySettings.Option o, String value) {
        if (o.flag) {
            CheckBox c = new CheckBox(this);
            c.setText(o.label + " (default " + ("true".equals(o.def) ? "on" : "off") + ")");
            c.setTypeface(Typeface.DEFAULT_BOLD);
            c.setChecked("true".equals(value));
            root.addView(c);
            return c;
        }
        label(root, o.label + (o.choices != null ? " (default " + o.def + ")" : " (" + o.range() + "; default " + o.def + ")"));
        if (o.choices != null) {
            Spinner sp = new Spinner(this);
            ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, o.choices);
            a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            sp.setAdapter(a);
            sp.setSelection(Math.max(0, Arrays.asList(o.choices).indexOf(value)));
            root.addView(sp);
            return sp;
        }
        EditText e = new EditText(this);
        e.setText(value);
        e.setInputType(o.decimal ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED
                : InputType.TYPE_CLASS_NUMBER);
        root.addView(e);
        return e;
    }

    private String fieldValue(MercurySettings.Option o) {
        View v = optionFields.get(o.id());
        if (v instanceof CheckBox) return ((CheckBox) v).isChecked() ? "true" : "false";
        if (v instanceof Spinner) return (String) ((Spinner) v).getSelectedItem();
        return ((EditText) v).getText().toString().trim();
    }

    private void setField(MercurySettings.Option o, String value) {
        View v = optionFields.get(o.id());
        if (v instanceof CheckBox) ((CheckBox) v).setChecked("true".equals(value));
        else if (v instanceof Spinner) ((Spinner) v).setSelection(Math.max(0, Arrays.asList(o.choices).indexOf(value)));
        else ((EditText) v).setText(value);
    }

    private void showPorts() {
        MercurySettings s = MercurySettings.load(this);
        portsView.setText("Control port " + s.controlPort + " · data port " + s.dataPort() + " · broadcast port "
                + s.broadcastPort + " · this phone only · sound in: " + (s.inputId == 0 ? "phone's default" : s.inputName)
                + " · sound out: " + (s.outputId == 0 ? "phone's default" : s.outputName));
    }

    private Spinner deviceChoice(LinearLayout root, String title, AudioDeviceInfo[] devices, List<AudioDeviceInfo> kept, int savedId, String savedName) {
        label(root, title);
        List<String> names = new ArrayList<>();
        names.add("Phone's default");
        int selected = 0;
        for (AudioDeviceInfo dev : devices) {
            int t = dev.getType();
            // Telephony, FM tuner and similar routes are not usable for a modem.
            if (t == AudioDeviceInfo.TYPE_TELEPHONY || t == AudioDeviceInfo.TYPE_FM_TUNER || t == AudioDeviceInfo.TYPE_REMOTE_SUBMIX) continue;
            kept.add(dev);
            names.add(MercurySettings.describe(dev) + " (device " + dev.getId() + ")");
            if (dev.getId() == savedId || (selected == 0 && savedId != 0 && MercurySettings.describe(dev).equals(savedName))) selected = kept.size();
        }
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(a);
        spinner.setSelection(selected);
        root.addView(spinner);
        return spinner;
    }

    private EditText number(LinearLayout root, String title, int value) {
        label(root, title);
        EditText e = new EditText(this);
        e.setText(String.valueOf(value));
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        root.addView(e);
        return e;
    }

    private void label(LinearLayout root, String title) {
        TextView t = new TextView(this);
        t.setText(title);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, (int) (10 * getResources().getDisplayMetrics().density), 0, 0);
        root.addView(t);
    }

    private void hint(LinearLayout root, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12);
        root.addView(t);
    }

    @Override
    protected void onResume() {
        super.onResume();
        showPorts();
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    private void startWithPermissions() {
        List<String> needed = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (needed.isEmpty()) {
            startForegroundService(new Intent(this, MercuryService.class));
        } else {
            requestPermissions(needed.toArray(new String[0]), REQUEST_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQUEST_PERMISSIONS) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startForegroundService(new Intent(this, MercuryService.class));
        } else {
            statusView.setText("Status: Stopped — microphone permission is needed to run the modem");
        }
    }

    private void showLicence() {
        String text;
        try (InputStream in = getAssets().open("LICENSE.txt")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            text = out.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            text = "Licence file missing: " + e.getMessage();
        }
        TextView body = new TextView(this);
        body.setText(text);
        body.setTextSize(11);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        body.setPadding(pad, pad, pad, pad);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        new AlertDialog.Builder(this)
                .setTitle("GNU General Public License v3")
                .setView(scroll)
                .setPositiveButton("Close", null)
                .show();
    }
}
