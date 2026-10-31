# Operit機能との対応・追加計画（v0.4.0）

2026-10-31に公開mainのHEADを再確認: `dbf71916fae9750cfdc9f9a774f5a0fee56633fb`（v1.12.2）。[Operit README](https://github.com/AAswordman/Operit/blob/dbf71916fae9750cfdc9f9a774f5a0fee56633fb/README.md) の機能一覧とソース構成を参考にした。READMEの記載を、Operitの全機能がすべての端末で動作することの証明として扱わない。

Komichiは独立したMITアプリ。Operitのソース・画像・名称・アバター等は移植していない。Rootの権限別実行器については [以前の調査](root-file-operations.md) に記載。今回も「実行状況が見えるチャット」「ローカル作業」「繰り返し操作」という構成を参考に独立実装した。

## 現在の対応

「対応」は記載した範囲に限る。Operitと完全互換、全機能の移植済み、全端末で検証済みという意味ではない。

| Operitの領域 | Komichi v0.4.0の実装 | 差・制約 |
|---|---|---|
| チャット・モデル | ストリーミング、Markdown、コード、アカウント別モデル選択・自動取得 | ChatGPT契約枠のみ。実アカウントでの認証・推論は未検証 |
| 実行状況 | **動作中**、承認待ち、接続、応答生成、操作中、送信確認、停止処理、完了・失敗・停止。経過時間と操作回数、停止ボタン | 転送量や完了率は計測していないため、架空の百分率を表示しない |
| 通知・画面上の停止 | 通知に現在フェーズと経過時間。Accessibility停止ボタンにもフェーズを表示 | OS通知許可とAccessibility接続に依存 |
| 会話管理 | 本文検索、改名、ピン留め、削除確認、JSON/Markdown書き出し、JSON読み込み | 読み込みは表示専用。認証情報・provider履歴は取り込まない。会話30件まで |
| 会話の分岐 | 選んだメッセージまでの発言を引用として新しい入力欄へ移す | 原providerのtool callや旧操作は再生しない。引用は送信前に確認 |
| UI | 下部ナビ、メッセージ操作、コードのコピー、最新位置へ移動、テーマ・文字サイズ保存、提案カード | フォント/背景画像の自由設定、アバター、フローティングチャット、ウィジェット、既定アシスタントの入口は未実装 |
| ファイル・作業スペース | 暗号化した仮想テキスト領域。パス付きファイル、検索、作成・参照・編集・削除、version照合、SAF入出力、コード/Markdown編集 | UTF-8テキストのみ。100ファイル・合計100万文字。仮想領域は実端末の任意パスではない |
| 添付 | UTF-8テキストのプレビュー・取り込み・チャット添付、本人が選んだSAFテキストの分割読み取り | PDF本文、画像/動画/音声解析、OCR、複雑なOffice形式は未実装 |
| メモリ | `memory/` の本人管理メモ、ローカル全文検索、明示的な添付 | 自動抽出・ベクトル検索・知識グラフ・自動送信は未実装 |
| キャラクター | 保存・編集・選択できる指示プリセット。毎回の送信レビューに含める | 独立モデル・メモリを持つ複数キャラクター、グループ会話、Tavernカードは未実装 |
| ワークフロー | JSONによる最大8操作の保存・編集・手動実行。開始時と各操作で承認。失敗・拒否で後続を停止 | ビジュアルノード、条件分岐、定期実行、Tasker/Intent起動は未実装。画面IDは固定保存しない |
| ツール権限 | 個別に有効/無効を保存。無効ツールをAIへ提示せず、実行器も拒否 | 有効でも既存の操作承認・送信確認を維持。無制限の自動承認は提供しない |
| Web | URLを指定した公開HTTPSページのテキスト取得、外部ブラウザ起動 | 内蔵ブラウザのタブ・Cookie・DOM操作・ダウンロード管理は未実装。取得はJS・Cookie・リダイレクト・LANアクセスなし |
| 端末操作 | アプリ検索/起動、設定、許可済み画面のUI取得・タップ・スクロール・入力・戻る/ホーム | Accessibilityが必要。ADB/Rootによる仮想画面、AutoGLM、視覚認識は未実装 |
| 高度な権限 | Shizukuの状態/診断/一般アプリ停止、Rootの状態/一般アプリ停止/内部データ一覧・コピー/TAR | 任意shell・権限変更・システムファイル変更・アプリ削除は未実装。Root管理方式の実機確認は残る |
| 音声入力 | Android 12以降の `createOnDeviceSpeechRecognizer` のみ。本人のマイク許可と開始操作が必要。結果は入力欄へ | 対応エンジン・言語モデルが必要。クラウドSTT、常時待受、ウェイクワード、連続会話は未実装 |
| 読み上げ | インストール済みの端末内TTS音声を本人のボタン操作で使用。停止可能 | 非対応時は停止。クラウドTTS、ONNX音声、音楽キューは未実装 |
| 拡張 | 固定された構造化ツールカタログを拡張可能 | MCP、ToolPkg、Skillsマーケットプレイス、外部スクリプトはまだ接続していない |
| ターミナル・開発環境 | テキスト/コードの保存と編集 | Ubuntu/PRoot/chroot、SSH/SFTP、Git実行、コンパイラ、APK生成はアプリ内に未実装 |
| ローカルLLM | 未実装 | MNN/GGUF/llama.cpp、モデルの大容量取得、端末RAM/ABI別の実測が必要 |
| LAN Web Chat / HTTP API | 未実装 | Android操作APIを認証なしで公開する経路は追加しない |

## この条件ではそのまま取り込まない機能

- APIキー・キーpool・従量課金のクラウドproviderは、依頼の最優先条件と異なるため追加していない。有料APIへの自動切替もない。
- AIに任意shellや任意Rootコマンドを渡す機能、承認や機密画面保護を解除する機能は、元の仕様を維持するため追加しない。
- 常時録音、バックグラウンドの自律実行、第三者コードの自動インストールは、権限・実行制御・認証を設計せずに有効化しない。

## 次の実装単位

1. SAFで許可したディレクトリを実ワークスペースに接続。選択範囲・競合・変更プレビュー・履歴を維持する。
2. PDF/画像/OCRのローカル処理と、送信前の内容確認。端末内の抽出とAIへ送る内容を別に制御する。
3. 認証付きMCPの限定接続。接続先とツールschemaを確認し、namespace別の権限、承認、停止、結果レビューを適用する。
4. 条件分岐を持つワークフローと本人起動の再利用。定期・外部トリガーは実行の同意とOS制約を検証してから追加する。
5. 任意コマンドをAIへ開放しない開発ツールカタログと、ローカルLLMのABI/メモリ別評価。

これは残作業を明示した計画で、未実装の機能がAPKに含まれるという表明ではない。

## 根拠と検証境界

- [Operit機能一覧](https://github.com/AAswordman/Operit/blob/dbf71916fae9750cfdc9f9a774f5a0fee56633fb/README.md)、[ワークフローrepository](https://github.com/AAswordman/Operit/blob/dbf71916fae9750cfdc9f9a774f5a0fee56633fb/app/src/main/java/com/ai/assistance/operit/data/repository/WorkflowRepository.kt)、[端末環境の管理](https://github.com/AAswordman/Operit/blob/dbf71916fae9750cfdc9f9a774f5a0fee56633fb/app/src/main/java/com/ai/assistance/operit/core/tools/system/OperitTerminalManager.kt)。Operit全体の端末動作試験は行っていない。
- [Android SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer)、[Voiceのネットワーク要否](https://developer.android.com/reference/android/speech/tts/Voice)、[TextToSpeech](https://developer.android.com/reference/android/speech/tts/TextToSpeech)。`EXTRA_PREFER_OFFLINE`を付けるだけではなく、端末内専用recognizerを使用する。
- [SAF](https://developer.android.com/training/data-storage/shared/documents-files)。保存先は本人がシステムUIで選ぶ。外部ファイルへ書き出す内容は平文になる場合がある。
- このアプリで実行した検証と未確認事項は [validation.md](validation.md)。
