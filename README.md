# SamKeyboard

Bàn phím Android kiểu Samsung, viết bằng Kotlin. Không quảng cáo, không thu thập dữ liệu.

## Chuyển ngôn ngữ và gõ tiếng Việt (Telex)

Phím **VI / EN** ở hàng dưới cùng để chuyển giữa tiếng Việt (gõ Telex) và tiếng Anh. Bàn phím nhớ lựa chọn lần trước.

- Dấu thanh: s=sắc, f=huyền, r=hỏi, x=ngã, j=nặng, z=xóa dấu
- aa=â, ee=ê, oo=ô, aw=ă, ow=ơ, uw=ư, dd=đ, uow=ươ
- Gõ lặp lại phím vừa tạo dấu thì hủy dấu (aaa=aa, ss=s, ww=w)
- Ví dụ: `Vieetj` = Việt, `dduowcj` = được, `nguowif` = người
- Tự tắt Telex ở ô mật khẩu, email và địa chỉ web

## Dịch

Bấm nút **Dịch** trên thanh phía trên bàn phím:
- Chạm "Dịch sang: ..." để đổi giữa English, 繁體中文 (Hoa phồn thể), မြန်မာ (Myanmar), rồi bấm **Dịch**.
- Tự nhận diện ngôn ngữ đang gõ.
- Có bôi đen đoạn chữ thì chỉ dịch đoạn đó, không thì dịch toàn bộ chữ trong ô rồi thay thế.
- Cần có mạng. Khi bấm Dịch, đoạn chữ cần dịch được gửi lên dịch vụ dịch của Google. Bàn phím không gửi gì khác.
- Lưu ý: app dùng đường dẫn dịch không chính thức của Google (không cần khóa API), nên có thể bị giới hạn hoặc ngừng hoạt động.

## Tính năng

- Bố cục QWERTY có hàng số trên cùng
- Shift: chạm 1 lần = viết hoa 1 chữ, chạm đúp = Caps Lock
- Giữ phím xóa để xóa liên tục
- Tự viết hoa đầu câu
- Chạm đúp dấu cách = chấm + cách
- 2 trang ký hiệu (?123 và =\<)
- Rung nhẹ khi bấm
- Tự đổi sáng/tối theo điện thoại
- Nút Enter tự đổi theo ô nhập (Tìm kiếm, Gửi, Xong...)

## Cách build và cài

1. Cài **Android Studio** (bản mới nhất) trên máy tính.
2. Giải nén file zip, mở Android Studio, chọn **Open** và chọn thư mục `SamKeyboard`.
3. Đợi Gradle đồng bộ xong (lần đầu cần mạng để tải thư viện, bấm OK nếu nó hỏi cài thêm SDK).
4. Trên điện thoại: bật **Tùy chọn nhà phát triển** và **Gỡ lỗi USB**, cắm cáp vào máy tính.
5. Bấm nút **Run** (tam giác xanh) trong Android Studio để cài lên điện thoại.
6. Mở app **SamKeyboard** vừa cài, làm theo 2 bước trên màn hình để bật và chọn bàn phím.

Muốn có file APK để gửi cho máy khác: menu **Build > Build Bundle(s) / APK(s) > Build APK(s)**.

## Cấu trúc code

- `SamKeyboardService.kt`: toàn bộ bàn phím (bố cục, phím, xử lý gõ)
- `SettingsActivity.kt`: màn hình hướng dẫn bật bàn phím
- `res/xml/method.xml`: khai báo bàn phím với hệ thống
