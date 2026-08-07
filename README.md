# 佐臻 AR 眼鏡硬體驗證 APP

這是以 JJSDK v1.3.3 製作的 Android 驗證專案，用來測試：

- 佐臻眼鏡 RGB 鏡頭即時預覽
- 相機影像幀是否持續傳入
- ToF 裝置連線狀態
- ToF 內建手勢辨識事件

## 必要條件

- Android 8.1（API 27）以上手機
- USB-C 支援 DP Alt Mode
- 手機可對眼鏡提供足夠電力
- 眼鏡型號具備 ToF 感測器
- ToF 韌體 v1.2.2 以上，才能收到手勢事件

## 建置方式

1. 使用 Android Studio 開啟 `JorjinARVerifier` 資料夾。
2. 確認 `app/libs/jjsdk.aar` 已存在。
3. 等待 Gradle Sync 完成。
4. 將手機開啟「開發人員選項」與「USB 偵錯」。
5. Build > Build APK(s)，或直接 Run 到手機。
6. 安裝後允許相機權限，再接上佐臻眼鏡。

## 驗證標準

- 背景出現眼鏡鏡頭即時影像。
- 「影像幀」數字持續增加。
- ToF 顯示「已連接，等待手勢」。
- 揮動手勢後顯示 UP、DOWN、LEFT、RIGHT、PUSH、PULL、HALT 或 PRESENCE。

## 已知限制

- SDK 的 USB 權限由 JJSDK 內部處理；首次連接時 Android 可能顯示 USB 授權視窗。
- 部分佐臻眼鏡沒有 ToF，即使鏡頭正常也不會有手勢事件。
- 強烈日光或其他紅外線光源可能降低 ToF 辨識效果，建議先在一般室內 LED 照明環境測試。
- 本專案只能完成編譯與程式結構驗證；相機、ToF、DP Alt Mode 與供電仍須接上實機確認。
