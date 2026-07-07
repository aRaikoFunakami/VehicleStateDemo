# Android Automotive OS (AAOS) の「走行中」を adb で再現する方法

## はじめに

車両が走り出した瞬間にアプリの画面が切り替わる、あの挙動を自身のアプリでも再現してみたいと思ったことはないだろうか？

AAOS Emulator に対して adb 経由で VHAL イベントを送信するだけで、車速やギアなどの車両状態を任意に変更できる。
本記事では、「車速を 10 に設定すると画面が『走っています』へ切り替わる」という動作を題材に、必要なコードとコマンドを交えながら、その実現方法を最短の手順で解説する。

## ポイント

- **車両状態の判定は速度値を直接見ず、`CarUxRestrictionsManager` の `UX_RESTRICTIONS_NO_VIDEO` を監視する。** これが動画アプリなど実車向け制御の定石で、AAOS が速度・ギアから算出した「今どこまで許すか」をそのまま使える。
- **テストは実機不要。** `adb shell dumpsys activity service com.android.car inject-vhal-event <property> <value>` で速度・ギアを注入すれば、アプリの表示が切り替わることを Emulator 上で確認できる。
- **ハマりどころは2つ。** ①表示を切り替えるのは速度ではなくギア。速度値を変えても `NO_VIDEO` は動かず、`Drive`↔`Park` で切り替わる。②走行中に自前画面を出すには Manifest の `distractionOptimized` 宣言が要る。この宣言は「アプリが動くか」ではなく「AAOS のブロック画面で覆われるか」を決めるフラグである。

以下、作ったサンプルと検証の記録。

## 作ったもの

画面中央に大きな文字を出すだけの AAOS アプリ。車両状態に応じて表示だけを切り替える。

- `UX_RESTRICTIONS_NO_VIDEO` が有効 → **走っています**
- 無効 → **止まっています**

これだけ。ロジックは Activity 1つに収まる。

| 停止中 | 走行中 |
|---|---|
| ![止まっています](docs/images/stopped.png) | ![走っています](docs/images/moving.png) |

## 実装のキモ: なぜ速度値を直接見ないのか

`CarPropertyManager` で `PERF_VEHICLE_SPEED` を読んで「0より大きければ走行」と判定することもできる。だがそれは筋が悪い。実車の「走行中は動画を出すな」というルールは、速度だけでなくギア・パーキングブレーキ・地域の法規まで含めて AAOS 側が算出している。アプリがそのロジックを再実装するのは車輪の再発明で、しかも法規変更に追従できない。

代わりに `CarUxRestrictionsManager` を監視する。AAOS が算出済みの「UX 制限」を受け取るだけでよい。動画アプリなら `UX_RESTRICTIONS_NO_VIDEO` ビットが立っているかどうかを見れば「今、映像を出していい状態か」が分かる。

接続とライフサイクルはこうなる。`Car.createCar()` で Car API に繋ぎ、`CAR_UX_RESTRICTION_SERVICE` からマネージャを取得。画面表示中だけリスナを登録し、破棄時に解除・切断してリークを防ぐ。

```kotlin
class MainActivity : ComponentActivity() {

    private var car: Car? = null
    private var uxManager: CarUxRestrictionsManager? = null
    private var moving by mutableStateOf(false)

    private val listener = CarUxRestrictionsManager.OnUxRestrictionsChangedListener { r ->
        applyRestrictions(r)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        car = Car.createCar(this)
        uxManager = car?.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager

        setContent {
            WebViewOnAndroid14Theme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (moving) "走っています" else "止まっています",
                        style = MaterialTheme.typography.displayLarge
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val m = uxManager ?: return
        m.registerListener(listener)
        applyRestrictions(m.currentCarUxRestrictions) // 初期表示を反映
    }

    override fun onStop() {
        super.onStop()
        uxManager?.unregisterListener()
    }

    override fun onDestroy() {
        super.onDestroy()
        car?.disconnect()
        car = null
    }

    private fun applyRestrictions(r: CarUxRestrictions?) {
        val active = r?.activeRestrictions ?: 0
        moving = active and CarUxRestrictions.UX_RESTRICTIONS_NO_VIDEO != 0
        Log.d(TAG, "activeRestrictions=0x${active.toString(16)}, moving=$moving")
    }
}
```

