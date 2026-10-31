# v0.4.0 検証結果

検証日: 2026-10-31（日本時間）。Debug試作版。利用者の実アカウントや実端末を使わず、使い捨ての読み取り専用AVDと人工データで検証した。

## ビルド・検査

```text
:app:assembleDebug
:app:testDebugUnitTest
:app:lintDebug
:app:connectedDebugAndroidTest
```

最終ソースで **BUILD SUCCESSFUL**。環境: Windows、Android Studio JBR 25.0.3、Gradle 9.3.1、AGP 9.1.1、SDK 36 / Build Tools 36.0.0。

| 検証 | 結果 | 証拠 |
|---|---|---|
| Debug APK | 生成成功、version 0.4.0 / code 4 | reports/build.txt, reports/apk-manifest.txt |
| JVM単体テスト | **27件、失敗0・エラー0** | reports/TEST-*.xml |
| Android 11 / API 30 / x86_64 | **15件、失敗0・エラー0・skip 0** | reports/instrumentation-tests.xml |
| Android 17 / API 37 / x86_64 | **15件、失敗0・エラー0・skip 0** | reports/instrumentation-api37.xml, reports/build-api37.txt |
| Android lint | エラー0、警告46、hint 2 | reports/lint.txt |
| APK署名 | 検証成功、以前と同じDebug証明書 | reports/apk-signature.txt |
| UIの目視確認 | 人工データのライト/ダーク、作業スペース、設定を確認 | screenshots/, ui-preview.md |

`QUERY_ALL_PACKAGES`のlint例外はmanifestの宣言1箇所だけ。全モードの非launcher・将来追加アプリ列挙のためで、[アクセスモード](access-modes.md) に理由を記載。全体baselineで他のエラーを隠していない。依存更新、target SDK、Rootパスの明示的な別名、古いAPI対応などの警告は残る。Google Play適格性を確認した配布ではない。

## 単体テスト

- 既存19件: 厳密なツールschema・namespace・引数・shell注入の拒否、機密情報マスク、公式AI接続先、未完了/失敗SSEでの操作拒否、OIDC署名・issuer・audience・nonce・期限、モデル自動取得の重複/遅延結果/アカウント別選択、全アクセス対象判定、共有ストレージ・Rootパスと形式の制約。
- 新規8件: 停止後の遅延callbackと古いrunの拒否、文書versionによる古い更新/削除の拒否、作業パス逸脱・衝突・上限、ワークフローの順序/未知コマンド/余分なキー/固定画面IDの拒否、会話書き出しでprovider履歴とアカウント情報を除外、表示専用インポート、再起動後の未解決call終了処理、公開HTTPS/DNSのプライベート・混在アドレス拒否、無効ツールのschema除外。

## Androidテスト15件（Android 11・17の各OSで実行）

1. 未認証・Shizukuなし・Rootなしで起動し、モデル選択・設定・ログ・Activity再作成を確認。
2. 全モードは明示承認後に有効になり、再作成でも保持。OFFで元に戻り、Rootは自動で有効にならない。
3. 人工のモデル一覧でチャットの選択を変更。ネットワークは使わない。
4. Keystoreの暗号化/復号、平文秘密値を含まないこと、改ざん拒否。
5. Rootファイル操作フォームの必須入力と構造化引数。キーボードの完了操作から一覧/コピーを選べる。
6. 作業ファイル保存の承認待ちに**動作中・承認待ち**を表示し、承認前は保存しない。許可後に保存し終了表示へ変わる。
7. 拒否すると保存せず、停止状態になる。
8. 無効ツールを含むワークフローはそこで失敗し、後続の保存を行わない。
9. 編集画面から保存し、Activity再作成後にも内容を保持。vaultに平文テキストが含まれない。
10. 動作カードに経過時間と停止処理を表示し、停止完了後は動作中表示を残さない。
11. 開始直後キャンセルを3回、短い操作を3回連続実行し、OSのforeground-service期限を過ぎてもクラッシュしない。
12. 認証通知サービスをアカウント・ネットワークなしで開始/中止し、短時間終了と起動直後キャンセルでも後からクラッシュしない。
13. 実ファイルdescriptorで日本語/絵文字のUTF-8入出力、バイナリと通常テキスト上限の拒否、履歴読み込み用の別上限を確認。任意の実SAF providerやクラウド保存の検証ではない。
14. ブラウザ未許可のときURL起動がアプリ対象制限を迂回せず失敗し、外部アプリへ遷移しない。
15. 人工データだけのUI画像を取得。テスト時だけFLAG_SECUREを外して最後に戻す。通常APKの保護は維持する。

短い処理の検証で `startForegroundService() did not then call startForeground()` による遅延クラッシュを検出したため、操作・認証の両サービスで開始ackとキャンセル後の安全な終了を実装し、上記11/12で再発しないことを確認した。

## APK

- package: `dev.komichi.agent`
- version: `0.4.0` / code `4`
- minSdk: 26（Android 8.0）、targetSdk: 36
- サイズ: **11,768,281 bytes**
- SHA-256: `f81d72953e6a6f69860bbd125dc97eb2834806604bf04535bb507ba54e813119`
- 証明書SHA-256: `97f851689a110b2ca5705ba155ef80f406262778187fb6a49029cbc05daab58c`
- v0.1.0〜v0.3.0と証明書が一致し、同じ配布元から上書き更新できる構成。実端末での更新は未確認。

新たな実行時権限は `RECORD_AUDIO`。本人が音声入力を開始する場合だけ要求し、Android 12以降の端末内認識に限定する。全モード、起動、認証、Root/ツールの有効化ではマイクを自動許可・録音しない。カメラ・SMS・連絡先権限は追加していない。

## 残る確認・未実装

**この環境で実Plusアカウントの認証・モデル取得・推論は行っていない。** 接続方式は変更せず、APIキーや従量課金providerへのfallbackはない。実ユーザーアカウントと地域・契約枠の許可を端末で確認する必要がある。

- Android 12以降での実際の端末内音声認識・対応言語モデル、端末内TTS音声の再生、マイク拒否/取消とエンジン固有エラー。
- 実SAF/クラウドproviderでの読み書き、取消、遅延、容量不足。書き出しやキャンセル時の部分ファイル。
- 実際の公開WebサイトのTLS・文字コード・サイズ・DNS・リダイレクト等の挙動と、許可済み各ブラウザでの起動。セキュリティ境界の単体テストは行ったが、Web全体の互換性を検証したものではない。
- 実アプリへのAccessibility操作、保護画面、snapshot変更、実行中の停止、OEM差とOSの通知制限。
- Shizukuの実診断・停止、Magisk / KernelSU / KernelSU Next / 一時Rootでの許可・拒否・内部データコピー・途中停止・CE/DE/SELinux/名前空間。
- MediaProjectionの各セッション許可・保護画面・中止。画像をAIへ送る機能はない。

RootFileScripts自体はv0.3.0から変更していない。人工データをUID 0でコピー・TAR・拒否確認した [v0.3.0の検証](https://github.com/tanakapf08-svg/komichi-agent/blob/v0.3.0/docs/validation.md) と reports/root-files-integration.txt はその版の同一テンプレートの証拠として残す。今回の実Root管理方式での確認済みという意味ではない。

Operitの全機能はまだ実装していない。MCP、ToolPkg、Ubuntu/SSH、ローカルLLM、PDF/画像解析、グラフメモリ、連続音声、複数キャラクター等の差は [対応表](operit-coverage.md) に記載。承認・機密情報保護を解除する機能、任意Root shell、有料APIへの切替は追加しない。マスキングやprompt injection対策に完全性を主張しない。
