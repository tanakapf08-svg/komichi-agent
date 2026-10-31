# Komichi Agent

Android単体で動作するKotlin / Jetpack ComposeのAIアシスタント試作版。MITライセンス。

**ChatGPT契約枠共有だけを利用します。APIキー入力、API従量課金への切替、cookie抽出、他クライアントの認証情報流用はありません。** 実際の利用可否はアカウント・ワークスペース・地域・モデルの条件によります。Plusでの実認証・推論は利用者の端末で確認する必要があります。

OpenAIが公開する [Sign in with ChatGPTのOSS向け登録](https://developers.openai.com/siwc/token-sharing-open-source/sign-in) と [ChatGPT plan usage](https://developers.openai.com/siwc/token-sharing-open-source) を使用します。初回にKomichi自身の登録を作成し、発行されたclient IDとPKCEで認証します。非公開ChatGPT backendは呼びません。商用・ホスト型サービスとしての提供はこのローカルOSS構成の対象外です。

## 収録内容

- チャット、暗号化した会話履歴、アカウント選択、モデル選択、ストリーミング、Markdownとコードブロック、ダークモード。
- 起動・認証・アカウント切替時のモデル自動取得、チャット上部のモデル選択、アカウントごとの選択の記憶。
- 権限状態の一覧と設定導線、明示opt-inの全アクセスモード。[範囲と制約](docs/access-modes.md)
- ツールの実行状況、操作ごとの承認、送信結果の確認・編集、停止ボタンと停止通知。
- 起動可能なアプリの検索・起動、標準設定画面、SAFで選択したファイルの情報参照。
- MediaProjectionでユーザーが許可した画面の1枚のローカルプレビュー。画像はメモリ内のみで、AIには送信しません。
- AccessibilityのUI要素取得、要素タップ、スワイプ、スクロール、文字入力、戻る、ホーム。機密画面・古いsnapshot・不許可アプリは拒否します。
- Shizukuの接続・権限確認、ユーザーによる権限リクエスト、固定AIDL UserServiceによる診断・一般アプリの強制停止。
- Rootの明示opt-in、固定UID確認、許可済み一般アプリの強制停止。任意shell、システムファイル変更、アンインストール、権限変更は実装していません。

## 導入

1. Android 8.0（API 26）以降の端末にDebug APKをインストールします。配布ファイルのSHA-256を確認してください。端末によっては「不明なアプリのインストール」をインストール元に許可する必要があります。
2. 「接続・権限」→ **Continue with ChatGPT** を押し、システムブラウザで認証します。ChatGPT契約枠共有の許可内容を確認してください。cookieやAPIキーをアプリへ貼り付ける必要はありません。
3. モデル一覧を自動取得します。チャット上部でモデルを選択できます。認証済みで次回起動した時も自動取得し、前回のモデルを記憶します。取得失敗時は「再取得」から再試行できます。取得成功だけでは推論成功を保証せず、最初の完了応答で確認します。
4. 「操作を許可するアプリ」で必要な対象だけを選びます。「起動」はAIへ接続しない端末内の動作テストです。
5. 画面操作が必要な場合は説明を読み、OSのユーザー補助設定で **Komichi 端末操作** を手動で有効にします。端末によってはサイドロードアプリの「制限付き設定」を本人が許可する必要があります。
6. チャットで指示します。送信文、操作の対象・引数、AIへ返す結果を都度確認します。画面上の文章はユーザー命令として扱いません。

「接続・権限」→「権限一覧を表示」で有効・未許可・未確認を確認できます。全アクセスモードは未選択のアプリも対象にし、OSの許可後は共有ストレージのディレクトリ一覧も参照できます。通知・Accessibility・画面取得・Shizuku・Rootはそれぞれ本人の許可が必要です。モードOFFで個別アプリ選択に戻ります。操作承認と機密情報の保護は両モードで維持します。

停止はアプリ内、実行通知、またはAccessibilityの停止ボタンから行えます。停止時に未実行のツールを破棄します。既にAndroidへ渡ったタップ等を巻き戻す機能はありません。

通常権限だけで他アプリを起動した場合、次の確認はKomichiへ戻って行います。Accessibility有効時は対象アプリ上に確認ダイアログを表示します。

契約枠が使えない・上限に達した・認証が失効した場合はエラーを表示します。追加クレジットの購入や別の課金経路への切替は行いません。[ChatGPTの利用管理](https://chatgpt.com/settings/usage) で設定を確認できます。サインアウトでリモート失効を確認できなかった場合は、その旨を表示します。

## Shizuku / Root

Shizukuは別途 [公式手順](https://shizuku.rikka.app/guide/setup/) で起動し、Komichiのボタンから許可します。Shizuku自体はAPKに同梱しません。shellとrootでは実行可能な操作が異なります。接続が切れたり許可が拒否された場合は停止します。

Rootは初期状態で無効です。Magisk・KernelSU・KernelSU Next等をインストール・改変する機能はありません。suを利用できる環境で本人がopt-inした場合だけ固定操作を実行します。各Root管理方式での実機互換性は未確認です。Rootがなくても基本機能を使用できます。

強制停止には未保存データを失う可能性があります。許可済み一般アプリだけを対象とし、対象パッケージと影響を操作ごとに表示します。

## ビルド

- Android SDK Platform 36、Build Tools 36.0.0
- Gradle Wrapper 9.3.1、Android Gradle Plugin 9.1.1、組込みKotlin / Compose compiler 2.2.10
- JDK 17以上の対応環境（本成果物はAndroid Studio同梱JBR 25でビルド）
- minSdk 26、targetSdk 36。TermuxやPCサーバーは実行時に不要ですが、AI推論にはインターネット接続が必要です。

Android Studioでこのフォルダを開き、SDKの場所を設定してGradle Sync、`app`のDebugをビルドできます。

PowerShell:

```powershell
$env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr'
$env:ANDROID_HOME = 'C:/Users/YOUR_NAME/AppData/Local/Android/Sdk'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

macOS / Linux:

```sh
bash gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

APKの通常の出力先は `app/build/outputs/apk/debug/app-debug.apk`。実機／エミュレーターでの追加テストは `:app:connectedDebugAndroidTest`。署名はDebug用であり、ストア配布用のrelease鍵ではありません。公開配布では自身の署名、セキュリティレビュー、Accessibilityに関する配布先ポリシー確認が必要です。

## 検証と制限

[技術調査・5プロジェクト比較・ロードマップ](docs/feasibility.md)、[構成とツール契約](docs/architecture.md)、[実行した検証・実機確認項目](docs/validation.md) を参照してください。

画面や機密情報の自動判定は完全ではありません。パスワード、OTP、銀行画面等は手動で操作してください。画面からの承認、自己権限付与、任意shellを許可する経路はありません。ファイルの参照はユーザーが選択した項目の情報とOSの選択画面に限定し、全文解析や画像添付は初期版に含めていません。

履歴、認証情報、操作ログは端末内でKeystore AES-GCMにより暗号化して保存し、バックアップと端末間転送から除外します。アンインストール後に履歴や認証を復元する設計ではありません。元のChatGPT会話を読み込む機能はありません。

現在の実装はプレビューで、全要件を実機で検証済みの完成品ではありません。Plusの利用可否、実アプリでのAccessibility操作、Shizuku、各Root環境の確認を残しています。

## ライセンス

[MIT](LICENSE)。依存ライブラリの表記は [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) と `licenses/` を参照してください。独自名称とコンパスアイコンを使用し、公式ChatGPTアプリやOpenAIによる認定を表示しません。