### イベントが発火してから Text が変わるまで

「ギアを変えた」→「画面の文字が変わる」までを1本の線でつなぐ。ポイントは、`applyRestrictions` を呼ぶルートが**2本**あること（初期表示とコールバック）、そして最後は Compose が**自動で**再描画すること。

```
① 準備（onCreate → onStart）
   Car.createCar()
        └─ getCarManager(CAR_UX_RESTRICTION_SERVICE) ──▶ uxManager
                                                            │
   onStart(): uxManager.registerListener(listener) ────────┘  ← listener を登録
   onStart(): applyRestrictions(currentCarUxRestrictions)      ← 初回だけ手動で1発

② 実行時（車両状態が変わるたび）
   [ギア Drive↔Park を注入]
        │  AAOS が UX 制限を再計算
        ▼
   CarUxRestrictionsManager
        │  登録済みの listener を呼ぶ（コールバック）
        ▼
   listener { r -> applyRestrictions(r) }
        │
        ▼
   applyRestrictions(r):
        moving = (r.activeRestrictions and NO_VIDEO) != 0   ← State に代入
        │
        ▼  Compose が「moving を読んでいる箇所」を検知して再実行
   Text(text = if (moving) "走っています" else "止まっています")   ← 文字が切り替わる
```

確認するべき3点

- **`applyRestrictions` の呼び出し口は2つだけ。** ①`onStart` で `currentCarUxRestrictions` を渡す初回の1発（登録直後はコールバックが来ないので現在値を手で反映）、②登録した `listener` 経由で、状態が変わるたびに `CarUxRestrictionsManager` が呼ぶ。
- **登録しているのは「関数」ではなく「関数を呼ぶラムダ」。** `registerListener` に渡すのは `applyRestrictions(r)` を呼ぶ `listener`。だから購読の登録（`onStart`）と解除（`onStop` の `unregisterListener`）が画面のライフサイクルにきれいに乗る。
- **Text を差し替えるコードは誰も呼ばない。** `moving` は `mutableStateOf` の State で、`Text` がそれを**読んでいる**。`applyRestrictions` が `moving` に代入した瞬間、Compose が依存箇所だけを再実行して文字が変わる。手続き的な `setText()` は使われない。

ビルド設定で忘れがちなのが2点。`android.car` はシステム API なので `build.gradle.kts` の `android {}` に `useLibrary("android.car")` が要る。そして Manifest には AAOS 専用であることを示す `uses-feature android.hardware.type.automotive`（required）を入れる。UX 制限の *読み取り* に追加パーミッションは不要だった。

## adb でのテスト: 実機なしで車を「走らせる」

肝はこのコマンド。`inject-vhal-event` で VHAL のプロパティに任意の値を書き込む。表示を切り替えるのはギア（`GEAR_SELECTION`）だ。

```bash
# ギア GEAR_SELECTION = 0x11400400 / VehicleGear.GEAR_PARK = 4, GEAR_DRIVE = 8
adb shell dumpsys activity service com.android.car inject-vhal-event 0x11400400 8   # Drive → 走行
adb shell dumpsys activity service com.android.car inject-vhal-event 0x11400400 4   # Park  → 停止
```

アプリのログを見ると、状態がそのまま反映されている。

```
D VehicleStateDemo: activeRestrictions=0xff, moving=true
D VehicleStateDemo: activeRestrictions=0x0,  moving=false
```

`0x10` が `UX_RESTRICTIONS_NO_VIDEO`、`0xff` は全制限が立った状態、`0x0` は制限なし。ビット演算で `NO_VIDEO` を拾えているのが確認できる。プロパティ ID やギアの値は Emulator イメージで異なることがあるので、迷ったら `adb shell dumpsys activity service com.android.car | grep -i gear` で確認する。

## ハマりどころ①: 表示を変えるのは速度ではなくギア

直感的には `PERF_VEHICLE_SPEED` に10を注入すれば「走っています」になりそうだが、ならない。速度を0↔10で振っても `NO_VIDEO` は変わらないのだ。

