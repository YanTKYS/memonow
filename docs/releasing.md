[README](../README.md) · [バックアップ](backup.md) · [位置・天気](location-weather.md) · [開発](development.md) · [公開](releasing.md) · [テスト](testing.md)

# 署名・自動公開・更新

## 通常の公開

1. 実装担当が機能検証、バージョン更新、対応ノート `docs/releases/vX.Y.Z.md`、確認記録を1つのPRに揃えます。バージョン更新は公開意図であり、PR説明に対象版と未確認事項を記載します。
2. ChatGPTがレビューし、オーナー **YanTKYS** がmainへマージします。マージが最終承認です。
3. [release.yml](../.github/workflows/release.yml)がmainのpushからマージ元PR・オーナー・commitを照合し、未公開の新しい版だけテスト・署名・正式タグ作成・自動公開します。同じ公開版の追加PRは `skip` です。
4. Summaryの `release` / `skip` / `failed`、版・commit・タグ・URL・APK SHA-256を確認します。

`applicationId`は `com.yantkys.memonow`、バージョンの正本は[version.properties](../version.properties)です。
versionNameに対応するノートが必要で、versionCodeとversionNameは前の正式公開版から増やします。DB/JSONの番号とは別管理です。
署名前の軽量判定で既存版なら署名・配布ビルドを省略し、code不整合・巻き戻し・正式タグ/未公開Draftとの衝突は拒否します。
mainへの直接push・別ブランチ・PRイベント・タグpushは正式公開経路にしません。ユーザーの手動タグ操作や別の公開操作は不要です。

配布するのは同じ実行の署名検証済みAPKとSHA-256だけです。内部で一時的なGitHub Draft Releaseへ添付し、再ダウンロードしたAPKのチェックサム・証明書固定値・applicationId・version・非debuggableを確認して、最後に公開します。この内部Draftに追加操作は要りません。
署名鍵とパスワードはsignだけ、contents:writeとマージ根拠照合用pull-requests:readはdistributeに付与し、Secretsを持ち出しません。

### 任意のテスト候補

Actions → **Signed release APK → Run workflow → main** はpreview専用で、正式タグを作らず未公開のテスト用DraftにAPKを置きます。
Summaryのリンクを管理者アカウントで開いて取得します。通常公開の前提ではなく、正式版と同一バイナリとは保証しません。
短期のActions Artifactsも作成しますが、Releasesの保管はその失効に依存しません。

### データの保全

鍵・パスワードの別媒体保管と読み出しは初回／鍵変更時、既存JSONの退避・上書き時のデータ保持は版ごとに確認します。
[バックアップ](backup.md)と[テスト手順](testing.md)を参照し、実機未確認事項を成功として扱わないでください。

## mainと署名環境の保護（初回）

GitHub Settings → Rules → RulesetsでmainのPR必須、必要なレビューとAndroid CI、force push・削除の禁止、管理者を含む迂回制限を有効化します。
公開スクリプトのmerge commit照合だけでは、Workflow自体の未レビュー直接変更を防げません。ブランチ保護は自動公開導入時の初回設定です。
Environmentのbranch policyもmainに限定し、毎回の追加承認待ちを設定している場合は「オーナーマージを公開承認」とする運用と整合させてください。設定変更は管理者が判断します。

## 初回セットアップ

### 鍵を一度だけ生成し、安全に保管する

以下は**ユーザー自身の安全なPC**で実行します。チャットやPRへ鍵・パスワードを貼らず、リポジトリ内へ保存しないでください。
JDK 17のkeytoolは対話でパスワード・証明書情報を尋ねます。生成時のaliasとパスワードを安全な管理先へ記録します。証明書の名前や組織はAPKから読める公開情報なので、公開してよい内容を使います。

```sh
mkdir -p "$HOME/.memonow-signing"
chmod 700 "$HOME/.memonow-signing"
keytool -genkeypair -storetype JKS -keyalg RSA -keysize 4096 -validity 10000 \
  -alias memonow -keystore "$HOME/.memonow-signing/memonow-release.jks"
# 公開可能な証明書SHA-256を確認（秘密鍵ではありません）
keytool -list -v -keystore "$HOME/.memonow-signing/memonow-release.jks" -alias memonow
```

鍵ファイル・storeパスワード・keyパスワード・aliasを、暗号化した別媒体や安全な管理先にもバックアップしてください。
バックアップした鍵を別の安全な環境で読み出し、同じ公開証明書であることを確認してください。
**鍵を紛失すると、通常は同じアプリへ上書き更新できません。** 毎回再生成したり、debug鍵を正式鍵として流用しないでください。
鍵・base64・パスワードは署名情報であり、Artifactsやログへ出さないでください。公開証明書フィンガープリントは照合用に保管できます。

### GitHub Environmentと必須の署名設定

リポジトリのSettings → Environmentsで **release-signing** を作成し、先に次の保護を設定します。

- mainのPRレビューとオーナーのマージを公開承認にします。Environmentはmainからの署名に限定します。Required reviewers等の追加承認待ちを設定している場合は、マージ承認に一本化する運用と初回に整合させてください。毎回の追加承認操作は通常手順に含めません。
- Deployment branches/tagsは現行の署名元であるmainに限定します。mainとタグの作成・更新・削除権限もRulesets等で保護し、未レビューの変更を通さない運用にしてください。
- 以下の秘密情報は**Environment Secrets**へ入れます。PRや任意ブランチから利用可能なRepository/Organization Secretsとして保存しないでください。

