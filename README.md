# 佐臻 AR 硬體驗證

使用 **JJSDK v1.3.3** 在 Android 手機驗證佐臻 J-Reality AR 眼鏡 RGB 相機與 ToF 手勢的診斷 APP。它只使用眼鏡的 `CameraManager` 與 `TofManager`，不會以 CameraX、手機鏡頭或影像辨識代替眼鏡硬體。

## 支援功能

- 眼鏡 RGB 相機即時全螢幕預覽、SDK 解析度顯示及累計影像幀（每 500 ms 更新 UI）。
- ToF 插拔狀態與韌體版本顯示；辨識 UP、DOWN、LEFT、RIGHT、PULL、PUSH、HALT、PRESENCE。
- 僅處理 `ACTION_RECEIVED`，同一手勢有 300 ms 防重複。
- Android 相機執行時權限、空解析度、USB 未授權/未連接、啟動失敗、不支援 ToF 與中斷提示。
- 進入背景釋放兩套 SDK 資源，返回前景重建；「重新連接」安全地停止、清除並重新初始化。
- 支援 `arm64-v8a` 與 `armeabi-v7a`，最低 Android 8.1（API 27），直向操作：預覽在上、狀態面板在下，方便一手持機、一手在 ToF 前比手勢。

## 建置與 APK 產出

必要環境為 JDK 17、Android SDK 35 與 Android Studio（Ladybug 或更新版本建議）。repo 已含 `app/libs/jjsdk.aar`；此檔不可是空檔或 Git LFS placeholder。

此 AAR 是原廠提供的必要封閉源碼相依，不是 APP 的編譯產物。有效的原廠 AAR 已存在於 repository 歷史物件 `ca510d9dc8680423da77be49aeb89eaa788e1bea`；由於變更審查介面拒絕二進位 patch，Android Studio Sync 或任何 Gradle 指令會先在 `settings.gradle` 自動從該 Git 物件還原 `app/libs/jjsdk.aar`，不會在 PR 再加入一份二進位檔。淺層 clone 請先執行 `git fetch --unshallow`。`preBuild` 接著驗證 AAR 大小、ZIP 結構、`classes.jar` 及兩個必要 ABI；CI 另以 SHA-256 `95847781d88ebe35cd54da5e427a2fce4071208800f42401c7378762f4c0cdea` 防止錯誤檔案被發布。

```bash
./gradlew clean testDebugUnitTest lintDebug assembleDebug
mkdir -p dist
cp app/build/outputs/apk/debug/app-debug.apk dist/JorjinARVerifier-v1.0.0-debug.apk
```

Android Studio 可直接開啟 repo 根目錄，等待 Gradle Sync 後選 **Build > Build APK(s)**。原始輸出在 `app/build/outputs/apk/debug/app-debug.apk`，交付檔在 `dist/JorjinARVerifier-v1.0.0-debug.apk`。

## 安裝與連接

```bash
adb install -r dist/JorjinARVerifier-v1.0.0-debug.apk
```

1. 確認手機為 Android 8.1 以上，USB-C 支援 **DisplayPort Alt Mode**，且可提供眼鏡足夠電力；供電不足時使用相容的供電/轉接配置。
2. 以 USB-C 連接 J-Reality 眼鏡並啟動 APP。
3. 首次啟動會出現**兩個**授權：Android 相機執行時權限，以及**眼鏡的 USB 裝置授權**，兩個都要允許（USB 那個可勾選記住裝置）。APP 不會自行偽造或繞過授權。

   JJSDK 透過 libusb/UVC 直接存取 `/dev/bus/usb`，Android 的相機權限**不會**授予這條路徑；沒有 USB 裝置授權時 `UsbManager.openDevice()` 回傳 null，畫面會顯示「鏡頭：已啟動」但影像幀永遠停在 0。因此 APP 會在啟動 SDK 前自行以 `UsbManager.requestPermission()` 取得授權，取得後才建立 `CameraManager`。
4. 確認預覽出現、解析度有效且影像幀持續增加，再於 ToF 前方測試手勢。
5. 中斷或授權後未啟動時，重新接妥再按「重新連接」。

