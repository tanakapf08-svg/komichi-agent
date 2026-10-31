# 実現可能性調査

調査日: 2026-10-31（日本時間）。以下の「確認済み」は公開文書・ソースの静的確認を指す。ユーザーのPlusアカウントによる認証、推論、Android実機での動作確認はまだ行っていない。

## 結論

**Android単体構成を採用する。接続先は公式 Sign in with ChatGPT の OSS／ローカルアプリ向け ChatGPT plan usage。APIキー、有料APIへのフォールバック、CodexのクライアントID流用は実装しない。**

OpenAIは現在、OSS／ローカルアプリの動的クライアント登録と契約枠共有を公開している。初回は `dynamic_agent_client` からアプリ自身の登録を作り、発行されたclient IDで認可コードを交換する。自作クライアントの正当性を既存ツールの動作だけから推定する必要はない。[公式概要](https://developers.openai.com/siwc/token-sharing-open-source)、[登録](https://developers.openai.com/siwc/token-sharing-open-source/sign-in)

Plusの5時間利用枠は他の対応アプリと共有され、アプリごとに別枠が増えるわけではない。利用可否にはアカウント・ワークスペース・地域・モデル・ポリシー条件がある。契約枠が利用できない場合は停止し、API課金へ切り替えない。ユーザーの実アカウントでの利用可否は未確認。[アカウントと利用枠](https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions)、[エラー](https://developers.openai.com/siwc/token-sharing-open-source/errors-and-recovery)

「Android単体」はUI・認証・履歴・計画実行・端末操作をAndroid内に置くという意味。AI推論はOpenAIに接続するためネットワークが必要で、オフラインLLMではない。追加クレジット購入、決済、APIキー入力の経路は設けない。

## 公式接続方式

確認済みの仕様:

- 外部ブラウザ、PKCE S256、state、nonce、127.0.0.1上のHTTPコールバックを使用。認可サーバーは `https://auth.openai.com`。クライアントシークレット不要。
- インストール単位の安定したhost IDと、登録ごとの発行client IDを保存する。他アプリの登録をコピーしない。
- IDトークンの署名・issuer・audience・有効期限・nonceを検証。`chatgpt.tokens.use.direct` の許可を確認する。
- 契約枠共有トークンで、公開 `https://api.openai.com/v1/models` と `/v1/responses` を利用する。URLが公開APIでも、APIキーを使う従量課金接続とは認証経路が異なる。
- `store:false`、`stream:true`、毎回必要な履歴をinput配列で送る。HTTPのprevious_response_idに依存しない。ツールはnamespace内のfunctionを使う。
- ストリームの `response.completed` まで成功扱いにしない。途中の利用制限・切断・incompleteで操作を実行しない。
- 画像生成・ネイティブcomputer use・ホステッドMCP等にはプレビュー制約がある。Android操作はアプリ自身が実行するfunction toolとする。

根拠: [登録仕様](https://developers.openai.com/siwc/token-sharing-open-source/sign-in)、[モデルと推論](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference)、[プレビュー制約](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations)、[トークン仕様](https://developers.openai.com/siwc/token-sharing-open-source/token-reference)。

Codex app-serverも公式のカスタムクライアント向けインターフェースだが、Android向け公式配布・完全なOSサンドボックス対応をこの調査で確認できなかった。単体APKにNode／Python／Codexバイナリを同梱する理由はない。PC代替構成なら公式app-serverを認証ごとホスト上に保持できる。[公式app-server](https://learn.chatgpt.com/docs/app-server)

## 参照リポジトリの最新スナップショット

取得時点の既定ブランチHEAD。日時はUTC。移動するブランチでなく、このSHAで調査した。更新頻度は保守の存在を示すが、品質・安全性を保証しない。

| リポジトリ | HEAD | コミット日時 | 状態・ライセンス |
|---|---|---|---|
| openclaw/openclaw | `1bf930c8e40a547f0e2eda87f895b2a7a00e644a` | 2026-10-31 02:18:50 | 非archived、MIT |
| NousResearch/hermes-agent | `f30105a0b4b3d0c1baa6c464189755ccdbc1ac7f` | 2026-10-31 01:59:00 | 非archived、MIT |
| rikkahub/rikkahub | `a5db9c9b770f316c76136970aa01a9d790e10e51` | 2026-10-29 02:38:34 | 非archived、AGPL-3.0 |
| anomalyco/opencode | `173f6c6dce7db669679625f6af93651ae66f16be` | 2026-10-31 01:56:41 | dev、非archived、MIT |
| badlogic/pi-mono → earendil-works/pi | `35dfa757378bd7a8570670e99c2687bdd1039a06` | 2026-10-30 23:17:56 | リダイレクト先を確認、非archived、MIT |

メタデータ・コミット・各LICENSEをGitHubコネクタで取得。下記ソースリンクは調査SHAに固定。

## 比較

「公式」は各プロジェクトが公開している実装／文書と、OpenAIの公開許諾範囲を区別した表現。OAuthコードが存在するだけでは、OpenAIによるその製品全体の認定とは扱わない。

| 項目 | OpenClaw | Hermes Agent | RikkaHub | OpenCode | Pi |
|---|---|---|---|---|---|
| Plus接続 | SIWC Betaの動的登録・公開Responses実装あり。従来Codex経路も別に存在 | Codex OAuthと公式app-serverを呼ぶ任意ランタイムあり。確認したproviderはCodex backend。新SIWC直接実装は確認できず | ChatGPT providerあり。ただし確認箇所はCodex client ID／originator／backend利用。今回不採用 | 新ChatGPT pluginは動的登録・公開Responses。別のlegacy Codex pluginも存在 | 新openai-chatgpt OAuthは動的登録・公開Responses。従来Codex OAuthも別に存在 |
| Android | Kotlinの公式companionあり。GatewayをAndroid側にはホストしない | Termux arm64パッケージあり。ただし文書に「現在broken」と明記 | Kotlin/ComposeのネイティブAPK | 主体はTS／サーバー／デスクトップ。Androidアイコン資産だけではAPK対応の証拠にならない | 公式Termux手順あり。Nodeランタイムが必要 |
| ツール呼び出し | エージェント・plugin・node command | 独自tools、MCP、app-serverツールの橋渡し | MCP／Skills、Responsesのtool call | ツール・MCP・拡張 | agent loop・カスタムtool・MCP |
| Android拡張 | companion nodeのコマンド追加が可能。Gateway依存は残る | Androidアプリとの認証済みbridgeを別途開発する必要 | Android APIとの統合が容易。権限broker追加は必要 | Kotlin側brokerとのbridgeが必要 | TermuxからAndroid brokerを呼ぶbridgeが必要 |
| 再利用 | MIT。ただし大規模Gatewayの移植は重い | MIT。Python／native依存をAPKへ移すコストが高い | UI再利用は有力だがAGPLで派生物の対応ソース提供等を設計する必要 | MIT。プロトコル設計の参考に適する | MIT。軽量agent設計の参考に適する |
| 単体APKの制約 | companion APKだけではエージェントが完結しない | Python・Node・bionic依存、Termux環境、現在の配布不具合 | Authを公式SIWCに置換し、既存有料provider経路も整理する必要 | Node/Bun依存・プロセス管理・Android API境界 | Node依存・バックグラウンド制限・Termux別インストール |

確認ソース:

- OpenClaw: [認証比較](https://github.com/openclaw/openclaw/blob/1bf930c8e40a547f0e2eda87f895b2a7a00e644a/docs/providers/openai/authentication.md)、[token-sharing定義](https://github.com/openclaw/openclaw/blob/1bf930c8e40a547f0e2eda87f895b2a7a00e644a/extensions/openai/token-sharing.ts)、[OAuth実装](https://github.com/openclaw/openclaw/blob/1bf930c8e40a547f0e2eda87f895b2a7a00e644a/extensions/openai/token-sharing-oauth.runtime.ts)、[Android制約](https://github.com/openclaw/openclaw/blob/1bf930c8e40a547f0e2eda87f895b2a7a00e644a/docs/platforms/android.md)、[LICENSE](https://github.com/openclaw/openclaw/blob/1bf930c8e40a547f0e2eda87f895b2a7a00e644a/LICENSE)。
- Hermes: [認証](https://github.com/NousResearch/hermes-agent/blob/f30105a0b4b3d0c1baa6c464189755ccdbc1ac7f/hermes_cli/auth_codex.py)、[provider](https://github.com/NousResearch/hermes-agent/blob/f30105a0b4b3d0c1baa6c464189755ccdbc1ac7f/plugins/model-providers/openai-codex/__init__.py)、[app-server](https://github.com/NousResearch/hermes-agent/blob/f30105a0b4b3d0c1baa6c464189755ccdbc1ac7f/website/docs/user-guide/features/codex-app-server-runtime.md)、[Termux不具合](https://github.com/NousResearch/hermes-agent/blob/f30105a0b4b3d0c1baa6c464189755ccdbc1ac7f/website/docs/getting-started/termux.md)、[LICENSE](https://github.com/NousResearch/hermes-agent/blob/f30105a0b4b3d0c1baa6c464189755ccdbc1ac7f/LICENSE)。
- RikkaHub: [認証](https://github.com/rikkahub/rikkahub/blob/a5db9c9b770f316c76136970aa01a9d790e10e51/app/src/main/java/me/rerere/rikkahub/data/ai/account/ChatGPTAuth.kt)、[provider](https://github.com/rikkahub/rikkahub/blob/a5db9c9b770f316c76136970aa01a9d790e10e51/ai/src/main/java/me/rerere/ai/provider/providers/chatgpt/ChatGPTProvider.kt)、[README](https://github.com/rikkahub/rikkahub/blob/a5db9c9b770f316c76136970aa01a9d790e10e51/README.md)、[LICENSE](https://github.com/rikkahub/rikkahub/blob/a5db9c9b770f316c76136970aa01a9d790e10e51/LICENSE)。
- OpenCode: [新ChatGPT plugin](https://github.com/anomalyco/opencode/blob/173f6c6dce7db669679625f6af93651ae66f16be/packages/core/src/plugin/provider/chatgpt.ts)、[legacy実装](https://github.com/anomalyco/opencode/blob/173f6c6dce7db669679625f6af93651ae66f16be/packages/core/src/plugin/provider/openai.ts)、[LICENSE](https://github.com/anomalyco/opencode/blob/173f6c6dce7db669679625f6af93651ae66f16be/LICENSE)。
- Pi: [新OAuth](https://github.com/earendil-works/pi/blob/35dfa757378bd7a8570670e99c2687bdd1039a06/packages/ai/src/auth/oauth/openai-chatgpt.ts)、[provider](https://github.com/earendil-works/pi/blob/35dfa757378bd7a8570670e99c2687bdd1039a06/packages/ai/src/providers/openai.ts)、[Termux](https://github.com/earendil-works/pi/blob/35dfa757378bd7a8570670e99c2687bdd1039a06/packages/coding-agent/docs/termux.md)、[LICENSE](https://github.com/earendil-works/pi/blob/35dfa757378bd7a8570670e99c2687bdd1039a06/LICENSE)。

## フォークとアーキテクチャの選定

**設計判断:** 既存全体をフォークせず、新規MITライセンスの小規模Kotlin/Composeアプリ「Komichi Agent」を作る。RikkaHubはUI面で最適なフォーク候補だが、認証の置換、既存provider経路の整理、依存モジュール、AGPL派生物管理を含めると、安全な権限brokerと新SIWCだけを持つ初期版は新規実装が合理的。既存コードはコピーしない。これは実測工数ではなく設計上の評価。

UI・履歴・OAuth・Responses SSE・agent loop・Android権限brokerを分離する。モデルはアカウントのカタログから選び、固定の利用可能モデルを約束しない。未認証時には端末内ツールの手動テストだけが可能。AIが動いたように装うデモ応答は表示しない。

## 実行構成 A/B/C

| 評価 | A: ネイティブAndroid単体（採用） | B: Android + Termux | C: PC + Android |
|---|---|---|---|
| Plus | 新SIWCの直接接続。アカウント許可が条件 | Piの新SIWCが有力。HermesのTermux版は現在broken | OpenClaw SIWC、または公式Codex app-serverを使うホスト構成 |
| 常時稼働 | 利用中のみ。自動バックグラウンド運転は初期版の対象外 | 利用中Termuxプロセスが必要。Doze等で停止し得る | 利用中PC/Gatewayを起動・接続維持する必要 |
| 難易度 | OAuth/OIDCとAndroid broker実装は必要。IPC構成は最小 | ランタイム＋ローカル認証bridge＋brokerで複雑 | broker＋安全な通信＋ホスト設定が必要 |
| セキュリティ | OAuthトークンは端末Keystoreで暗号化。操作は端末で承認 | localhostでも他アプリを信頼せず、ペアリング・認証・再送防止が必要 | TLS／VPN、端末ペアリング、操作ごとの端末承認。ホスト侵害範囲が増える |
| 使い勝手 | APKとブラウザ認証で完結 | Termuxの導入・起動・電池管理が必要 | PC依存とネットワーク設定が残る |

## Androidの技術制約

- Phase A: launchableアプリだけをPackageManagerのqueriesで列挙。全パッケージ権限は要求しない。ファイルはSAFでユーザーが選んだURIのみ扱う。[Android SAF](https://developer.android.com/training/data-storage/shared/documents-files)
- Phase B: ユーザーが設定からAccessibilityServiceを有効化する。座標やUIツリーが取得できないアプリ、保護画面、WebView/OEM差、ロック画面では失敗し得る。成功を偽装しない。パスワード欄や編集欄等は原則隠す。[公式API](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)
- 画面の文章はuntrusted観測データ。ユーザー命令や承認とは扱わない。モデルへの注意書きだけで完全にprompt injectionを解決したとはしない。許可ツール、厳格な引数検証、対象アプリ許可リスト、人間の承認を併用する。
- Phase C: Shizukuはrootそのものではない。起動時のUID（shell=2000/root=0）、Androidの権限とSELinuxに制限される。ユーザーサービスのAIDLに固定操作だけを公開する。[公式API](https://github.com/RikkaApps/Shizuku-API/blob/master/README.md)
- Shizukuの無線デバッグ起動はAndroid 11以降。再起動後の再開始が必要な場合がある。[公式起動手順](https://shizuku.rikka.app/guide/setup/)
- Phase D: Magisk/KernelSU/KernelSU Next等のsu互換性は実機ごとに確認する。存在するだけではroot許可成功としない。固定のUID確認と、許可済み対象への限定操作のみ。SELinux回避や恒久root化はしない。
- Accessibilityの自律操作アプリのGoogle Play配布適格性はAPKのサイドロード可否とは別問題。初期成果はDebug APK。Play公開可能とは主張しない。

## 開発ロードマップと完了条件

| 段階 | 実装 | 合格条件 |
|---|---|---|
| 1 調査（本書） | 上記5リポジトリと公式接続仕様 | 禁止経路を除外し採用構成を説明 |
| 2 設計 | コンポーネント図、権限モデル、ツール契約、脅威境界 | 任意shell・無承認外部送信の経路がない |
| 3 最小版 | Composeチャット、暗号化履歴、複数登録、OAuth/OIDC、モデル一覧、SSE、アプリ起動 | SDKビルド成功。Plusの実認証・推論はユーザー端末で確認 |
| 4 通常権限／Accessibility | アプリ検索、設定、SAF、画面情報、UI操作、承認、停止 | 実機の保護画面／画面変化／キャンセルでfail closed |
| 5 高度権限 | Shizuku接続・許可・固定UserService、su診断・限定操作 | 非rootでも起動。拒否・binder death・timeoutを処理 |
| 6 配布 | unit tests、lint、Debug APK、ハッシュ、導入手順 | 実行済みと実機未検証を明記。ソース一式を添付 |

最優先の実機ゲート: Plusの動的登録 → IDトークン検証 → grant確認 → モデル取得 → 最初のresponse.completed。この一連がアカウント側で拒否される場合、その理由を表示して停止する。非公開API、cookie抽出、既存クライアント偽装、有料APIへの代替は採用しない。
