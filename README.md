# VehicleStateDemo (AAOS)

車両の UX Restriction（`CarUxRestrictionsManager`）を監視し、画面中央に
状態を表示するだけの Android Automotive OS サンプル。

- `UX_RESTRICTIONS_NO_VIDEO` が有効 → **走っています**
- 無効 → **止まっています**

速度値を直接見るのではなく AAOS が算出した UX Restriction を監視するため、
動画アプリなど実車向けの表示制御に近い挙動になる。

## 起動方法

1. Android Studio の Device Manager で **Automotive** システムイメージの
   Emulator を作成・起動する。
2. `app` を Run（または `./gradlew installDebug`）でインストール。
3. アプリを起動すると中央に「走っています」/「止まっています」が表示される。

状態はログにも出る:

```
adb logcat -s VehicleStateDemo
# 例: activeRestrictions=0x1, moving=true
```

## ADB による確認（VHAL イベント注入）

### 速度を変更する（PERF_VEHICLE_SPEED = 0x11600207）

```bash
# 停止相当
adb shell dumpsys activity service com.android.car inject-vhal-event 0x11600207 0

# 走行相当（10 m/s）
adb shell dumpsys activity service com.android.car inject-vhal-event 0x11600207 10
```

### ギアを変更する（GEAR_SELECTION = 0x11400400）

停止として扱わせるには Park が必要な場合がある（下記「制限事項」参照）。

```bash
# Park（停止扱い）  VehicleGear.GEAR_PARK = 4
adb shell dumpsys activity service com.android.car inject-vhal-event 0x11400400 4

# Drive（走行扱い） VehicleGear.GEAR_DRIVE = 8
adb shell dumpsys activity service com.android.car inject-vhal-event 0x11400400 8
```

## 制限事項

- AAOS の Driving State は速度だけでなくギア状態にも依存する。
  速度 0 を注入しただけでは `NO_VIDEO` が解除されず「走っています」の
  ままになることがある。その場合は **ギアを Park（`0x11400400` に `4`）**
  にしてから速度 0 を注入すると「止まっています」に切り替わる。
- VHAL の property ID / gear の値は Emulator のイメージによって異なる場合が
  ある。うまく切り替わらないときは以下で対象イメージの定義を確認する:

  ```bash
  adb shell dumpsys activity service com.android.car | grep -i gear
  ```

- 本アプリは `android.hardware.type.automotive` を必須とする AAOS 専用アプリ
  のため、通常のスマホ／タブレット Emulator にはインストールできない。
- 走行中は AAOS が非最適化アプリを標準のブロック画面で覆う。本アプリは自前の
  「走っています」を見せるため Manifest で `distractionOptimized=true` を宣言
  している。この宣言を外すと走行中はブロック画面に切り替わる。
