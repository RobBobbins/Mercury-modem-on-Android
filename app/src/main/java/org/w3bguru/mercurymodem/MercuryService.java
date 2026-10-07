/*
 * Mercury Modem for Android — runs the Rhizomatica Mercury HF modem.
 * Copyright (C) 2026. GPL-3.0-or-later; see assets/LICENSE.txt.
 */
package org.w3bguru.mercurymodem;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.IBinder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Foreground service that runs the Mercury program (libmercury.so) as a child
 * process. Mercury listens on 127.0.0.1 only; a controlling program sends
 * MYCALL / LISTEN / CONNECT on the control port as with VARA.
 */
public final class MercuryService extends Service {
    public static final String ACTION_STOP = "org.w3bguru.mercurymodem.STOP";
    /** Ports of the run in progress (from the settings at Start). */
    private static volatile int controlPort = 8400, dataPort = 8401;

    private static final String CHANNEL_ID = "mercury";
    private static final int NOTIFICATION_ID = 1;
    private static final int LOG_LINES_KEPT = 200;

    // Read by MainActivity (same process).
    private static volatile String status = "Stopped";
    private static final ArrayDeque<String> logLines = new ArrayDeque<>();

    private Process process;
    private Thread watcher;
    private volatile boolean stopping;

    public static String status() {
        return status;
    }

    public static List<String> lastLogLines(int n) {
        synchronized (logLines) {
            List<String> all = new ArrayList<>(logLines);
            return all.subList(Math.max(0, all.size() - n), all.size());
        }
    }

    private static void addLogLine(String line) {
        synchronized (logLines) {
            logLines.addLast(line);
            while (logLines.size() > LOG_LINES_KEPT) logLines.removeFirst();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopMercury();
            return START_NOT_STICKY;
        }
        startInForeground();
        if (process == null) startMercury();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        stopMercury();
        super.onDestroy();
    }