## ToF 測試限制

眼鏡必須是配有 ToF 的型號；內建手勢要求 **ToF 韌體 v1.2.2 以上**。APP 會嘗試透過 SDK 顯示韌體版本；顯示「無法確認」時需向設備管理者/佐臻確認版本。一般室內 LED 照明最適合先行測試；強烈日光、紅外線補光燈或其他紅外線光源可能干擾 ToF。DP Alt Mode 主要影響眼鏡顯示輸出，USB 資料連線及充足供電仍是 RGB/ToF 驗證的必要條件。

## 常見故障排除

| 畫面訊息/現象 | 處理方式 |
|---|---|
| 未取得相機權限 | 到 Android「設定 > 應用程式 > 佐臻 AR 硬體驗證 > 權限」允許相機，再按重新連接。 |
| RGB 啟動失敗或解析度為空 | 拔插 USB-C、接受 USB 授權，關閉其他占用眼鏡相機的 APP，確認供電後重試。 |
| 有預覽但幀數不增加 | 按重新連接；仍無效時重開眼鏡並查看 `adb logcat -s JorjinVerifier`。 |
| 未取得眼鏡 USB 授權 | 拔插 USB-C 後按重新連接，並在系統授權視窗選允許。若先前誤按拒絕且勾了記住，需到「設定 > 應用程式 > 預設應用程式 > USB」清除預設值。 |
| 未偵測到眼鏡 | 換一條具資料傳輸能力的 USB-C 線（純充電線不會枚舉裝置），確認眼鏡供電充足後按重新連接。 |
| ToF 不支援 | 確認眼鏡型號包含 ToF、韌體至少 v1.2.2、USB 授權及線材具資料傳輸能力。 |
| ToF 已中斷 | 檢查接頭/供電，避開僅充電線，接妥後按重新連接。 |
| 手勢不穩定 | 改在室內 LED 環境，避開日光與紅外線光源，並確認韌體版本。 |
| 返回 APP 無畫面 | APP 在背景會刻意釋放硬體；返回後稍候初始化，必要時按重新連接。 |

一般使用者只會看到簡短錯誤；完整例外會寫入 Logcat，不會把 stack trace 顯示在畫面。

## ToF 手勢如何在不開佐臻 App 的情況下運作

**ToF 是與 RGB 相機不同的 USB 裝置。** JJSDK 內部以 `vendorId & 0xFF | (productId & 0xFF) << 16`
查表辨識裝置：相機在 `c.a`、ToF 在 `c.k`。`c.k` 只有兩筆，對應
`0x0483:0x5740`（STM32 虛擬序列埠）與 `0x350E:0x3723`；原廠 `J-Reality-Gesture` 的
`res/xml/device_filter.xml` 列的是同一組 ToF 描述子，另外多一筆 `0x350E:0x3501`（JJSDK 1.3.3
未收錄）與 `0x0483:0xDF11`（DFU 韌體更新模式）。ToF 走的是 CDC-ACM，不是 UVC，所以
**取得相機的 USB 授權完全不代表 ToF 也有授權**。

JJSDK 雖然有自行補請求 ToF 授權的路徑（`d.a`），但那條路徑幾乎是一次性的：建構 manager 後
300 ms 只排程一次掃描，之後僅在 `USB_DEVICE_ATTACHED`/`DETACHED` 或 SDK 自己的
`com.jorjin.jjsdk.USB_PERMISSION` 廣播才重新排程，且所有請求都被同一個「已有請求進行中」旗標擋住，
一旦某個子系統的請求位元被清掉就不會再設回來。用**我們自己的**對話框拿到的授權不會重新觸發這條路徑，
於是 `TofManager` 常常從未拿到裝置、從未開啟 CDC 埠、也就從未送出任何手勢事件——
而相機因為剛好在正確的時機點持有授權，看起來一切正常。

