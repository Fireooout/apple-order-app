package tech.plaseco.appleorder;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.webkit.CookieManager;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 订单通知：国内手机收不到谷歌推送，所以 App 自己每分钟向服务器取一次新消息（/api/notify/pull），
 * 用系统通知栏提醒（带提示音）。以前台服务运行，并用“允许空闲时触发”的闹钟定时唤醒；
 * 手机长时间锁屏待机时，系统可能把间隔拉长到几分钟。
 */
public class NotifyService extends Service {

    static final String BASE = "https://apple.plaseco.tech";
    static final String CH_ORDER = "orders";
    static final String CH_RUN = "running";
    static final int ID_RUN = 1;
    static final long INTERVAL = 60_000L;
    static final String ACTION_POLL = "tech.plaseco.appleorder.POLL";

    /** App 界面在前台时不弹通知（网页自己会响提示音），只更新已读位置。 */
    static volatile boolean appVisible = false;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("notify", Context.MODE_PRIVATE);
    }

    static boolean isEnabled(Context c) {
        return prefs(c).getBoolean("enabled", false);
    }

    static String deviceId(Context c) {
        SharedPreferences p = prefs(c);
        String id = p.getString("device", null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            p.edit().putString("device", id).apply();
        }
        return id;
    }

    static void start(Context c) {
        prefs(c).edit().putBoolean("enabled", true).apply();
        Intent i = new Intent(c, NotifyService.class).setAction(ACTION_POLL);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception ignored) {
            // 部分系统不允许从后台启动；下次打开 App 时会再启动
        }
    }

    static void stop(Context c) {
        prefs(c).edit().putBoolean("enabled", false).apply();
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(alarmIntent(c));
        c.stopService(new Intent(c, NotifyService.class));
    }

    private static PendingIntent alarmIntent(Context c) {
        Intent i = new Intent(c, NotifyService.class).setAction(ACTION_POLL);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        return Build.VERSION.SDK_INT >= 26
                ? PendingIntent.getForegroundService(c, 0, i, flags)
                : PendingIntent.getService(c, 0, i, flags);
    }

    static void createChannels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel orders = new NotificationChannel(CH_ORDER, "订单提醒", NotificationManager.IMPORTANCE_HIGH);
        orders.setDescription("新订单、确认、排期、售后等消息");
        orders.enableVibration(true);
        nm.createNotificationChannel(orders);
        NotificationChannel run = new NotificationChannel(CH_RUN, "后台运行状态", NotificationManager.IMPORTANCE_MIN);
        run.setDescription("保持订单提醒在后台运行；可以在这里把它折叠");
        run.setShowBadge(false);
        nm.createNotificationChannel(run);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        goForeground();
        if (!isEnabled(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        worker.execute(this::pollOnce);
        scheduleNext();
        return START_STICKY;
    }

    private void goForeground() {
        Notification n = new NotificationCompat.Builder(this, CH_RUN)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("订单提醒运行中")
                .setContentText("有新订单、排期、售后等消息会在通知栏提醒你")
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(openApp(this, "/admin#/me", 0))
                .build();
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(ID_RUN, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(ID_RUN, n);
            }
        } catch (Exception ignored) {
        }
    }

    private void scheduleNext() {
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        long at = SystemClock.elapsedRealtime() + INTERVAL;
        PendingIntent pi = alarmIntent(this);
        if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
        else am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
    }

    static PendingIntent openApp(Context c, String url, int code) {
        Intent i = new Intent(c, MainActivity.class)
                .putExtra("url", url)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(c, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void pollOnce() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wl = pm == null ? null : pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "appleorder:poll");
        if (wl != null) wl.acquire(30_000L);
        HttpURLConnection con = null;
        try {
            SharedPreferences p = prefs(this);
            long after = p.getLong("last", 0);
            String cookie = CookieManager.getInstance().getCookie(BASE);
            if (cookie == null || !cookie.contains("ao_session")) {
                loginNotice();
                return;
            }
            String q = "?after=" + after + "&device=" + URLEncoder.encode(deviceId(this), "UTF-8")
                    + "&model=" + URLEncoder.encode(Build.MANUFACTURER + " " + Build.MODEL, "UTF-8");
            con = (HttpURLConnection) new URL(BASE + "/api/notify/pull" + q).openConnection();
            con.setConnectTimeout(15_000);
            con.setReadTimeout(15_000);
            con.setRequestProperty("Cookie", cookie);
            con.setRequestProperty("User-Agent", "AppleOrderApp-Notify/" + appVersion());
            int code = con.getResponseCode();
            if (code == 401 || code == 403) {
                loginNotice();
                return;
            }
            if (code != 200) return;
            JSONObject res = new JSONObject(readAll(con.getInputStream()));
            JSONArray items = res.optJSONArray("items");
            if (items != null && !appVisible) {
                for (int k = 0; k < items.length(); k++) show(items.getJSONObject(k));
            }
            p.edit().putLong("last", Math.max(after, res.optLong("last", after)))
                    .putBoolean("loginWarned", false).putLong("lastOk", System.currentTimeMillis()).apply();
        } catch (Exception ignored) {
            // 网络不好时下一分钟再试
        } finally {
            if (con != null) con.disconnect();
            if (wl != null && wl.isHeld()) wl.release();
        }
    }

    private void show(JSONObject it) {
        int id = (int) (it.optLong("id") % 1_000_000) + 10;
        Notification n = new NotificationCompat.Builder(this, CH_ORDER)
                .setSmallIcon(R.drawable.ic_notify)
                .setColor(0xFF9E2A2B)
                .setContentTitle(it.optString("title", "订单提醒"))
                .setContentText(it.optString("body"))
                .setStyle(new NotificationCompat.BigTextStyle().bigText(it.optString("body")))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(true)
                .setContentIntent(openApp(this, it.optString("url", "/admin"), id))
                .build();
        try {
            NotificationManagerCompat.from(this).notify(id, n);
        } catch (SecurityException ignored) {
            // 没有通知权限
        }
    }

    /** 登录过期：只提醒一次，重新登录后恢复。 */
    private void loginNotice() {
        SharedPreferences p = prefs(this);
        if (p.getBoolean("loginWarned", false)) return;
        p.edit().putBoolean("loginWarned", true).apply();
        Notification n = new NotificationCompat.Builder(this, CH_ORDER)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("订单提醒已暂停")
                .setContentText("登录已过期，请打开 App 重新登录")
                .setAutoCancel(true)
                .setContentIntent(openApp(this, "/admin", 2))
                .build();
        try {
            NotificationManagerCompat.from(this).notify(2, n);
        } catch (SecurityException ignored) {
        }
    }

    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toString("UTF-8");
    }

    @Override
    public void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
