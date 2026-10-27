[README](../README.md) · [バックアップ](backup.md) · [位置・天気](location-weather.md) · [開発](development.md) · [公開](releasing.md) · [テスト](testing.md)

# 開発・ビルド

## 環境・採用技術

| 項目 | バージョン |
| --- | --- |
| JDK | Gradle・コンパイルは17、API 36を含むテスト実行用に21も必要 |
| Gradle | 8.13（Wrapper同梱、SHA-256検証あり） |
| Android Gradle Plugin | 8.13.2 |
| Kotlin / Compose Compiler plugin | 2.2.21 |
| Compose BOM | 2025.10.00 |
| Activity Compose | 1.11.0 |
| APK署名の検証 | apksig 8.13.2（アプリ内更新） |
| Room | 2.8.3（Kotlin kaptでDAO生成） |
| Lifecycle | 2.9.4 |
| テスト | JUnit 4.13.2 / Robolectric 4.16.1 / Coroutines Test 1.10.2 |
| Android compileSdk / targetSdk | 36 |
| Android SDK Build Tools | 36.0.0 |
| minSdk | 26（Android 8.0） |

AGP 8.13をサポートするAndroid Studio（例：Otter 2025.2.1以降の互換版）、
またはJDKとAndroid SDK Command-line Toolsを用意してください。
依存関係とプラグインのバージョンは [gradle/libs.versions.toml](../gradle/libs.versions.toml)、
SDK設定は [app/build.gradle.kts](../app/build.gradle.kts)、Gradleは [gradle/wrapper/gradle-wrapper.properties](../gradle/wrapper/gradle-wrapper.properties) で管理します。

## ローカルビルド

1. リポジトリを取得し、ルートディレクトリへ移動します。
2. Android Studioでルートを開き、Gradle Syncを実行します。
   SDK ManagerからAndroid SDK Platform 36、Build Tools 36.0.0、Platform Toolsを導入し、ライセンスを確認してください。
3. CLIの場合はSDKの場所を `ANDROID_HOME` に指定するか、ルートの `local.properties` に
   `sdk.dir=/path/to/Android/Sdk` を設定します。このファイルはコミットしません。

GradleとアプリのJava 17ターゲットに加え、Robolectric API 36用のJava 21を用意します。
Gradleは通常のインストール探索と、`gradle.properties` で指定した `JAVA_HOME_21_X64` からテスト用JavaLauncherを探します。
macOS / Linuxの例（実際のパスへ置き換えてください）：

```sh
export JAVA_HOME="/path/to/jdk-17"
export JAVA_HOME_21_X64="/path/to/jdk-21"
export ANDROID_HOME="/path/to/Android/Sdk"
JAVA_HOME="$JAVA_HOME_21_X64" "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "platforms;android-36" "build-tools;36.0.0" "platform-tools"
JAVA_HOME="$JAVA_HOME_21_X64" "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses
./gradlew testDebugUnitTest assembleDebug lintDebug
```

WindowsのコマンドプロンプトではJDK 17をJAVA_HOME、JDK 21を次の変数に設定し、SDKも同様に用意します：

```bat
set JAVA_HOME_21_X64=C:\path\to\jdk-21
gradlew.bat testDebugUnitTest assembleDebug lintDebug
```

APKは `app/build/outputs/apk/debug/app-debug.apk` に生成されます。
Lintレポートは `app/build/reports/lint-results-debug.html` です。
ユニットテストは `app/build/reports/tests/testDebugUnitTest/index.html` を確認してください。
Robolectric API 28でRoomの保存・取得・編集・削除、DB再オープン、400件の同時刻投稿の並び順を検証し、
ViewModelで空白防止・二重送信防止・保存失敗時の入力保持・編集削除の再試行を検証します。
初回テスト実行にはMaven CentralからRobolectricのAndroidランタイムを取得する通信が必要です。
位置のOSアダプターはAPI 28/31/36、非同期GeocoderはAPI 33でも検証します。位置・天気の失敗経路は偽実装に差し替え、権限なし・各段階のタイムアウト・API障害・編集削除との競合を検証します。
保存済みv1スキーマからDBを作成し、v2マイグレーション後の本文・ID・時刻・順序と新規投稿を検証します。
バックアップは空・日本語/改行/絵文字・null属性・多件数・大きな連番の往復、不正JSON/サイズ拒否、
置換の明示確認・キャンセル・読み書き失敗、DB挿入失敗/トランザクション中断のロールバック、古いメタデータ拒否も検証します。
テストは実GPS・外部天気APIを利用しません。
Roomスキーマは `app/schemas` に保存します。将来変更時はバージョンを上げてマイグレーションを実装し、既存データを破棄しないでください。
UI自動テストはまだ含みません。
Android StudioではAPI 26以上の端末・エミュレーターを選んでRunしてください。

## データ構造

UUIDを投稿ID、UTC epochミリ秒を作成日時に使い、同時刻の表示順はローカル連番で保ちます。
Room DBはversion 2です。`Migration(1, 2)`で地名・天気コード・気温・推定時刻のnullable列を追加し、本文・ID・日時・順序を保持します。
既存投稿へ属性を一括補完せず、破壊的マイグレーションを使いません。JSONのschemaVersionはDBとは別管理です。

## GitHub Actionsからdebug APKを取得

プッシュ・Pull Requestで `.github/workflows/android.yml` がユニットテスト・ビルド・Lintを実行します。
Actions画面から手動実行もできます。

1. GitHubの **Actions → Android debug APK** で対象コミットの成功した実行を開きます。
2. **Artifacts** の `memonow-debug-<実行番号>` をダウンロードします（GitHubへのログインが必要です）。
3. ZIPを展開し、`app-debug.apk` を取り出します。ワークフローでは保存期間1日を指定しています。実際の期限はActions画面のExpires表示を確認してください。長期保管が必要なAPKは期限前に取得してください。

debug APKは開発用の自動生成鍵で署名されます。CIや別PCで鍵が異なると上書きできず、アンインストールで記録が消えます。
検証には別端末やエミュレーターを使い、既存データのあるインストールを切り替える前に[JSON保存・復元](backup.md)を確認してください。
署名・移行の詳細は[公開と更新](releasing.md)を参照してください。

## 実機インストール・起動確認

Android 8.0以上の端末でUSBデバッグを有効にし、PCの接続を許可します。
Android SDK Platform Toolsの `adb` を使用してください。

```sh
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
# Actionsから取得した場合は、展開先のapp-debug.apkを指定
adb shell am start -n com.yantkys.memonow/.MainActivity
```

手動の確認項目は[テストガイド](testing.md)を参照してください。APKをファイル転送して開く場合は、そのファイル管理アプリからの「不明なアプリのインストール」を一時的に許可します。
