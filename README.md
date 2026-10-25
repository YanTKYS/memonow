# memonow

「その瞬間のことを一文で残す」個人向けライフログAndroidアプリ。
v0.1.0 は起動可能なプロジェクトの土台です。初期画面には `memonow` と説明文を表示します。
投稿・保存・位置情報・天気・JSON入出力はまだ実装していません。将来の記録の正本は端末内とし、手動JSONバックアップ・復元を予定しています。
位置情報やネットワーク等の権限は要求せず、外部通信も行いません。Androidの自動バックアップは無効にしています。

## 前提環境・採用バージョン

| 項目 | バージョン |
| --- | --- |
| JDK | 17（CI）または21 |
| Gradle | 8.13（Wrapper同梱、SHA-256検証あり） |
| Android Gradle Plugin | 8.13.2 |
| Kotlin / Compose Compiler plugin | 2.2.21 |
| Compose BOM | 2025.10.00 |
| Activity Compose | 1.11.0 |
| Android compileSdk / targetSdk | 36 |
| Android SDK Build Tools | 36.0.0 |
| minSdk | 26（Android 8.0） |

AGP 8.13をサポートするAndroid Studio（例：Otter 2025.2.1以降の互換版）、
またはJDKとAndroid SDK Command-line Toolsを用意してください。
依存関係とプラグインのバージョンは `gradle/libs.versions.toml`、
SDK設定は `app/build.gradle.kts`、Gradleは `gradle/wrapper/gradle-wrapper.properties` で管理します。

## ローカルビルド

1. リポジトリを取得し、ルートディレクトリへ移動します。
2. Android Studioでルートを開き、Gradle Syncを実行します。
   SDK ManagerからAndroid SDK Platform 36、Build Tools 36.0.0、Platform Toolsを導入し、ライセンスを確認してください。
3. CLIの場合はSDKの場所を `ANDROID_HOME` に指定するか、ルートの `local.properties` に
   `sdk.dir=/path/to/Android/Sdk` を設定します。このファイルはコミットしません。

```sh
# CLIによるSDKパッケージ導入（sdkmanagerはCommand-line Toolsに含まれます）
sdkmanager "platforms;android-36" "build-tools;36.0.0" "platform-tools"
sdkmanager --licenses

# macOS / Linux
./gradlew assembleDebug lintDebug

# Windows（コマンドプロンプト）
gradlew.bat assembleDebug lintDebug
```

APKは `app/build/outputs/apk/debug/app-debug.apk` に生成されます。
Lintレポートは `app/build/reports/lint-results-debug.html` です。
今回の自動検証はAPKビルドとAndroid Lintです。UI自動テストはまだ含みません。
Android StudioではAPI 26以上の端末・エミュレーターを選んでRunしてください。

## GitHub ActionsからAPKを取得

プッシュ・Pull Requestで `.github/workflows/android.yml` がビルドとLintを実行します。
Actions画面から手動実行もできます。

1. GitHubの **Actions → Android debug APK** で対象コミットの成功した実行を開きます。
2. **Artifacts** の `memonow-debug-<実行番号>` をダウンロードします（GitHubへのログインが必要です）。
3. ZIPを展開し、`app-debug.apk` を取り出します。保存期間は14日です。

debug APKは開発用の自動生成鍵で署名され、Play Store配布用ではありません。
CIや別PCでは署名鍵が異なるため、既存インストールの更新に失敗することがあります。
その場合は既存アプリをアンインストールして再インストールします（アプリデータは削除されます）。
署名鍵や秘密情報はリポジトリへ追加しないでください。

## 実機インストール・起動確認

Android 8.0以上の端末でUSBデバッグを有効にし、PCの接続を許可します。
Android SDK Platform Toolsの `adb` を使用してください。

```sh
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
# Actionsから取得した場合は、展開先のapp-debug.apkを指定
adb shell am start -n com.yantkys.memonow/.MainActivity
```

画面に `memonow` と「その瞬間のことを、一文で残す。」が表示されること、
ホームに戻ってアプリアイコンから再起動できることを確認します。
ファイル転送でAPKを開く方法も使えます。その場合は端末側でそのファイル管理アプリからの
「不明なアプリのインストール」を一時的に許可してください。

ビルド成功だけでは実機表示や起動を保証しません。実機・エミュレーターでの表示確認と、
GitHub Actions上での実行結果はPRに個別に記載します。
