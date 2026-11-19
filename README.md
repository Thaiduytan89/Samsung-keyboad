# SamKeyboard

Bàn phím Android (giao diện kiểu iPhone), viết bằng Kotlin. Không quảng cáo, không thu thập dữ liệu.

## Gõ phím & giao diện

- Giao diện sáng/tối kiểu iPhone, tự đổi theo hệ thống.
- **Popup ký tự** hiện lên khi nhấn phím.
- Phản hồi rung/haptic khi gõ phím.
- Bố cục QWERTY.
- **Shift**: chạm 1 lần = viết hoa 1 chữ, chạm đúp = Caps Lock.
- **Giữ phím xóa** để xóa liên tục.
- **Tự viết hoa** đầu câu.
- **Chạm đúp dấu cách** = chấm + cách.
- 2 trang ký hiệu (**123** và **#+=**).
- Nút **return** tự đổi theo ô nhập (Go, Tìm, Gửi, Tiếp, Xong, ...).
- Phím **VI / EN** để chuyển giữa tiếng Việt (Telex) và tiếng Anh, có nhớ lựa chọn.

## Gõ tiếng Việt (Telex)

- Dấu thanh: `s`=sắc, `f`=huyền, `r`=hỏi, `x`=ngã, `j`=nặng, `z`=xóa dấu
- `aa`=â, `ee`=ê, `oo`=ô, `aw`=ă, `ow`=ơ, `uw`=ư, `dd`=đ, `uow`=ươ
- Gõ lặp lại phím vừa tạo dấu thì hủy dấu (`aaa`=aa, `ss`=s, `ww`=w)
- Ví dụ: `Vieetj` = Việt, `dduowcj` = được, `nguowif` = người
- Tự tắt Telex ở ô mật khẩu, email và địa chỉ web

## Dịch

Bấm nút **Dịch** trên thanh phía trên bàn phím:

- Khi đang soạn văn bản (chưa gửi), bấm **Dịch** sẽ tự lấy nội dung đang gõ/đang chọn, **dịch và thay thế trực tiếp vào khung chat/ô nhập** (không cần nút thay thế).
- Có ô nhập văn bản cần dịch trong bảng dịch: bạn có thể gõ, sửa, xóa trực tiếp bằng bàn phím (khi gõ/sửa sẽ tự hiển thị bản dịch xem trước).
- Bấm **Sang: ...** để đổi ngôn ngữ đích giữa English, 繁體中文 (Hoa phồn thể), မြန်မာ (Myanmar) — sẽ dịch và thay thế trực tiếp.
- Bấm **Dịch** (hoặc phím **Enter/return**) để dịch và thay thế trực tiếp vào khung chat/ô nhập.
- Bấm **✕** để đóng bảng dịch.
- Tự nhận diện ngôn ngữ nguồn.
- **Cần có mạng.** Khi dịch, đoạn chữ cần dịch được gửi lên dịch vụ dịch của Google. Bàn phím không gửi gì khác.
- Lưu ý: app dùng đường dẫn dịch không chính thức của Google (không cần khóa API), nên có thể bị giới hạn hoặc ngừng hoạt động tùy mạng/thiết bị.

## Cách build và cài

1. Cài **Android Studio** (bản mới nhất) trên máy tính.
2. Mở Android Studio, chọn **Open** và chọn thư mục `SamKeyboard`.
3. Đợi Gradle đồng bộ xong (lần đầu cần mạng để tải thư viện, bấm OK nếu nó hỏi cài thêm SDK).
4. Trên điện thoại: bật **Tùy chọn nhà phát triển** và **Gỡ lỗi USB**, cắm cáp vào máy tính.
5. Bấm nút **Run** (tam giác xanh) trong Android Studio để cài lên điện thoại.
6. Mở app **SamKeyboard** vừa cài, làm theo 2 bước trên màn hình để bật và chọn bàn phím.

Muốn có file APK để gửi cho máy khác: menu **Build > Build Bundle(s) / APK(s) > Build APK(s)**.

## Cấu trúc code

- `app/src/main/java/com/example/samkeyboard/SamKeyboardService.kt`: toàn bộ bàn phím (bố cục, phím, popup, xử lý gõ, bảng dịch)
- `app/src/main/java/com/example/samkeyboard/TelexEngine.kt`: bộ gõ Telex
- `app/src/main/java/com/example/samkeyboard/SettingsActivity.kt`: màn hình hướng dẫn bật bàn phím
- `app/src/main/res/xml/method.xml`: khai báo bàn phím với hệ thống