    private void startInForeground() {
        MercurySettings s = MercurySettings.load(this);
        controlPort = s.controlPort;
        dataPort = s.dataPort();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "Mercury modem", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Mercury modem running")
                .setContentText("Ports " + controlPort + " / " + dataPort + " on this phone")
                .setContentIntent(open)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    private void startMercury() {
        File exe = new File(getApplicationInfo().nativeLibraryDir, "libmercury.so");
        File log = new File(getFilesDir(), "mercury.log");
        File console = new File(getFilesDir(), "mercury-console.txt");
        //noinspection ResultOfMethodCallIgnored
        log.delete();
        synchronized (logLines) {
            logLines.clear();
        }
        stopping = false;
        MercurySettings settings = MercurySettings.load(this);
        int input = presentDevice(AudioManager.GET_DEVICES_INPUTS, settings.inputId, settings.inputName, "input");
        int output = presentDevice(AudioManager.GET_DEVICES_OUTPUTS, settings.outputId, settings.outputName, "output");
        try {
            // Mercury reads mercury.ini from its working folder: the transmit delay lives there.
            try (FileOutputStream out = new FileOutputStream(new File(getFilesDir(), "mercury.ini"))) {
                out.write(settings.ini().getBytes(StandardCharsets.UTF_8));
            }
            List<String> cmd = new ArrayList<>(Arrays.asList(exe.getAbsolutePath(),
                    "-x", "aaudio",
                    "-p", String.valueOf(settings.controlPort),
                    "-b", String.valueOf(settings.broadcastPort),
                    "-L", log.getAbsolutePath()));
            // A number makes Mercury open that Android audio device (Android patch in ffaudio aaudio.c).
            if (input != 0) { cmd.add("-i"); cmd.add(String.valueOf(input)); }
            if (output != 0) { cmd.add("-o"); cmd.add(String.valueOf(output)); }
            addLogLine("Settings: control port " + settings.controlPort + ", data port " + settings.dataPort()
                    + ", broadcast port " + settings.broadcastPort + ", sound in " + (input == 0 ? "default" : input)
                    + ", out " + (output == 0 ? "default" : output) + ", TX delay " + settings.txDelayMs + " ms");
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(getFilesDir());
            pb.environment().put("HOME", getFilesDir().getAbsolutePath());
            pb.environment().put("MERCURY_SHM_DIR", getCacheDir().getAbsolutePath());
            pb.redirectErrorStream(true);
            pb.redirectOutput(console);
            process = pb.start();
            status = "Running — waiting for a program on port " + controlPort;
        } catch (Exception e) {
            status = "Could not start Mercury: " + e.getMessage();
            addLogLine(status);
            process = null;
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return;
        }
        final Process p = process;
        watcher = new Thread(() -> watch(p, log), "mercury-watch");
        watcher.start();
    }

    /**
     * The device to use: the saved ID if that device is present, else a present device with
     * the saved name (a re-plugged USB sound card gets a new ID), else 0 (phone's default).
     */
    private int presentDevice(int flags, int savedId, String savedName, String what) {
        if (savedId == 0) return 0;
        AudioDeviceInfo[] devices = getSystemService(AudioManager.class).getDevices(flags);
        for (AudioDeviceInfo dev : devices) if (dev.getId() == savedId) return savedId;
        for (AudioDeviceInfo dev : devices)
            if (MercurySettings.describe(dev).equals(savedName)) {
                addLogLine("Sound " + what + ": \"" + savedName + "\" is now device " + dev.getId() + ".");
                return dev.getId();
            }
        addLogLine("Sound " + what + ": \"" + savedName + "\" is not connected; using the phone's default.");
        return 0;
    }

    /** Follows Mercury's log file and notices when the process ends. */
    private void watch(Process p, File log) {
        long offset = 0;
        StringBuilder partial = new StringBuilder();
        while (true) {
            boolean alive = p.isAlive();
            if (log.exists()) {
                try (RandomAccessFile f = new RandomAccessFile(log, "r")) {
                    if (f.length() > offset) {
                        f.seek(offset);
                        byte[] buf = new byte[(int) Math.min(f.length() - offset, 65536)];
                        f.readFully(buf);
                        offset += buf.length;
                        partial.append(new String(buf, StandardCharsets.UTF_8));
                        int nl;
                        while ((nl = partial.indexOf("\n")) >= 0) {
                            String line = partial.substring(0, nl).trim();
                            partial.delete(0, nl + 1);
                            if (!line.isEmpty()) handleLine(line);
                        }
                    }
                } catch (Exception ignored) {
                    // Log file briefly unavailable; try again next pass.
                }
            }
            if (!alive) break;
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                break;
            }
        }
        int code = p.isAlive() ? -1 : p.exitValue();
        status = stopping ? "Stopped" : "Stopped — Mercury exited (code " + code + ")";
        addLogLine(status);
        process = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void handleLine(String line) {
        addLogLine(line);
        int i = line.indexOf("[arq] Connected to ");
        if (i >= 0) {
            status = "Connected to " + line.substring(i + "[arq] Connected to ".length()).trim();
        } else if (line.contains("[arq] Disconnected")) {
            status = "Running — disconnected";
        } else if (line.contains("[tcp-ctl] Control client connected")) {
            if (!status.startsWith("Connected")) status = "Running — program attached";
        } else if (line.contains("[tcp-ctl] Control client disconnected")) {
            if (!status.startsWith("Connected"))
                status = "Running — waiting for a program on port " + controlPort;
        }
    }

    private void stopMercury() {
        final Process p = process;
        if (p == null) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return;
        }
        stopping = true;
        status = "Stopping…";
        new Thread(() -> {
            p.destroy(); // SIGTERM: Mercury shuts down cleanly
            try {
                if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly();
            } catch (InterruptedException ignored) {
                p.destroyForcibly();
            }
        }, "mercury-stop").start();
    }
}
