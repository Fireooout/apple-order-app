package tech.plaseco.appleorder;

import android.Manifest;
import android.app.DownloadManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.BridgeActivity;

import org.json.JSONObject;

import java.net.URLDecoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 等离子体果园 · 订单后台 App：在 Capacitor 外壳基础上补充
 *  1. 导出 Excel / 下载图片（交给系统下载管理器，带上登录 Cookie，保存到「下载/等离子体果园」）
 *  2. 打印拣货单、发货单（调用系统打印服务，可连接打印机或另存为 PDF）
 *  3. 返回键：网页内后退，回到首页后切到后台（保持登录）
 *  4. 状态栏颜色
 *  5. 订单提醒（NotifyService）：开启 / 关闭、通知权限、电池优化、自启动设置入口；点通知打开对应订单
 * 只加载 apple.plaseco.tech；其他网址（快递查询等）交给手机浏览器打开。
 */
public class MainActivity extends BridgeActivity {

    private static final int REQ_STORAGE = 1001;
    private static final int REQ_NOTIFY = 1002;
    private static final String DIR = "等离子体果园";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.parseColor("#3a2a26"));

        WebView webView = getBridge().getWebView();
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);
        webView.addJavascriptInterface(new AppBridge(), "AOApp");

        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, length) ->
                startDownload(url, userAgent, contentDisposition, mimeType));

        NotifyService.createChannels(this);
        openFromIntent(getIntent(), 1500);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                WebView wv = getBridge().getWebView();
                if (wv != null && wv.canGoBack()) {
                    wv.goBack();
                } else {
                    moveTaskToBack(true);
                }
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        NotifyService.appVisible = true;
        // 服务被系统清理后，打开 App 时自动恢复
        if (NotifyService.isEnabled(this)) NotifyService.start(this);
    }

    @Override
    public void onPause() {
        super.onPause();
        NotifyService.appVisible = false;
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        openFromIntent(intent, 0);
    }

    /** 点通知进入：打开对应的订单页面 */
    private void openFromIntent(Intent intent, long delay) {
        if (intent == null) return;
        String url = intent.getStringExtra("url");
        if (url == null || !url.startsWith("/")) return;
        intent.removeExtra("url");
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            WebView wv = getBridge() == null ? null : getBridge().getWebView();
            if (wv != null) wv.loadUrl(NotifyService.BASE + url);
        }, delay);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIFY) {
            NotifyService.start(this);
            notifyPage();
        }
    }

    /** 通知网页刷新“消息通知”卡片的状态 */
    private void notifyPage() {
        runOnUiThread(() -> {
            WebView wv = getBridge().getWebView();
            if (wv != null) wv.evaluateJavascript("window.dispatchEvent(new Event('aoapp-notify'))", null);
        });
    }

    private boolean ignoringBattery() {
        if (Build.VERSION.SDK_INT < 23) return true;
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    /** 各品牌“自启动 / 后台运行”管理页面，打不开时退回到应用详情页 */
    private void openAutostartSettings() {
        String[][] targets = {
                {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
                {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
                {"com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"},
                {"com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
                {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
                {"com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"},
                {"com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"},
                {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
                {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"},
                {"com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity"},
        };
        for (String[] t : targets) {
            try {
                Intent i = new Intent().setComponent(new ComponentName(t[0], t[1])).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (getPackageManager().resolveActivity(i, 0) != null) {
                    startActivity(i);
                    return;
                }
            } catch (Exception ignored) {
            }
        }
        openAppDetails();
    }

    private void openAppDetails() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
        } catch (Exception ignored) {
        }
    }

    /** 提供给网页调用的少量原生能力：window.AOApp.print() / window.AOApp.version() */
    private class AppBridge {
        @JavascriptInterface
        public void print() {
            runOnUiThread(() -> {
                try {
                    WebView wv = getBridge().getWebView();
                    PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
                    String title = wv.getTitle() == null ? "打印" : wv.getTitle();
                    PrintDocumentAdapter adapter = wv.createPrintDocumentAdapter(title);
                    pm.print(title, adapter, new PrintAttributes.Builder().build());
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "无法打开打印：" + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
        }

        /** 订单提醒状态：{enabled, permission, battery, brand, lastOk} */
        @JavascriptInterface
        public String notifyState() {
            try {
                JSONObject o = new JSONObject();
                o.put("enabled", NotifyService.isEnabled(MainActivity.this));
                o.put("permission", NotificationManagerCompat.from(MainActivity.this).areNotificationsEnabled());
                o.put("battery", ignoringBattery());
                o.put("brand", Build.MANUFACTURER);
                o.put("lastOk", NotifyService.prefs(MainActivity.this).getLong("lastOk", 0));
                o.put("device", NotifyService.deviceId(MainActivity.this));
                return o.toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public void notifyEnable() {
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(MainActivity.this,
                        Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(MainActivity.this,
                            new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
                } else {
                    NotifyService.start(MainActivity.this);
                    notifyPage();
                }
            });
        }

        @JavascriptInterface
        public void notifyDisable() {
            runOnUiThread(() -> {
                NotifyService.stop(MainActivity.this);
                notifyPage();
            });
        }

        /** 通知权限 / 声音设置 */
        @JavascriptInterface
        public void openNotifySettings() {
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                    startActivity(i);
                } catch (Exception e) {
                    openAppDetails();
                }
            });
        }

        /** 关闭电池优化（允许后台运行） */
        @JavascriptInterface
        public void openBattery() {
            runOnUiThread(() -> {
                try {
                    if (Build.VERSION.SDK_INT >= 23 && !ignoringBattery()) {
                        startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:" + getPackageName())));
                    } else if (Build.VERSION.SDK_INT >= 23) {
                        startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    }
                } catch (Exception e) {
                    openAppDetails();
                }
            });
        }

        /** 自启动 / 后台运行管理 */
        @JavascriptInterface
        public void openAutostart() {
            runOnUiThread(MainActivity.this::openAutostartSettings);
        }

        @JavascriptInterface
        public String version() {
            try {
                return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (Exception e) {
                return "";
            }
        }
    }

    private void startDownload(String url, String userAgent, String contentDisposition, String mimeType) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
            Toast.makeText(this, "请允许存储权限后，再点一次下载", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            String name = fileName(url, contentDisposition, mimeType);
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) req.addRequestHeader("Cookie", cookie);
            req.addRequestHeader("User-Agent", userAgent);
            req.setTitle(name);
            req.setDescription(DIR);
            if (mimeType != null && !mimeType.isEmpty()) req.setMimeType(mimeType);
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, DIR + "/" + name);
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            dm.enqueue(req);
            Toast.makeText(this, "开始下载：" + name + "\n完成后在通知栏或「下载/" + DIR + "」中查看",
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "下载失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** 解析服务器返回的文件名，支持中文（filename*=UTF-8''...） */
    private static String fileName(String url, String cd, String mimeType) {
        String name = null;
        if (cd != null) {
            Matcher m = Pattern.compile("filename\\*\\s*=\\s*(?:UTF-8|utf-8)''([^;]+)", Pattern.CASE_INSENSITIVE).matcher(cd);
            if (m.find()) {
                try { name = URLDecoder.decode(m.group(1).trim(), "UTF-8"); } catch (Exception ignored) { }
            }
            if (name == null) {
                m = Pattern.compile("filename\\s*=\\s*\"?([^\";]+)\"?", Pattern.CASE_INSENSITIVE).matcher(cd);
                if (m.find()) name = m.group(1).trim();
            }
        }
        if (name == null || name.isEmpty()) name = URLUtil.guessFileName(url, cd, mimeType);
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }
}
