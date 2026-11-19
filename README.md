# SamKeyboard

Bàn phím Android (giao diện kiểu iPhone/Samsung), viết bằng Kotlin. Không quảng cáo, không thu thập dữ liệu.

## Gõ phím & giao diện

- **Giao diện**: chọn kiểu **iPhone** (mặc định) hoặc **Samsung** trong bảng tinh chỉnh (⌨).
- **Đổi màu bàn phím**: nhiều tông màu (Mặc định, Xanh dương, Xanh lá, Xanh ngọc, Tím, Hồng, Cam, Xám), áp dụng cho cả iPhone & Samsung, tự theo sáng/tối hệ thống.
- **Popup ký tự** hiện lên khi nhấn phím với hiệu ứng hiện/ẩn mượt (kiểu iPhone).
- Phản hồi **rung/haptic** và **âm thanh gõ** khi gõ phím (âm thanh có nhiều lựa chọn).
- Bố cục QWERTY.
- **Shift**: chạm 1 lần = viết hoa 1 chữ, chạm đúp = Caps Lock.
- **Giữ phím xóa** để xóa liên tục.
- **Tự viết hoa** đầu câu.
- **Chạm đúp dấu cách** = chấm + cách.
- 2 trang ký hiệu (**123** và **#+=**).
- Nút **return** tự đổi theo ô nhập (Go, Tìm, Gửi, Tiếp, Xong, ...).
- Phím **VI / EN** để chuyển giữa tiếng Việt (Telex) và tiếng Anh, có nhớ lựa chọn.

## Bố cục theo màn hình gập/mở (Oppo Find N5)

- **Màn hình trong (mở ra, màn hình lớn)**: tự động dùng **bàn phím tách đôi 2 bên** (kiểu Gboard, có khoảng trống ở giữa) kèm **dãy số 1-0** ở trên để tiện gõ 2 tay.
  - Có phím **G** bổ sung bên trái **H** (nửa phải) và phím **V** bổ sung bên phải **C** (nửa trái) cho cân bằng khi gõ hai tay.
- **Màn hình ngoài (gập lại, màn hình nhỏ)**: tự động dùng **bàn phím bình thường** (gộp, full chiều ngang).
- Bàn phím tự chuyển khi bạn gập/mở máy.

### Tinh chỉnh (nút ⌨)

Bấm nút **⌨** trên thanh công cụ để mở bảng tinh chỉnh:

- **Trên màn hình trong**: chọn kiểu bố cục **Bình thường / Tách đôi / Nổi**.
  - **Tách đôi** (mặc định): hai nửa bàn phím tách sang hai bên (kiểu Gboard) + dãy số.
  - **Nổi**: bàn phím nổi có thể di chuyển (kéo thanh “⠿ Kéo để di chuyển bàn phím”, dùng nút ← → ↑ ↓ và **Rộng −/+** để chỉnh vị trí/độ rộng).
  - **Bình thường**: bàn phím liền, full chiều ngang (trên màn hình trong có dãy số).
- **Cao**: tăng/giảm chiều cao phím.
- **Âm thanh**: chọn âm thanh gõ (**Tắt**, **Mặc định**, **Nhẹ**, **Click**, **Rõ**).
- **Giao diện**: chuyển **iPhone / Samsung**.
- **Màu**: đổi màu bàn phím.
- **Tinh chỉnh sâu** (Tinh chỉnh ▼/▲): điều chỉnh chi tiết kích thước/khoảng cách
  - **Lề**: khoảng cách từ mép màn hình vào giữa (đẩy bàn phím vào trong/ra ngoài).
  - **Ngang**: khoảng cách ngang giữa các phím.
  - **Dọc**: khoảng cách dọc giữa các phím.
- **Trên màn hình ngoài**: luôn dùng bàn phím bình thường (vẫn chỉnh được chiều cao, âm thanh, giao diện, màu, lề/khoảng cách).
- Các thiết lập bố cục (màn hình trong), chiều cao, giao diện, màu, âm thanh, lề/khoảng cách, vị trí/kích thước (chế độ nổi) được lưu lại.

## Gõ tiếng Việt (Telex, bố cục QWERTY)

- Kiểu gõ **Telex trên QWERTY** (tương tự Gboard): gõ chữ cái và phím dấu trực tiếp trên bàn phím QWERTY.
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

- `app/src/main/java/com/example/samkeyboard/SamKeyboardService.kt`: toàn bộ bàn phím (bố cục, phím, popup, xử lý gõ, bảng dịch, tách đôi/nổi/chiều cao, theme/màu, âm thanh, tinh chỉnh lề/khoảng cách, dãy số, tối ưu gập/mở)
- `app/src/main/java/com/example/samkeyboard/TelexEngine.kt`: bộ gõ Telex (QWERTY)
- `app/src/main/java/com/example/samkeyboard/SettingsActivity.kt`: màn hình hướng dẫn bật bàn phím
- `app/src/main/res/xml/method.xml`: khai báo bàn phím với hệ thống
