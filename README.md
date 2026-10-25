# memonow

「その瞬間のことを一文で残す」個人向けライフログAndroidアプリ。
画面下の入力欄から投稿すると、古い記録が上・新しい記録が下に並びます。
記録は端末内のRoom（SQLite）DBに保存され、アプリを終了しても残ります。
位置情報・天気・JSON入出力はまだ実装していません。
位置情報やネットワーク等の権限は要求せず、外部通信も行いません。

## 使い方・データの扱い

- 下部の「いまの一文」に入力し「送信」。日本語・改行を含む複数行に対応し、空白だけの投稿はできません。
- 各投稿に本文と端末のローカル日時を表示します。初回起動・投稿成功時は最新側を表示し、古い履歴は上方向へスクロールして読めます。
- 「編集」で本文だけを変更できます。作成日時とIDは変わりません。「削除」には確認操作があります。
- 保存に失敗した場合は入力を保持し、エラーを表示します。画面回転時も書きかけの本文を保持します。
- データはアプリ専用領域の `databases/memonow.db`（およびSQLiteの関連ファイル）に保存します。
  IDはUUID、作成日時はUTC epochミリ秒。日時が同じ場合はローカル連番で投稿順を保ちます。
- 記録の正本は端末内です。Androidのクラウド自動バックアップと端末間転送は無効にする設定を含みます。
  Android 12以降は `dataExtractionRules`、従来版は `allowBackup=false` と `fullBackupContent` で制御します。
  **手動JSONバックアップ・復元は後続Issueで実装予定で、現在はバックアップ手段がありません。**

**debug APKで大切な記録を継続保存しないでください。** CI・別PC・ビルド環境の再生成でdebug署名鍵が変わると上書き更新できません。
アンインストールや「ストレージを消去」、端末初期化で投稿は失われ、自動バックアップからは復元できません。
更新時に署名不一致が出ても、記録が必要な端末ではアンインストールしないでください。
継続利用用の署名・データ保護はリリース準備の後続Issueで整えます。署名鍵はコミットしません。

## 前提環境・採用バージョン

| 項目 | バージョン |
| --- | --- |
| JDK | 17（CI）または21 |
| Gradle | 8.13（Wrapper同梱、SHA-256検証あり） |
| Android Gradle Plugin | 8.13.2 |
| Kotlin / Compose Compiler plugin | 2.2.21 |
| Compose BOM | 2025.10.00 |
| Activity Compose | 1.11.0 |
| Room | 2.8.3（Kotlin kaptでDAO生成） |
| Lifecycle | 2.9.4 |
| テスト | JUnit 4.13.2 / Robolectric 4.16.1 / Coroutines Test 1.10.2 |
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
./gradlew testDebugUnitTest assembleDebug lintDebug

# Windows（コマンドプロンプト）
gradlew.bat testDebugUnitTest assembleDebug lintDebug
```

APKは `app/build/outputs/apk/debug/app-debug.apk` に生成されます。
Lintレポートは `app/build/reports/lint-results-debug.html` です。
ユニットテストは `app/build/reports/tests/testDebugUnitTest/index.html` を確認してください。
Robolectric（API 28）でRoomの保存・取得・編集・削除、DB再オープン、400件の同時刻投稿の並び順を検証し、
ViewModelで空白防止・二重送信防止・保存失敗時の入力保持・編集削除の再試行を検証します。
初回テスト実行にはMaven CentralからRobolectricのAndroidランタイムを取得する通信が必要です。
Roomスキーマは `app/schemas` に保存します。将来変更時はバージョンを上げてマイグレーションを実装し、既存データを破棄しないでください。
UI自動テストはまだ含みません。
Android StudioではAPI 26以上の端末・エミュレーターを選んでRunしてください。

## GitHub ActionsからAPKを取得

プッシュ・Pull Requestで `.github/workflows/android.yml` がユニットテスト・ビルド・Lintを実行します。
Actions画面から手動実行もできます。

1. GitHubの **Actions → Android debug APK** で対象コミットの成功した実行を開きます。
2. **Artifacts** の `memonow-debug-<実行番号>` をダウンロードします（GitHubへのログインが必要です）。
3. ZIPを展開し、`app-debug.apk` を取り出します。保存期間は14日です。

debug APKは開発用の自動生成鍵で署名され、Play Store配布用ではありません。
CIや別PCでは署名鍵が異なるため、既存インストールの更新に失敗することがあります。
署名不一致時にアンインストールすると投稿も削除されます。現在はJSON出力が未実装のため、
記録を残したい端末ではアンインストールを避け、動作確認には別端末やエミュレーターを使ってください。
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

以下は実機・エミュレーターでの手動確認項目です。

1. 空状態を確認し、日本語の複数行を入力・送信して本文と端末時刻が表示されること。
2. IME表示中や長文入力時も入力欄と送信ボタンが操作でき、空白のみでは送信できないこと。
3. 複数投稿が下へ追加され、過去の履歴を読んでいる間に編集・再描画で位置が飛ばないこと。
4. 画面回転で書きかけの本文を保持すること。編集で作成日時が変わらず、削除キャンセルで投稿が残ること。
5. アプリを強制終了・再起動し、編集後の本文と削除結果が保持されること（端末再起動後も確認）。
6. 日付を跨ぐ投稿・同時刻の多件数投稿・タイムゾーン変更後の日時表示を確認すること。

ファイル転送でAPKを開く方法も使えます。その場合は端末側でそのファイル管理アプリからの
「不明なアプリのインストール」を一時的に許可してください。

ビルド成功だけでは実機表示や起動を保証しません。実機・エミュレーターでの表示確認と、
GitHub Actions上での実行結果はPRに個別に記載します。
