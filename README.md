# AnPinime TV Projector

Ứng dụng Kotlin/Android cho máy chiếu và TV, Android 6 trở lên. Ba nguồn: Film4K,
Phim4K và AnimeVietsub. Giao diện dùng D-pad; phát native bằng Media3, bộ giải mã
LibVLC dự phòng và WebView khi nguồn chỉ cung cấp player nhúng.

## Build

JDK 17, Android SDK Platform 34, Build Tools 34.0.0 và Gradle 8.7:

```sh
gradle testDebugUnitTest assembleDebug
gradle assembleRelease
```

Debug có application ID `com.anpinime.projector.preview`, nhãn **AnPinime TV Thử nghiệm**,
cài song song với app hiện có. Dữ liệu/cookie của hai bản độc lập; bản thử có thể
cần xác minh nguồn một lần nữa. Release giữ application ID gốc. Hiện release dùng
debug signing; muốn cập nhật trực tiếp một APK đã cài phải dùng cùng khóa ký.

APK nằm trong `app/build/outputs/apk/debug/`: `app-armeabi-v7a-debug.apk` cho ARM32,
`app-arm64-v8a-debug.apk` cho ARM64, và `app-universal-debug.apk` nếu chưa biết kiến trúc.
Thư viện native được nén trong APK và giải nén khi cài đặt.

## Điều khiển bản 1.5

- Khi thanh điều khiển ẩn, trái/phải tua và đưa focus vào thanh thời lượng.
- Trên thanh thời lượng, trái/phải tua 10 giây; giữ lâu tăng bước lên 30 giây.
- Các nút `-10s` và `+10s` hỗ trợ nhấn liên tiếp hoặc giữ OK.
- Lên/xuống mở thanh điều khiển; OK trên nút thực hiện hành động. Hàng nút cuộn
  ngang để dùng được trên màn hình nhỏ.
- `Âm thanh` / `Phụ đề` liệt kê track mà luồng phát cung cấp. Phụ đề đã ghi vào
  hình không có track riêng và không thể tắt.
- `Sửa mất tiếng` chuyển luồng hiện tại sang LibVLC, giữ vị trí. App cũng tự chuyển
  khi Media3 báo codec âm thanh không hỗ trợ hoặc lỗi giải mã. Tính năng này không
  tạo âm thanh/phụ đề nếu nguồn không có chúng.
- Nút nguồn, Home, âm lượng được chuyển cho hệ thống.

## Kiểm tra trên máy chiếu

1. Mở lần lượt ba nguồn; thử tìm kiếm và đổi nguồn khi đang tải.
2. Anime: mở một tập, xác minh nếu nguồn yêu cầu; kiểm tra autoplay, pause/play,
   tua và tập tiếp theo bằng remote. Cloudflare phụ thuộc phiên bản Android System
   WebView, mạng và chính sách của nguồn; build thành công không xác nhận bước này.
3. Film4K: kiểm tra cả danh sách và phát HLS; thử tua lặp lại khi đang buffering.
4. Nguồn 2: chọn cùng phim/tập ở các server. Kiểm tra âm thanh, menu track/phụ đề,
   và `Sửa mất tiếng` nếu chỉ có hình. Ghi lại tên phim, tập, server khi báo lỗi.
5. Thử giữ trái/phải, giữ OK ở nút tua, thanh thời lượng, Back, Home rồi trở lại.

Kiểm thử JVM bao phủ parser nguồn (JSON, HTML, iframe, dữ liệu mã hóa) và proxy HLS
(header ticket, rewrite URL, segment bọc PNG, Range, WebVTT). Chúng không thay thế
kiểm thử codec, remote hoặc Cloudflare trên thiết bị thực.

Kiểm thử JavaScript dùng Playwright/Chromium, lấy đúng script từ `PlayerActivity.kt`:

```sh
node tests/web-player.test.cjs
```

Có thể đặt `PLAYWRIGHT_MODULE` để chỉ tới package Playwright đã cài và
`CHROMIUM_PATH` để dùng Chromium của hệ thống. Fixture kiểm tra autoplay, pause/play,
tua lặp lại/tuyệt đối, nhận diện manifest và truyền lệnh qua iframe khác origin.

## Tham khảo

- Mã iOS tại `../anime`: resolver anime, relay HLS và cấu trúc nguồn 2.
- APK `../273.apk`: quan sát tĩnh có `libffmpegJNI.so` ở bốn ABI; không thực thi APK.
- [Media3 supported formats](https://developer.android.com/media/media3/exoplayer/supported-formats).
- [AndroidX WebViewCompat](https://developer.android.com/reference/androidx/webkit/WebViewCompat):
  dùng script từ đầu document nếu WebView hỗ trợ, cùng bắt request media cho bản cũ.
- [LibVLC Android source](https://code.videolan.org/videolan/libvlcjni), Maven
  `org.videolan.android:libvlc-all:3.6.5`. Native library làm APK lớn hơn; chỉ khởi tạo
  khi cần chế độ tương thích. License và nguồn: xem `THIRD_PARTY_NOTICES.md`.
