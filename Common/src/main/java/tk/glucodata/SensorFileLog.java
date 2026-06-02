package tk.glucodata;

import android.content.Context;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.Writer;

/**
 * Diagnostic-only persistent capture of the unconditional "JuggSensor" logcat
 * diagnostics (connection / stream / scan outcomes — both the Java and the
 * native lines) to a file in the app's EXTERNAL files dir, so they survive
 * untethered (logcat itself is a small in-memory ring buffer) and can be
 * retrieved later via:
 *
 *   adb pull /sdcard/Android/data/&lt;pkg&gt;/files/sensorlog.txt
 *
 * An app may read its own UID-filtered logcat without any special permission
 * (this is the same technique the built-in dologcat() feature uses), so this
 * works even in the non-debuggable .dub release.
 *
 * Active only in the .dub diagnostic build (or any debuggable build); never in
 * the production tk.glucodata release.
 */
public final class SensorFileLog {
    private static final String LOG_ID = "SensorFileLog";
    private static final long ROTATE_BYTES = 4_000_000L; // rotate logfile at ~4 MB, keep one .1 backup
    private static volatile boolean started = false;

    public static synchronized void start(Context ctx) {
        if (started) return;
        if (!(ctx.getPackageName().endsWith(".dub") || BuildConfig.DEBUG)) return; // diagnostic builds only
        final File dir = ctx.getExternalFilesDir(null);
        if (dir == null) {
            android.util.Log.e(LOG_ID, "no external files dir; sensor file logging disabled");
            return;
        }
        started = true;
        final Thread t = new Thread(() -> run(dir), "SensorFileLog");
        t.setDaemon(true);
        t.start();
        android.util.Log.e("JuggSensor", "SensorFileLog: capturing to " + new File(dir, "sensorlog.txt"));
    }

    private static void run(File dir) {
        final File logfile = new File(dir, "sensorlog.txt");
        final File oldfile = new File(dir, "sensorlog.1.txt");
        while (true) {
            Process p = null;
            try {
                // -T 1 starts near "now" so we don't re-dump the whole buffer on each (re)start.
                p = Runtime.getRuntime().exec(new String[]{
                        "logcat", "-v", "time", "-T", "1", "JuggSensor:V", "*:S"});
                final BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                Writer w = new BufferedWriter(new FileWriter(logfile, true));
                long written = logfile.length();
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("---")) continue; // skip "--- beginning of ..." banners
                    w.write(line);
                    w.write('\n');
                    w.flush();
                    written += line.length() + 1;
                    if (written > ROTATE_BYTES) {
                        w.close();
                        if (oldfile.exists()) oldfile.delete();
                        logfile.renameTo(oldfile);
                        w = new BufferedWriter(new FileWriter(logfile, false));
                        written = 0;
                    }
                }
                w.close();
            } catch (Throwable e) {
                android.util.Log.e(LOG_ID, "capture loop error: " + e);
            } finally {
                if (p != null) p.destroy();
            }
            // logcat process exited (rare); wait and restart the capture.
            try {
                Thread.sleep(5000);
            } catch (InterruptedException ie) {
                return;
            }
        }
    }
}