```bash
# 速度 PERF_VEHICLE_SPEED = 0x11600207 — これを変えても表示は切り替わらない
adb shell dumpsys activity service com.android.car inject-vhal-event 0x11600207 10
adb shell dumpsys activity service com.android.car inject-vhal-event 0x11600207 0
```

理由は AAOS の Driving State の作られ方にある。Driving State はギアを主因に `PARKED`／`IDLING`／`MOVING` に分類され、既定の UX 制限設定では「停止(`IDLING`)」も「走行(`MOVING`)」も**同じ制限セット（`NO_VIDEO` を含む）**が適用される。つまり Park を抜けた瞬間に `NO_VIDEO` が立ち、あとは速度の大小では動かない。表示を切り替えたいなら速度ではなく `Drive`↔`Park` を注入する。

## ハマりどころ②: distractionOptimized は「動くか」ではなく「覆われるか」

最初、走行状態にしたら自作の「走っています」ではなく、AAOS 標準の **"You can't use this feature while driving"** というブロック画面が出た。だがログは `moving=true` を吐き続けている。つまりアプリは裏で動いていて、AAOS が上から覆っているだけだった。

これは、アプリが「走行中に表示してよい画面」だと宣言していないための標準動作。Manifest の Activity に次を足すと解決する。

```xml
<meta-data
    android:name="distractionOptimized"
    android:value="true" />
```

`true`/`false` を切り替えて比べると挙動の意味がはっきりする。

| | 走行中の表示 | ログ |
|---|---|---|
| `distractionOptimized=true` | 「走っています」（自前画面） | `moving=true` |
| `distractionOptimized=false` | AAOS ブロック画面（裏に自前画面が透ける） | `moving=true` |

`false` のスクリーンショットではブロック画面の背後に「走っています」がうっすら透けて見えた。アプリは常に動いていて、表示だけが覆われる——という関係がそのまま可視化された形だ。

![走行中のAAOSブロック画面。背後に「走っています」が透けて見える](docs/images/blocked.png)

つまり `distractionOptimized` は「走行中にアプリを動かすか」ではなく、**「走行中に自前 UI を出す責任を開発者が負う（＝運転者気を散らさないガイドラインの順守を約束する）か」を宣言するフラグ**。中身が本当に安全かを OS はチェックしないし、量産車では OEM のホワイトリスト登録も別途必要になる。注意点として、Manifest の変更はコード修正と違い再インストール（`installDebug`）しないと反映されない。

## 小ネタ: Emulator の screencap がマルチディスプレイで壊れる

車載 Emulator は複数ディスプレイを持つため、`adb exec-out screencap -p` の出力先頭に `[Warning] Multiple displays...` という警告文字列が混ざり、PNG として壊れる。PNG のマジックバイト以降だけを取り出せば救える。

```bash
adb exec-out screencap -p > raw.bin
python3 -c 'import sys;d=open("raw.bin","rb").read();i=d.find(b"\x89PNG\r\n\x1a\n");open("shot.png","wb").write(d[i:])'
```

## まとめ

- 車両状態による表示制御は、速度を自前判定せず `CarUxRestrictionsManager` に委ねるのが筋がよい。
- `adb ... inject-vhal-event` で速度もギアも偽装でき、実機なしで挙動を検証できる。
- `NO_VIDEO` を消すにはギア Park まで含める。走行中に自前画面を出すには `distractionOptimized` を宣言する。この2つを押さえれば、AAOS の「走行中の振る舞い」は手元の Emulator で一通り再現・テストできる。

## 参照

- サンプルのソース一式: https://github.com/aRaikoFunakami/VehicleStateDemo
- Android Automotive OS のドキュメント: https://source.android.com/devices/automotive
- 運転状態と UX 制限の利用: https://source.android.com/docs/automotive/driver_distraction/consume
- VehicleProperty.aidl: https://android.googlesource.com/platform/hardware/interfaces/+/refs/heads/main/automotive/vehicle/aidl_property/android/hardware/automotive/vehicle/VehicleProperty.aidl
- VehicleGear.aidl: https://android.googlesource.com/platform/hardware/interfaces/+/refs/heads/main/automotive/vehicle/aidl_property/android/hardware/automotive/vehicle/VehicleGear.aidl