| Environment Secret | 内容 |
| --- | --- |
| `MEMONOW_RELEASE_KEYSTORE_BASE64` | 同じJKS鍵ファイルの改行なしbase64（暗号化ではありません） |
| `MEMONOW_RELEASE_STORE_PASSWORD` | keystoreパスワード |
| `MEMONOW_RELEASE_KEY_ALIAS` | 生成した鍵のalias（上記例はmemonow） |
| `MEMONOW_RELEASE_KEY_PASSWORD` | 鍵のパスワード |

Environment **Variable** `MEMONOW_RELEASE_CERT_SHA256` にkeytoolで確認した証明書SHA-256（コロン有無はどちらも可）を設定します。
これは公開値で、別の鍵やdebug署名を誤って配布しないための照合値です。
base64は例えば安全なPCで `base64 < "$HOME/.memonow-signing/memonow-release.jks" | tr -d '\n'` で作成します。
出力を共有ログ・画面共有・チャットへ流さず、Secrets設定へ直接取り込み、不要な一時ファイルは削除してください。

Secretsがなくても既存のPR/mainのdebug CIと自動テストは実行できます。
一方、releaseを実行した場合は必須設定不足を明示して失敗し、unsigned APKを配布可能なreleaseとして扱いません。
署名ジョブはEnvironmentの保護条件を満たした後に鍵を受け取り、runner一時領域にモード600で復号し、終了時に削除します。
署名処理ではGradleキャッシュ用Actionを使わず、configuration cacheも無効にし、Artifactsは検証済みAPKとSHA-256だけに限定します。

### ローカルの正式署名ビルドと検証

[開発環境](development.md)を用意し、コマンドはリポジトリのルートで実行します。安全なシェル/秘密管理ツールから次の環境変数を渡してください。値をコマンド履歴・ソース・Gradleプロパティファイルに書かないでください。

- `MEMONOW_RELEASE_KEYSTORE_PATH`：鍵の絶対パス
- `MEMONOW_RELEASE_STORE_PASSWORD` / `MEMONOW_RELEASE_KEY_ALIAS` / `MEMONOW_RELEASE_KEY_PASSWORD`
- `MEMONOW_RELEASE_CERT_SHA256`：照合用の公開証明書SHA-256

```sh
./gradlew --no-daemon --no-configuration-cache testDebugUnitTest assembleDebug lintDebug
./gradlew --no-daemon --no-configuration-cache testReleaseUnitTest assembleRelease lintRelease
python3 tools/release.py verify-apk --apk app/build/outputs/apk/release/app-release.apk
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --print-certs app/build/outputs/apk/release/app-release.apk
```

出力は `app/build/outputs/apk/release/app-release.apk`。`verify-apk`は署名の暗号学的検証に加え、証明書の固定値・applicationId・version値・非debuggableを確認します。
検証時に`--debug`・`--info`・`--scan`等で署名設定をログ/外部サービスへ出さないでください。作業後は秘密の環境変数を解除します。
鍵を渡す設定は通常のdebug開発環境に保存しません。

## 途中失敗・重複実行の回復

全リリース実行は共通concurrencyで直列化します。自動公開は署名前と公開直前に正式タグ・Release（未公開Draftを含む）の存在を検査します。
既存オブジェクトがあれば同じコミットでも停止し、自動上書き・削除・タグ付け替え・既存Draftの公開はしません。
成功後の再実行・同一版の後続PRはskipとなり再公開しません。previewの再実行はrun/attempt別の新しい未公開Draftを作ります。

失敗時はSummaryと該当runを確認してください。

- タグ／Draftがまだない場合：ネットワーク・権限・署名失敗を修復して、承認済みマージのrunを再実行できます。
- タグだけ、または未公開Draftと途中のassetsが残った場合：そのcommit・証明書・assets・失敗段階を管理者が確認します。本実装は自動再開しません。原則は修正と新しいバージョンのPRを準備します。同じ版で再試行が必要なら、未公開であることを確認した管理者が残存Draft／タグの整理を個別に判断し、整理後、オーナーが承認したマージの失敗runを再実行します。
- 公開済み、または公開PATCHの結果が不明な場合：まずReleaseの公開状態を確認し、既に公開されていれば再配布・削除でやり直さず、修正は次版にします。

署名ジョブはreadのみ、タグ／Releaseを扱うdistributeジョブだけがcontents:writeを持ちます。秘密鍵・署名パスワードはdistributeへ渡しません。
添付後にダウンロードしたAPKのSHA-256・署名証明書の公開固定値・applicationId・version・非debuggableを再検査してから公開します。
ReleasesのファイルはArtifactsの短期失効に依存しません。

取得したファイルの確認例（`X.Y.Z`を取得した版へ置き換えます。macOSは`sha256sum`の代わりに`shasum -a 256 -c`を使用できます）：

```sh
sha256sum -c memonow-vX.Y.Z.apk.sha256
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --print-certs memonow-vX.Y.Z.apk
```

SHA-256はファイルの一致、証明書SHA-256は更新の同一署名を確認する別の値です。debug証明書と混同しないでください。

## debugからの初回移行とrelease同士の更新

- **debug→正式署名releaseは原則上書き不可**です。旧debug版でJSONを保存し、別のテスト端末/インストールで内容と復元を確認してから切り替えます。
  バックアップ未確認のままアンインストールを促すことはしません。元のデータとJSONは移行後も確認が終わるまで保管します。
- 正式版でテスト記録を作り、同じ鍵・applicationId・増加するversionCodeの次版を `adb install -r` で入れ、投稿・属性・設定・JSON互換性が残ることを確認します。
  更新検証だけは `-PmemonowVersionCode=<インストール済みより大きいcode>` の一時overrideも使えます（実行時に整数へ置換）。配布時はoverrideせずversion.propertiesを更新してください。
- 一時的な検証専用鍵の結果と正式鍵による実機更新は区別し、確認した署名・commit・端末・結果を[版別記録](testing.md)へ残してください。
