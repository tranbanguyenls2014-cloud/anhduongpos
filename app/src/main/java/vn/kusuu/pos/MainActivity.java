package vn.kusuu.pos;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.Toast;

/**
 * Ku Sửu POS — khung WebView mở web app (Google Apps Script) và cung cấp cầu nối in LAN "KuSuuPrinter"
 * đúng tên/chữ ký mà Index.html đang gọi: printRaster(ip, port, widthBytes, base64), probe(ip, port), fetchImageBase64(url).
 */
public class MainActivity extends Activity {
    private static final String PREFS = "kusuu";
    private static final String KEY_URL = "app_url";
    private static final int REQ_FILE = 1001;

    private WebView web;
    private String appUrl = "";
    private ValueCallback<Uri[]> fileCallback;
    private long lastBack = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);   // không tắt màn hình khi đang bán hàng

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#f7f2e9"));
        setContentView(web, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setupWebView();

        appUrl = currentUrl();
        if (appUrl.isEmpty()) askUrl(false); else web.loadUrl(appUrl);
    }

    private SharedPreferences prefs() { return getSharedPreferences(PREFS, MODE_PRIVATE); }

    /** Link dùng để mở: link người dùng tự đặt (nếu có) -> nếu không thì link GHI SẴN trong APK (BuildConfig.APP_URL). */
    private String currentUrl() {
        String saved = prefs().getString(KEY_URL, "");
        if (saved.startsWith("https://")) return saved;
        String built = BuildConfig.APP_URL == null ? "" : BuildConfig.APP_URL.trim();
        return built.startsWith("https://") ? built : "";
    }

    private void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_LONG).show(); }

    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);                    // localStorage cho phiên đăng nhập / dữ liệu tạm
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportMultipleWindows(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setUserAgentString(s.getUserAgentString() + " KuSuuPOS-APK/1.0");

        // Cầu nối in (tên bắt buộc trùng với Index.html)
        web.addJavascriptInterface(new PrinterBridge(), "KuSuuPrinter");
        // Dùng cho trang báo lỗi mất mạng (nút "Thử lại" / "Đổi link")
        web.addJavascriptInterface(new HostBridge(), "KuSuuHost");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                Uri u = request.getUrl();
                String host = u.getHost() == null ? "" : u.getHost().toLowerCase();
                boolean inside = "https".equals(u.getScheme()) &&
                        (host.equals("google.com") || host.endsWith(".google.com")
                                || host.endsWith(".googleusercontent.com") || host.endsWith(".gstatic.com"));
                if (inside) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
                return true;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) showOfflinePage();
            }
        });

        // QUAN TRỌNG: không có WebChromeClient thì alert() không hiện và confirm() luôn trả về "hủy" -> thanh toán không chạy.
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsAlert(WebView view, String url, String message, final JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setCancelable(false)
                        .setPositiveButton("OK", (d, w) -> result.confirm())
                        .show();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, final JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setCancelable(false)
                        .setPositiveButton("Đồng ý", (d, w) -> result.confirm())
                        .setNegativeButton("Hủy", (d, w) -> result.cancel())
                        .show();
                return true;
            }

            @Override
            public boolean onJsPrompt(WebView view, String url, String message, String defaultValue, final JsPromptResult result) {
                final EditText input = new EditText(MainActivity.this);
                input.setInputType(InputType.TYPE_CLASS_TEXT);
                input.setText(defaultValue == null ? "" : defaultValue);
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setView(input)
                        .setCancelable(false)
                        .setPositiveButton("OK", (d, w) -> result.confirm(input.getText().toString()))
                        .setNegativeButton("Hủy", (d, w) -> result.cancel())
                        .show();
                return true;
            }

            // Chọn ảnh món ăn từ thư viện / máy ảnh
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), REQ_FILE);
                } catch (Exception e) {
                    fileCallback = null;
                    toast("Không mở được trình chọn tệp.");
                    return false;
                }
                return true;
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE) {
            if (fileCallback != null) {
                fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
                fileCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void askUrl(final boolean cancelable) {
        final EditText input = new EditText(this);
        input.setHint("https://script.google.com/macros/s/.../exec");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setText(appUrl);
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("Link ứng dụng")
                .setMessage("Dán link Web App (kết thúc bằng /exec).\nĐể TRỐNG rồi bấm Lưu = dùng link có sẵn trong ứng dụng.")
                .setView(input)
                .setCancelable(cancelable)
                .setPositiveButton("Lưu & mở", (d, w) -> {
                    String u = input.getText().toString().trim();
                    if (u.isEmpty()) {
                        prefs().edit().remove(KEY_URL).apply();
                        appUrl = currentUrl();
                        if (appUrl.isEmpty()) { toast("Ứng dụng chưa có link ghi sẵn, hãy dán link."); askUrl(cancelable); return; }
                        web.loadUrl(appUrl);
                        return;
                    }
                    if (!u.startsWith("https://")) {
                        toast("Link phải bắt đầu bằng https://");
                        askUrl(cancelable);
                        return;
                    }
                    appUrl = u;
                    prefs().edit().putString(KEY_URL, u).apply();
                    web.loadUrl(u);
                });
        if (cancelable) b.setNegativeButton("Hủy", null);
        b.show();
    }

    private void showOfflinePage() {
        String html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>"
                + "<body style='font-family:sans-serif;background:#f7f2e9;color:#3b2a1a;text-align:center;padding:40px 20px'>"
                + "<h2>Không kết nối được</h2><p>Kiểm tra Wi‑Fi / 4G rồi thử lại.</p>"
                + "<p><button style='font-size:18px;padding:12px 24px' onclick='KuSuuHost.retry()'>Thử lại</button></p>"
                + "<p><button style='font-size:15px;padding:8px 16px' onclick='KuSuuHost.changeUrl()'>Đổi link ứng dụng</button></p>"
                + "</body></html>";
        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
    }

    @Override
    public void onBackPressed() {
        long now = System.currentTimeMillis();
        if (now - lastBack < 2000) { finish(); return; }
        lastBack = now;
        toast("Nhấn Back lần nữa để thoát");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    @Override
    protected void onPause() {
        if (web != null) web.onPause();
        super.onPause();
    }

    /** Cầu nối máy in LAN. Các hàm chạy ở luồng riêng của WebView nên được phép dùng mạng. Tên + tham số khớp Index.html. */
    public static final class PrinterBridge {
        @JavascriptInterface
        public String printRaster(String ip, int port, int widthBytes, String base64) {
            return ThermalPrinter.printRaster(ip, port, widthBytes, base64);
        }

        @JavascriptInterface
        public String probe(String ip, int port) {
            return ThermalPrinter.probe(ip, port);
        }

        @JavascriptInterface
        public String fetchImageBase64(String url) {
            return ThermalPrinter.fetchImageBase64(url);
        }
    }

    public final class HostBridge {
        @JavascriptInterface
        public void retry() {
            runOnUiThread(() -> { if (appUrl.isEmpty()) askUrl(false); else web.loadUrl(appUrl); });
        }

        @JavascriptInterface
        public void changeUrl() {
            runOnUiThread(() -> askUrl(true));
        }
    }
}
