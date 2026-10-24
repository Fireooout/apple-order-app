package tech.plaseco.appleorder;

import android.Manifest;
import android.app.DownloadManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
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
import androidx.core.content.ContextCompat;

import com.getcapacitor.BridgeActivity;

import java.net.URLDecoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 等离子体果园 · 订单后台 App：在 Capacitor 外壳基础上补充
 *  1. 导出 Excel / 下载图片（交给系统下载管理器，带上登录 Cookie，保存到「下载/等离子体果园」）
 *  2. 打印拣货单、发货单（调用系统打印服务，可连接打印机或另存为 PDF）
 *  3. 返回键：网页内后退，回到首页后切到后台（保持登录）
 *  4. 状态栏颜色
 * 只加载 apple.plaseco.tech；其他网址（快递查询等）交给手机浏览器打开。
 */
public class MainActivity extends BridgeActivity {

    private static final int REQ_STORAGE = 1001;
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
    public void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
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
