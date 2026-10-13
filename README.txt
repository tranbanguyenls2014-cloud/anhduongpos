KU SỬU POS — APK (vỏ in hóa đơn) — HƯỚNG DẪN BUILD BẰNG GITHUB
================================================================

APK này CHỈ là cái vỏ: mở link Web App của quán để dùng như bình thường, và lo riêng việc IN HÓA ĐƠN
qua Wi-Fi (cổng 9100). Sửa web (Index.html / Code.gs) rồi Deploy bản mới (giữ nguyên link /exec) thì mọi
điện thoại tự dùng bản mới — KHÔNG cần build lại hay cài lại APK.
Chỉ build lại APK khi: đổi link /exec, đổi icon, hoặc sửa code in trong chính APK.

A. TẠO FILE APK TRÊN GITHUB (làm 1 lần, không cần cài Android Studio)
----------------------------------------------------------------------
1. Đăng ký tài khoản GitHub (miễn phí): https://github.com
2. Bấm dấu "+" (góc trên phải) -> New repository.
   Tên: kusuu-pos-apk   |  chọn PRIVATE (riêng tư)  |  bấm Create repository.
3. Trong trang repository mới, bấm "uploading an existing file".
4. Giải nén KuSuuPOS-GitHub.zip, vào trong thư mục, CHỌN TẤT CẢ file và thư mục bên trong
   (app, gradle, .github, build.gradle, settings.gradle, app_url.txt, kusuu-release.keystore ...)
   rồi KÉO THẢ vào trang GitHub. Đợi tải xong -> bấm Commit changes.
   (Phải có thư mục ".github" — nếu sau đó không thấy tab Actions chạy, xem mục D.)
5. Điền LINK Web App (nếu CHƯA được điền sẵn). PHẢI là link kết thúc bằng /exec (KHÔNG dùng link /dev).
   Lấy link /exec: Apps Script -> Deploy -> Manage deployments -> copy "Web app URL".
   Chọn 1 trong 2 cách:
   - Cách 1: bấm vào file app_url.txt -> biểu tượng cây bút -> dán link /exec vào dòng cuối -> Commit.
   - Cách 2: Settings -> Secrets and variables -> Actions -> tab "Variables" -> New repository variable:
             Name = APP_URL ,  Value = link /exec.
6. Vào tab "Actions" -> chọn "Build APK" -> "Run workflow" (hoặc nó tự chạy sau khi upload/commit).
   Đợi khoảng 5–10 phút cho đến khi có dấu tích xanh.
7. Bấm vào lần chạy vừa xong, kéo xuống mục "Artifacts" -> tải "KuSuuPOS-apk" (file .zip) -> giải nén
   ra file KuSuuPOS.apk.  Đây là file để cài cho các điện thoại.

B. CÀI CHO NGƯỜI KHÁC
----------------------
1. Gửi file KuSuuPOS.apk cho họ (Zalo / Google Drive / USB).
2. Mở file -> cho phép "Cài ứng dụng không rõ nguồn gốc" -> Cài đặt.
3. Mở app "Ku Sửu POS": vào thẳng web app, KHÔNG phải nhập link.
4. Vào cài đặt máy in trong web app, nhập IP máy in + cổng 9100 (nhập một lần, các máy khác tự nhận vì lưu chung).
   Điện thoại phải cùng Wi-Fi với máy in.

C. CẬP NHẬT APK (khi đổi link/icon/code in)
--------------------------------------------
Sửa file trên GitHub (hoặc đổi biến APP_URL) -> Actions tự build lại -> tải APK mới -> cài ĐÈ lên bản cũ.
Cài đè được vì mọi bản đều ký bằng cùng một khóa (file kusuu-release.keystore).
!! GIỮ KỸ file kusuu-release.keystore (sao lưu ra nơi an toàn). Mất khóa này thì bản mới sẽ KHÔNG cài đè được,
   phải gỡ app cũ rồi cài lại. Nên để repository ở chế độ PRIVATE.

D. GẶP LỖI
-----------
- Không thấy "Build APK" trong tab Actions: GitHub không nhận thư mục ".github". Làm thế này:
  Add file -> Create new file -> gõ tên:  .github/workflows/build-apk.yml  -> dán nội dung file
  .github/workflows/build-apk.yml trong thư mục đã giải nén -> Commit.
- Actions báo lỗi đỏ: bấm vào bước màu đỏ, chụp màn hình gửi cho người hỗ trợ.
- Build báo "Link Web App phải bắt đầu bằng https://": link trong app_url.txt / biến APP_URL bị sai.
- Muốn đổi link trên MỘT điện thoại (thử nghiệm): tắt mạng để hiện "Không kết nối được" -> "Đổi link ứng dụng".
  Để trống rồi Lưu = quay về link ghi sẵn.
- "Hết thời gian chờ máy in": máy in tắt / sai IP / điện thoại không cùng Wi-Fi.
- "Máy in từ chối kết nối": sai cổng (thường 9100).
- Nhập liệu giọng nói và nút tải file sao lưu có thể không chạy trong APK: dùng máy tính cho việc đó.

E. (TÙY CHỌN) BUILD BẰNG ANDROID STUDIO
-----------------------------------------
Mở thư mục này bằng Android Studio -> Build -> Build APK(s). File: app/build/outputs/apk/(debug|release)/.
