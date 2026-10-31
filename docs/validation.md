# v0.2.0 検証結果

検証日: 2026-10-31（日本時間）。実機受入前のDebug試作版。

## 実行した検証

```text
:app:assembleDebug
:app:testDebugUnitTest
:app:lintDebug
:app:connectedDebugAndroidTest
```

最終ソースで上記を実行し、**BUILD SUCCESSFUL** を確認した。

| 検証 | 結果 | 証拠 |
|---|---|---|
| Debug APK | 生成成功 | reports/build.txt |
| JVM単体テスト | 14件、失敗0・エラー0 | reports/TEST-*.xml |
| Androidテスト | 4件、失敗0・エラー0 | reports/instrumentation-tests.xml |
| Android lint | エラー0、警告29、hint 1 | reports/lint.txt |
| APK署名 | 検証成功。v0.1.0と同じDebug署名 | reports/apk-signature.txt |
| バージョンと宣言権限 | v0.2.0 / code 2を確認 | reports/apk-manifest.txt |

ビルド環境: Windows、Android Studio同梱JBR 25.0.3、Gradle 9.3.1、AGP 9.1.1、SDK 36、Build Tools 36.0.0。読み取り専用の検証用エミュレーター（Android 17 / API 37 / x86_64）だけで実行し、ユーザーの実端末にはインストールしていない。

単体テスト:

- 既存9件: ツール名・namespace・引数・shell注入の拒否、機密入力のマスキング、接続先の制限、SSE途中失敗時の操作拒否、OIDC署名・issuer・audience・nonce・期限の検証。
- モデル一覧3件: 取得の重複防止、アカウントごとの選択復元、未知モデルの拒否、別アカウントの遅延結果の破棄、失敗時の一覧クリアと再試行。
- 全アクセス2件: モードON/OFFと対象判定、自己アプリの除外、共有ストレージ外・兄弟ディレクトリ・他アプリの保護パスの拒否。

Androidテスト:

1. 未認証・Shizukuなし・Rootなしで起動。チャットのモデル選択欄、接続画面、Activity再作成、ログ画面を確認。
2. テスト用モデル一覧でドロップダウンを操作し、選択の表示更新を確認。ネットワーク接続は行わない。
3. 全アクセスモードは確認ダイアログを承認する前は有効にならない。承認後の設定保存とActivity再作成後の復元、OFFへの切替を確認。Rootは勝手に有効にならず、権限一覧の画面取得は「毎回確認」、Root実利用は「未確認」と表示されることを確認。
4. Android Keystoreによる暗号化・復号、平文秘密値を含まないこと、改ざんした暗号文の拒否を確認。

`QUERY_ALL_PACKAGES`のlint例外は、その宣言1箇所に限定する。本人が明示的に有効化する全モードでは、不特定の非launcherアプリや将来追加されるアプリも検索対象となり、固定`queries`では不足するため。理由は [アクセスモード設計](access-modes.md) に記載。他のlintエラーを全体抑制するbaselineは使用していない。依存バージョン、target SDK、KTX、古いAPI対応属性などの警告は残る。

## APK

- package: `dev.komichi.agent`
- version: `0.2.0` / code `2`
- minSdk: 26（Android 8.0）、targetSdk: 36
- サイズ: 11,696,192 bytes
- SHA-256: `0509a8723f99a47bdb1022ed72f301645e471f24d955829462bf7f99c2d43e23`
- v0.1.0と署名が一致するため、同じ配布元のv0.1.0から上書き更新可能な構成。実端末での更新は未確認。

追加宣言: `QUERY_ALL_PACKAGES`、`MANAGE_EXTERNAL_STORAGE`、API 29までの`READ_EXTERNAL_STORAGE`。モードは初期OFFで、通常時は個別アプリ選択とSAFの範囲を使用する。OS側の権限が付与されただけでは、モードOFF中の共有ストレージ参照を許可しない。カメラ・マイク・SMS・連絡先等の権限は追加していない。

## 実アカウント・実機で残る確認

**Plusアカウントによる実認証・モデル一覧取得・推論は未確認。** 自動取得の制御とUIはテストしたが、実際のアカウントが契約枠共有を許可することは別途確認が必要。有料APIへのフォールバックはない。

- 認証済みの状態からのコールド起動、ログイン直後、実アカウント切替後に一覧を自動取得すること。認証失効・オフライン・共有枠対象外時のエラーと再取得。
- 通知設定の拒否・チャンネル無効、Accessibilityの有効化と接続待ち、Shizukuの起動・許可・binder deathの実端末表示。
- OSの全ファイルアクセス許可の付与・取消、Android 8〜10のストレージ許可、実共有ストレージでの一覧・拒否・シンボリックリンク。
- Accessibilityでの実アプリ操作、画面変更や期限切れsnapshotの拒否、保護画面、操作中の停止、OEM差。
- Shizukuの実診断・一般アプリ停止と、Magisk / KernelSU / KernelSU Next / 一時Rootの実UID確認・許可拒否・timeout。
- MediaProjectionによる各セッションの本人許可、保護画面、取得終了・中止。画像はAIへ送信しない。

全アクセスモードはAndroidのサンドボックスや権限確認を解除しない。実機未検証の機能を検証済みと扱わず、機密情報のマスキングとprompt injection対策も完全性を保証するものではない。操作承認・送信前確認・緊急停止は維持する。

旧版の結果は [v0.1.0時点のレポート](https://github.com/tanakapf08-svg/komichi-agent/blob/v0.1.0/docs/validation.md) を参照。
