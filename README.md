# App Dịch Truyện (`app-dich-truyen`) — Android

Ứng dụng Android dịch chữ trực tiếp trên màn hình khi đọc truyện tranh (Manga, Manhwa, Manhua, Comic) — hỗ trợ **Online & Offline**, **lướt đến đâu chữ bám khung thoại đến đó**, vuốt xuyên qua lớp dịch không bị cản tay.

## Tính năng nổi bật

- **Gắn chữ trực tiếp lên khung thoại**: Tự đo màu nền bóng thoại (trắng, tối, hoặc nền tranh SFX), tự căn cỡ chữ tiếng Việt vừa khít khung thoại gốc bằng `StaticLayout`.
- **Lướt đến đâu bám đến đó**: Theo dõi tốc độ cuộn màn hình theo thời gian thực để dịch chuyển khung chữ bám theo lúc đang vuốt, sau khi dừng tay ~180ms tự quét khung mới và dịch liền.
- **OCR 100% Offline trên máy**: Dùng Google ML Kit Text Recognition v2 cho cả 4 ngôn ngữ **Anh, Nhật (kể cả chữ dọc), Hàn, Trung** cực nhanh (~100ms).
- **3 chế độ dịch linh hoạt + tự động dự phòng Offline**:
  - **Online Nhanh (Google)**: Dịch gom cả trang chỉ trong 1 request, miễn phí.
  - **Online AI (Gemini 2.5 Flash)**: Dán Gemini API Key để dịch chuẩn văn phong truyện tranh và xưng hô tự nhiên.
  - **Offline 100% (ML Kit Translate)**: Bấm 1 nút tải gói ngôn ngữ về máy, tắt mạng vẫn dịch ngon lành.
- **Nút nổi điều khiển gọn nhẹ**: Kéo thả cạnh màn hình, chạm để mở nhanh các nút `Quét`, `Dừng/Chạy`, `Ẩn/Hiện`, `✕`.

## Cách lấy file APK tự động qua GitHub (không cần cài Android Studio)

1. Giải nén thư mục dự án, bấm đúp **`push_to_github.bat`** để đẩy lên repo **`app-dich-truyen`** trên GitHub của bạn.
2. Vào tab **Actions** trên repo GitHub → chọn workflow **`Build Android APK - App Dich Truyen`**.
3. Chờ ~2–3 phút build xong, tải file **`AppDichTruyen-APK`** (`AppDichTruyen-v1.0.0.apk`) về điện thoại Android và cài đặt.

## Cách dùng trên điện thoại

1. Mở **App Dịch Truyện**, chọn ngôn ngữ truyện gốc (Anh / Nhật / Hàn / Trung) và chế độ dịch (nếu muốn dịch không mạng thì bấm **Tải gói dịch Offline** trước).
2. Bấm **BẬT DỊCH TRUYỆN NGAY**, cấp quyền *Hiển thị trên ứng dụng khác* và *Ghi màn hình*.
3. Mở app đọc truyện bất kỳ (Mihon, Tachiyomi, Chrome, Webtoon...) rồi lướt đọc bình thường.