因此 APP 現在的作法是：先枚舉全部 USB 裝置並以同一套規則分類，**在建立任何 SDK manager 之前**
自行取得相機與 ToF 兩個裝置的授權；等 SDK 那唯一一次掃描執行時，兩個裝置都已是 `hasPermission`，
第一輪就會把 ToF 交給 `TofManager`。此外 `AndroidManifest.xml` 也宣告了
`USB_DEVICE_ATTACHED` 與 `res/xml/device_filter.xml`，插上眼鏡時系統會直接把授權給本 APP。
若 ToF 已授權卻仍未就緒，watchdog 會重建 `TofManager`（其建構子會重新排程 SDK 的裝置探索），最多三次。

`TofManager.isDeviceSupportToF()` 的實作與名稱相反（尚未找到 ToF 時回傳 `true`），且裝置枚舉是非同步的，
所以絕不能用它決定是否釋放 ToF。手勢事件另外要求 ToF 韌體 **v1.2.2 以上**：SDK 會把版本字串折成整數，
低於門檻就整段不送 gesture callback，面板上的 **ToF Firmware** 因此是必看欄位。

全程不需要、也不會啟動、繫結或依賴原廠 `J-Reality-Gesture` App；repo 內的 APK 僅作靜態比對用途。

## 診斷面板

畫面下半部逐層顯示，實機測試時可直接判斷卡在哪一層：

```text
RGB Camera：Connected / Failed
ToF USB：Detected / Not detected
ToF USB Permission：Granted / Missing
ToF Manager：Opened / Failed
ToF State：Ready / Waiting
ToF Firmware：x.x.x
Gesture Listener：Registered
最後手勢：PUSH
手勢次數：12（原始事件 27）
```

面板底部另有完整 USB 枚舉（vendorId／productId／deviceId／deviceClass／interface 數量與每個
interface 的 class/subclass/protocol／授權狀態），同一份內容也寫進 Logcat 的 `JorjinVerifier` tag。
每一筆手勢事件在防重複判斷**之前**就會寫入 Logcat 的 `JorjinGesture` tag：

```text
JorjinGesture: action=1 (ACTION_RECEIVED) gesture=5 label=PUSH timestamp=... raw=27
```

`ACTION_RECEIVED` 才會更新「最後手勢」；300 ms 防重複只針對相同的 (action, gesture) 組合，
被濾掉時也會留下一行 Log，所以「完全沒有手勢」與「有手勢但被濾掉」不會混淆。

## CI 與下載位置

`.github/workflows/build-android.yml` 會在 push、pull request 與手動觸發時執行單元測試與 `assembleDebug`，確認 APK 存在後上傳名為 **JorjinARVerifier-debug-apk** 的 Artifact。Lint 在 APK 產出**之後**才執行且只作報告（`continue-on-error`），因此樣式類的 Lint 問題不會擋住可測試的 APK；報告另存為 **lint-report** Artifact。

取得 APK 有兩種方式：

1. **GitHub Release（手機最方便）**：push 到 `main` 後會更新名為 `debug-latest` 的 pre-release，直接用手機瀏覽器開啟 <https://github.com/ericingptt/ar-app/releases/tag/debug-latest> 點 `.apk` 即可下載安裝，不需解壓縮。若儲存庫未開放 workflow 的 `contents: write` 權限，此步驟會被跳過而不影響建置，改用下面的 Artifact。
2. **Actions Artifact**：**Actions > Build Android APK > 該次成功執行 > Artifacts > JorjinARVerifier-debug-apk**。下載的是 zip，需先解壓縮再安裝。

- 本機/交付位置：`dist/JorjinARVerifier-v1.0.0-debug.apk`
- Gradle 原始位置：`app/build/outputs/apk/debug/app-debug.apk`
- GitHub Actions Artifact：`JorjinARVerifier-debug-apk/JorjinARVerifier-v1.0.0-debug.apk`

手機直接安裝時，需先在 Android「設定 > 應用程式 > 特殊存取權 > 安裝未知應用程式」允許瀏覽器或檔案管理員安裝。此為 debug 簽章的測試版本，無法上架，也可能與正式簽章版本衝突而需先移除舊版。

> CI 只能驗證程式、Manifest、ABI 與封裝。RGB 預覽、實際幀輸入、USB 授權流程、ToF 手勢、DP Alt Mode 和供電必須使用指定手機與眼鏡實機驗證。
