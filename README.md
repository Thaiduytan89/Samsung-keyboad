# SamKeyboard

Bàn phím Android (giao diện kiểu iPhone/Samsung), viết bằng Kotlin. Không quảng cáo, không thu thập dữ liệu.

## Gõ phím & giao diện

- **Giao diện**: chọn kiểu **iPhone** (mặc định) hoặc **Samsung** trong bảng tinh chỉnh (⌨).
- **Đổi màu bàn phím**: nhiều tông màu (Mặc định, Xanh dương, Xanh lá, Xanh ngọc, Tím, Hồng, Cam, Xám), áp dụng cho cả iPhone & Samsung, tự theo sáng/tối hệ thống.
- **Popup ký tự** hiện lên khi nhấn phím với hiệu ứng hiện/ẩn mượt (kiểu iPhone). Trên **màn hình trong** popup được **phóng to gấp đôi** (khung và chữ to gấp 2) để dễ nhìn khi gõ hai tay.
- Phản hồi **rung/haptic** và **âm thanh gõ** khi gõ phím (âm thanh có nhiều lựa chọn).
- Bố cục QWERTY.
- **Shift**: chạm 1 lần = viết hoa 1 chữ, chạm đúp = Caps Lock.
- **Giữ phím xóa** để xóa liên tục.
- **Tự viết hoa** đầu câu.
- **Chạm đúp dấu cách** = chấm + cách.
- **Tự sửa chính tả** (tuỳ chọn): khi bấm dấu cách, bàn phím tự sửa từ gõ sai (dựa trên từ điển sửa lỗi phổ biến và trình kiểm tra chính tả của hệ thống/thiết bị, nếu có), giữ kiểu viết hoa.
- 2 trang ký hiệu (**123** và **#+=**).
- Nút **return** tự đổi theo ô nhập (Go, Tìm, Gửi, Tiếp, Xong, ...).
- Phím **VI / EN** để chuyển giữa tiếng Việt (Telex) và tiếng Anh, có nhớ lựa chọn.

## Bố cục theo màn hình gập/mở (Oppo Find N5)

- **Màn hình trong (mở ra, màn hình lớn)**: tự động dùng **bàn phím tách đôi 2 bên** (kiểu Gboard, có khoảng trống ở giữa) kèm **dãy số 1-0** ở trên để tiện gõ 2 tay.
  - Có phím **G** bổ sung bên trái **H** (nửa phải) và phím **V** bổ sung bên phải **C** (nửa trái) cho cân bằng khi gõ hai tay.
  - **Dấu cách** là **một phím liền dài** (chung) trên giao diện màn hình trong.
- **Màn hình ngoài (gập lại, màn hình nhỏ)**: tự động dùng **bàn phím bình thường** (gộp, full chiều ngang).
- Bàn phím tự chuyển khi bạn gập/mở máy.

### Tinh chỉnh (nút ⌨) — riêng biệt cho từng màn hình và từng chiều

- **Tất cả tinh chỉnh được lưu RIÊNG BIỆT** cho **màn hình trong / màn hình ngoài** và cho **chiều dọc / chiều ngang** (bố cục, chiều cao, lề mép, khoảng cách/kích thước phím, giao diện, màu, âm thanh, tự sửa lỗi, vị trí/kích thước chế độ nổi). Bạn có thể tinh chỉnh khác nhau cho từng trường hợp.
- Bấm nút **⌨** trên thanh công cụ để mở bảng tinh chỉnh (bảng hiển thị đang chỉnh màn hình/chiều nào):
  - **Trên màn hình trong**: chọn kiểu bố cục **Bình thường / Tách đôi / Nổi**.
    - **Tách đôi** (mặc định): hai nửa bàn phím tách sang hai bên (kiểu Gboard) + dãy số, dấu cách liền.
    - **Nổi**: bàn phím nổi có thể di chuyển (kéo thanh “⠿ Kéo để di chuyển bàn phím”, dùng nút ← → ↑ ↓ và **Rộng −/+**).
    - **Bình thường**: bàn phím liền, full chiều ngang (có dãy số trên màn hình trong).
  - **Cao**: tăng/giảm chiều cao phím.
  - **Âm thanh**: chọn âm thanh gõ (**Tắt**, **Mặc định**, **Nhẹ**, **Click**, **Rõ**).
  - **Sửa lỗi**: bật/tắt tự sửa chính tả khi bấm dấu cách.
  - **Giao diện**: chuyển **iPhone / Samsung**.
  - **Màu**: đổi màu bàn phím.
  - **Tinh chỉnh sâu** (Tinh chỉnh ▼/▲): điều chỉnh chi tiết kích thước/khoảng cách
    - **Lề**: khoảng cách từ mép màn hình vào giữa.
    - **Ngang**: khoảng cách ngang giữa các phím.
    - **Dọc**: khoảng cách dọc giữa các phím.
  - **Trên màn hình ngoài**: luôn dùng bàn phím bình thường, nhưng mọi tinh chỉnh trên được lưu **riêng** cho màn hình ngoài (và riêng theo chiều dọc/ngang).

## Gõ tiếng Việt (Telex, bố cục QWERTY)

- Kiểu gõ **Telex trên QWERTY** (tương tự Gboard): gõ chữ cái và phím dấu trực tiếp trên bàn phím QWERTY.
- Dấu thanh: `s`=sắc, `f`=huyền, `r`=hỏi, `x`=ngã, `j`=nặng, `z`=xóa dấu
- `aa`=â, `ee`=ê, `oo`=ô, `aw`=ă, `ow`=ơ, `uw`=ư, `dd`=đ, `uow`=ươ
- Gõ lặp lại phím vừa tạo dấu thì hủy dấu (`aaa`=aa, `ss`=s, `ww`=w)
- Ví dụ: `Vieetj` = Việt, `dduowcj` = được, `nguowif` = người
- Tự tắt Telex ở ô mật khẩu, email và địa chỉ web

## Nhập liệu giọng nói

- **🎤 (Mic)**: nhập liệu giọng nói bằng **tiếng Việt**. Lời nói được nhận dạng và **hiện song song (trực tiếp) lên khung chat** trong khi bạn đang nói, và chốt văn bản khi kết thúc.
- **VI→EN**: nói bằng **tiếng Việt**, tự dịch sang **tiếng Anh** và **hiện song song (trực tiếp) lên khung chat** ngay khi bạn nói. Khi bấm lại nút **⏹** để **ngắt thu âm**, bản dịch tiếng Anh được **chuyển dịch và chèn ngay lập tức** lên khung chat (nếu dịch lỗi sẽ chèn nguyên văn tiếng Việt và báo trạng thái).
- Cần quyền **Microphone** (`RECORD_AUDIO`) và **mạng/Internet**. Nhận dạng giọng nói và dịch sử dụng dịch vụ của Google trên thiết bị/mạng (kết quả và độ trễ phụ thuộc thiết bị, kết nối).

## Dịch

Bấm nút **Dịch** trên thanh phía trên bàn phím:

- Khi mở bảng **Dịch**, bạn **gõ trực tiếp vào ô văn bản trong bảng**; bản dịch sẽ **hiện thẳng, song song lên khung chat/ô nhập** của ứng dụng ngay khi bạn gõ (không có ô “Bản dịch sẽ hiện ở đây” riêng — mọi kết quả đều đẩy thẳng lên khung chat). Telex tiếng Việt vẫn hoạt động trong ô này.
- Khi đang soạn văn bản (chưa gửi), bấm **Dịch** sẽ lấy nội dung đang gõ/đang chọn, **dịch và thay thế trực tiếp** vào khung chat/ô nhập.
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
6. Mở app **SamKeyboard** vừa cài, làm theo 2 bước trên màn hình để bật và chọn bàn phím, và cấp quyền **Micro** nếu dùng nhập liệu giọng nói.

Muốn có file APK để gửi cho máy khác: menu **Build > Build Bundle(s) / APK(s) > Build APK(s)**.

## Cấu trúc code

- `app/src/main/java/com/example/samkeyboard/SamKeyboardService.kt`: toàn bộ bàn phím (bố cục, phím, popup, xử lý gõ, bảng dịch song song, tách đôi/nổi/chiều cao, theme/màu, âm thanh, tinh chỉnh lề/khoảng cách riêng từng màn hình & chiều, dãy số, dấu cách liền, tự sửa chính tả, nhập liệu giọng nói song song, tối ưu gập/mở)
- `app/src/main/java/com/example/samkeyboard/TelexEngine.kt`: bộ gõ Telex (QWERTY)
- `app/src/main/java/com/example/samkeyboard/SettingsActivity.kt`: màn hình hướng dẫn bật bàn phím
- `app/src/main/res/xml/method.xml`: khai báo bàn phím với hệ thống
- `app/src/main/AndroidManifest.xml`: quyền Internet, rung, micro và khai báo IME
