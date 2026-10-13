package vn.kusuu.pos;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.Base64;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Gửi ảnh hóa đơn (bitmap 1-bit) tới máy in nhiệt LAN qua cổng RAW (thường 9100) bằng lệnh ESC/POS.
 * Lớp này KHÔNG phụ thuộc Android để có thể kiểm thử trên máy tính.
 * Định dạng dữ liệu khớp với receiptRaster() trong Index.html: mỗi dòng ảnh = widthBytes byte, MSB trước, bit 1 = chấm đen.
 */
public final class ThermalPrinter {
    private ThermalPrinter() {}

    static final int CONNECT_TIMEOUT_MS = 3000;
    static final int JOB_TIMEOUT_MS = 20000;
    static final int BAND_ROWS = 128;                   // chia ảnh thành các dải nhỏ cho máy in có bộ nhớ đệm bé
    static final int MAX_BITMAP_BYTES = 4 * 1024 * 1024;
    static final int MAX_IMAGE_BYTES = 1024 * 1024;

    private static final Pattern HOST = Pattern.compile("^[A-Za-z0-9]([A-Za-z0-9.\\-]{0,251}[A-Za-z0-9])?$");
    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "kusuu-print");
        t.setDaemon(true);
        return t;
    });

    /** Kiểm tra kết nối tới máy in. Trả về JSON {"ok":true} hoặc {"ok":false,"error":"..."} */
    public static String probe(String ip, int port) {
        try {
            validateTarget(ip, port);
            runWithTimeout(ip, port, new byte[0], 6000);
            return json(true, null);
        } catch (Throwable e) {
            return json(false, describe(e, ip, port));
        }
    }

    /** In ảnh hóa đơn. Trả về JSON như probe(). */
    public static String printRaster(String ip, int port, int widthBytes, String base64) {
        try {
            validateTarget(ip, port);
            if (base64 == null || base64.isEmpty()) throw new IllegalArgumentException("Không có dữ liệu để in.");
            byte[] bitmap;
            try {
                bitmap = Base64.getDecoder().decode(base64.replaceAll("\\s", ""));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Dữ liệu hóa đơn không hợp lệ.");
            }
            byte[] job = buildJob(widthBytes, bitmap);
            runWithTimeout(ip, port, job, JOB_TIMEOUT_MS);
            return json(true, null);
        } catch (Throwable e) {
            return json(false, describe(e, ip, port));
        }
    }

    /** Tải ảnh (VietQR) bằng https rồi trả về base64; lỗi thì trả về chuỗi rỗng (web sẽ tự xử lý). */
    public static String fetchImageBase64(String url) {
        HttpURLConnection c = null;
        try {
            if (url == null || !url.startsWith("https://")) return "";
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(8000);
            c.setInstanceFollowRedirects(true);
            if (c.getResponseCode() != 200) return "";
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > MAX_IMAGE_BYTES) return "";
                }
                return Base64.getEncoder().encodeToString(out.toByteArray());
            }
        } catch (Throwable e) {
            return "";
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ------------------------------------------------------------------ nội bộ

    static byte[] buildJob(int widthBytes, byte[] bitmap) {
        if (widthBytes < 1 || widthBytes > 200) throw new IllegalArgumentException("Độ rộng ảnh không hợp lệ.");
        if (bitmap == null || bitmap.length == 0) throw new IllegalArgumentException("Không có dữ liệu để in.");
        if (bitmap.length > MAX_BITMAP_BYTES) throw new IllegalArgumentException("Hóa đơn quá dài để in.");
        if (bitmap.length % widthBytes != 0) throw new IllegalArgumentException("Dữ liệu ảnh không đúng kích thước.");
        int height = bitmap.length / widthBytes;
        ByteArrayOutputStream o = new ByteArrayOutputStream(bitmap.length + 256);
        o.write(0x1B); o.write(0x40);                                   // ESC @  : khởi tạo máy in
        for (int y = 0; y < height; y += BAND_ROWS) {
            int rows = Math.min(BAND_ROWS, height - y);
            o.write(0x1D); o.write(0x76); o.write(0x30); o.write(0x00); // GS v 0 : in ảnh raster, chế độ thường
            o.write(widthBytes & 0xFF); o.write((widthBytes >> 8) & 0xFF);
            o.write(rows & 0xFF); o.write((rows >> 8) & 0xFF);
            o.write(bitmap, y * widthBytes, rows * widthBytes);
        }
        o.write(0x1B); o.write(0x64); o.write(0x04);                    // ESC d 4 : đẩy giấy 4 dòng
        o.write(0x1D); o.write(0x56); o.write(0x42); o.write(0x00);     // GS V B 0 : cắt giấy (máy không có dao cắt sẽ bỏ qua)
        return o.toByteArray();
    }

    private static void validateTarget(String ip, int port) {
        if (ip == null || ip.trim().isEmpty() || !HOST.matcher(ip.trim()).matches())
            throw new IllegalArgumentException("Địa chỉ IP máy in không hợp lệ.");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Cổng máy in không hợp lệ.");
    }

    /** Kết nối + gửi dữ liệu, có giới hạn tổng thời gian; quá hạn thì đóng socket. */
    private static void runWithTimeout(final String ip, final int port, final byte[] data, int totalMs) throws Exception {
        final AtomicReference<Socket> ref = new AtomicReference<>();
        Future<Void> f = POOL.submit(new Callable<Void>() {
            @Override public Void call() throws Exception {
                Socket s = new Socket();
                ref.set(s);
                try {
                    s.setTcpNoDelay(true);
                    s.connect(new InetSocketAddress(ip.trim(), port), CONNECT_TIMEOUT_MS);
                    if (data.length > 0) {
                        OutputStream out = s.getOutputStream();
                        int off = 0;
                        while (off < data.length) {
                            int n = Math.min(4096, data.length - off);
                            out.write(data, off, n);
                            off += n;
                        }
                        out.flush();
                        try { Thread.sleep(250); } catch (InterruptedException ignored) {}   // chờ máy in nhận hết bộ đệm
                    }
                } finally {
                    try { s.close(); } catch (Exception ignored) {}
                }
                return null;
            }
        });
        try {
            f.get(totalMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            Socket s = ref.get();
            if (s != null) try { s.close(); } catch (Exception ignored) {}
            f.cancel(true);
            throw new SocketTimeoutException("timeout");
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof Exception) throw (Exception) c;
            throw e;
        }
    }

    private static String describe(Throwable e, String ip, int port) {
        String target = ip + ":" + port;
        if (e instanceof IllegalArgumentException) return e.getMessage();
        if (e instanceof SocketTimeoutException) return "Hết thời gian chờ máy in " + target + ". Kiểm tra máy in đã bật và cùng Wi‑Fi với điện thoại.";
        if (e instanceof ConnectException) return "Máy in " + target + " từ chối kết nối. Kiểm tra lại IP và cổng (thường là 9100).";
        if (e instanceof NoRouteToHostException) return "Không tìm thấy đường tới máy in " + target + ". Điện thoại cần cùng mạng Wi‑Fi với máy in.";
        if (e instanceof UnknownHostException) return "Không tìm thấy máy in '" + ip + "'.";
        String m = e.getMessage();
        return "Lỗi gửi lệnh in: " + (m == null || m.isEmpty() ? e.getClass().getSimpleName() : m);
    }

    static String json(boolean ok, String error) {
        if (ok) return "{\"ok\":true}";
        return "{\"ok\":false,\"error\":\"" + esc(error) + "\"}";
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c);
            }
        }
        return b.toString();
    }
}
