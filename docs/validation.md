# 検証結果

検証日: 2026-10-31（日本時間）。本成果物は実機受入前の試作版。

## 実行済み

最終ソースに対して以下をまとめて実行し、**BUILD SUCCESSFUL** を確認した。

```text
:app:assembleDebug
:app:testDebugUnitTest
:app:lintDebug
:app:connectedDebugAndroidTest
```

| 検証 | 結果 | 証拠 |
|---|---|---|
| Debug APK生成 | 成功 | reports/build.txt |
| JVM単体テスト | 9件、失敗0・エラー0 | reports/TEST-dev.komichi.agent.AgentSecurityTest.xml、reports/TEST-dev.komichi.agent.IdentityVerifierTest.xml |
| Android instrumented tests | 2件、失敗0・エラー0 | reports/instrumentation-tests.xml |
| Android lint | エラー0、警告25、hint 1 | reports/lint.txt |
| APK署名 | apksigner verify成功、v2署名 | reports/apk-signature.txt |
| APK manifest | package / minSdk / target / 権限を抽出 | reports/apk-manifest.txt |

ビルド環境: Windows、Android Studio同梱JBR 25.0.3、Gradle 9.3.1、AGP 9.1.1、SDK 36、Build Tools 36.0.0。エミュレーター: Medium Phone / Android 17 / API 37 / x86_64。読み取り専用で起動した検証環境だけを使用し、実ユーザーの端末にはインストールしていない。

単体テストの内容:

- 未知のツール、namespace、余分な引数、型不一致、パッケージ名へのshell注入を拒否。
- 機密らしい文字入力、範囲外の選択肢、不正なsnapshot形式を拒否。
- メール・OTP・カード形式の文字列をマスク。
- 非公式ホスト、HTTP、偽のサブドメイン、非標準ポートへの認証済み通信を拒否。
- SSEの途中切断、DONEだけの応答、失敗イベントではツールを確定しない。
- completedに複数のツールがある場合も、すべてを検証してから返す。
- OIDC署名を検証し、nonce・audience・issuer・期限・署名が不正なIDトークンを拒否。

Androidで確認した内容:

- 認証情報・Shizuku・Rootがない状態で起動し、接続画面・ログ画面に遷移できる。
- Activity再作成後も接続画面が表示される。
- 実際のAndroid Keystoreで暗号化して復号でき、ファイルに平文のテスト秘密値を含まない。
- 暗号文を改ざんした場合に認証タグの検証で復号を拒否する。

最初のinstrumented testは旧EspressoがAPI 37の削除済み内部メソッドに依存して失敗した。公式リリースノートを確認し、テスト専用のRunner 1.7.0 / ext.junit 1.3.0 / Espresso 3.7.0に更新して再実行し、上記2件の成功を確認した。[AndroidX Testの公式情報](https://developer.android.com/jetpack/androidx/releases/test)

lintの警告は、固定した依存ライブラリに新しい版があること、targetSdk 36、API 26では無視される高API用の明示的false属性、KTXやCompose状態の書き方等。エラーを無効化するbaselineや全体抑制は使用していない。警告0とは主張しない。AccessibilityNodeInfo.recycleの非推奨警告も残るが、古い対応API向けに呼び出している。

## APK

- アプリ名: Komichi Agent
- package: `dev.komichi.agent`
- version: `0.1.0` / code `1`
- 最低Android: API 26（Android 8.0）
- target: API 36
- サイズ: 11,896,122 bytes
- SHA-256: `0a3f0d3074775da61b9fb6349fb556876607979474a765c17f3adf083fa52ee1`
- Debug署名。ストア公開用のrelease署名ではない。

追加した権限はネット接続、foreground service、通知、MediaProjection、Shizuku。AccessibilityへのアクセスはOSが管理する明示的なサービス有効化が必要。QUERY_ALL_PACKAGES、外部ストレージ全体、連絡先、SMS、カメラ、マイク、位置情報の権限は要求していない。AndroidXの非exported receiver保護用署名permissionがmerged manifestに追加される。

## 未実施：実アカウントと実機の受入項目

**Plusで本当に推論が成功することは未確認。** 公式仕様を確認して実装したが、モデル一覧だけで利用可能と判定しない。以下の最初の一連を利用者のアカウントで確認する必要がある。有料APIへ切り替えるコードはない。

| 項目 | 実機での確認方法・期待結果 |
|---|---|
| Plus初回登録 | Continue with ChatGPT → ブラウザにKomichi自身の登録が表示される → 契約枠共有を許可 → アプリで署名・nonce等を検証 |
| 推論 | モデル取得 → 短い質問を送信 → response.completedを受信。ChatGPT設定にアプリの利用が現れることを確認 |
| 共有不可・上限 | 許可なし／対象外／利用上限で停止し、APIキー要求・別課金・自動購入が発生しない |
| 再認証・更新 | アプリ再起動、期限付近のrefresh、拒否、失効、ログアウト、同一メールの別workspace登録を確認 |
| Phase A | 対象アプリ許可 → 起動の個別確認 → 起動結果を確認。設定ページ、SAF、許可取消・ファイル消失も確認 |
| 通常権限の画面取得 | OS許可→指定画面を1枚取得→端末内だけに表示→中止・OS停止・保護画面を確認。画像送信機能はない |
| Accessibility | 本人がOSで有効化。対象アプリだけを許可し、read_screen→確認・マスク→要素タップ等を実行 |
| 古い画面 | 画面変更、120秒超過、別の実行セッションのsnapshotに対する操作を拒否 |
| 保護画面 | password欄・OTP・金融情報・権限UI等を含む画面で操作を拒否。ヒューリスティックに見落としがないか人間が確認 |
| 停止 | 承認中・SSE受信中・ツール待機中に停止し、次の操作・結果送信が発生しない。開始済みジェスチャは最大350msで、巻戻しはできない |
| Shizuku | 未起動・拒否・許可・binder death・UserService作成失敗・shell/root UID差・診断を確認 |
| Root | Magisk / KernelSU / KernelSU Next / 一時rootでそれぞれ未許可・拒否・許可・timeoutを確認。互換性を推測で保証しない |
| 強制停止 | 許可済み一般アプリだけに対象・影響の個別確認。システムアプリや不正パッケージを拒否 |
| OEM差 | Android 8〜16の実端末、WebView、Composeアプリ、画面回転、分割画面、通知不許可、制限付き設定を確認 |

画面情報の自動マスキング・機密判定・prompt injection対策は完全性を証明するものではない。初期版は人間が操作と送信内容を確認することを前提とし、無人自律運転として公開・運用する段階ではない。

Phase B〜Dはコードを実装しコンパイルした段階で、実端末上の一連の自動操作が成功したとは主張しない。実アカウント認証、実機の権限付与、端末依存の受入はこのレポートの検証済み範囲に含まれない。
