# Rootでの内部データ一覧・ローカルコピー（v0.3.0）

## 修正の理由

v0.2.0のRootツールはUID確認と一般アプリの強制停止だけだった。モデルに「Rootなら/dataを読める」と伝えても、実際にデータをコピーする実行機能がなかった。v0.3.0では実行ツールとモデルへの機能説明の両方を追加した。通常の共有ストレージ一覧の制限を解除して代用する設計にはしない。

## 使い方

1. 「接続・権限」でRoot連携を有効化し、Root管理アプリでKomichiに許可する。「Root UIDを確認」で実際のUID 0を確認する。
2. 対象アプリを個別に許可するか、全アクセスモードを有効化する。
3. チャットで、例えば `com.example.notes の内部データを Download/notes-backup-001 にコピーして` と指示する。アプリ名しか分からない場合は検索できる。対象や保存先が不明ならモデルが確認する。
4. 実行確認でパッケージ、コピー元、保存先、形式、影響を確認する。既存の保存先は使わず、新しい名前を指定する。
5. 完了・失敗の結果を確認する。AI接続なしで試す場合は「内部データの一覧・コピー」から同じツールを呼べる。結果はチャットに表示され、AIへ送信しない。

全体コピーのコピー元は `.`。`files`、`databases` 等の相対パスや、対象パッケージの絶対パスも指定できる。ログイン状態や個人情報が含まれる場合があるため、バックアップの保管・共有には注意する。

## ツールと権限

| ツール | 引数 | 動作 |
|---|---|---|
| `list_app_data` | package, path | 対象ディレクトリの名前・種類を最大60件取得。内容は読まない |
| `copy_app_data` | package, source_path, destination, format | `directory` はファイル／フォルダのローカルコピー、`tar` は `.tar` ファイルを新規作成 |

両方ともRoot opt-in、対象アプリ許可、引数検証、個別承認、実UIDチェックが必要。自アプリのデータ・認証情報は対象外。Root管理側の拒否は `ROOT_NOT_GRANTED`、コピー元の範囲外は `SOURCE_SCOPE`、既存の保存先は `DESTINATION_EXISTS` 等として返す。失敗を成功扱いせず、確認後にモデルへ実際の結果を返す。

コピー元はPackageManagerの `ApplicationInfo.dataDir` / `deviceProtectedDataDir` が示す同じユーザーの領域。`/data/user/<id>/<package>`、`/data/user_de/<id>/<package>`、ユーザー0の `/data/data/<package>` 別名に対応する。`..`、制御文字、別アプリのパス、別ユーザーへの切替は拒否。Root側でも実パスを再検証し、開いたソースのdescriptorを固定する。コピーはsymlinkを辿らず、TARもリンク先を取り込まない。

保存先は端末のプライマリ共有ストレージ内の新しいパス。`/sdcard` 別名を使える。`Android/data` / `Android/obb`、既存の保存先、途中のsymlinkを拒否する。Rootが書き込むため、この機能自体に全ファイルアクセス権限や全アクセスモードは不要。対象の個別アプリ許可でも利用できる。通常の `list_directory` には従来どおり全モードとOSファイル権限が必要。

コピー元の削除・変更、任意shell、SELinux変更、権限変更、アプリのアンインストール、任意のシステム領域のコピーは提供しない。実行器は固定テンプレートだけで、変数はshell引用し、展開後に再評価しない。操作履歴にはツール名と状態だけを保存する。ファイル内容はAIへ渡さず、パス・一覧等の結果情報も外部送信前に確認・編集できる。

## 時間・停止・限界

コピー本体は最大180秒。全体のチャット実行は10分、最大8操作。停止時にはアプリprivateの実行許可ファイルを削除し、開始前・実行中のチェックで遅れて許可されたRoot処理も停止する。把握したプロセスはジョブnonceを照合して停止する。既に保存したデータは巻き戻さず、失敗・停止時は部分コピーと `.komichi-<job>.incomplete` が残る場合がある。自動で保存先を再帰削除せず、新しい名前で再試行する。

共有ストレージがsymlinkに対応しない場合、通常コピーは失敗し得る。リンクを保存する場合は本人がTAR形式を選ぶ。形式の自動切替はしない。コピーはデータ取得用であり、所有者・SELinuxラベル等の完全な復元やアプリへのリストアは提供しない。稼働中のデータベースには整合性を保証できないため、必要なら対象アプリを別の承認操作で終了してからコピーする。

Rootでもロック中のCredential Encrypted領域を自動復号できない。端末のロック解除、Rootのマウント名前空間、SELinux、空き容量、OEMやRoot管理方式によって読み書きに失敗する場合がある。エラーを返し、セキュリティ設定を勝手に変更しない。Magisk / KernelSU / KernelSU Next / 一時Rootの実端末での互換性確認は残る。

根拠: [Android ApplicationInfo](https://developer.android.com/reference/android/content/pm/ApplicationInfo)、[Android File-Based Encryption](https://source.android.com/docs/security/features/encryption/file-based)、[Magisk su](https://github.com/topjohnwu/Magisk/blob/master/docs/tools.md)。

## Operitを参考にした点

調査したOperitのmainは `dbf71916fae9750cfdc9f9a774f5a0fee56633fb`。以下はソースから確認した事項。

- [ToolGetter](https://github.com/AAswordman/Operit/blob/dbf71916fae9750cfdc9f9a774f5a0fee56633fb/app/src/main/java/com/ai/assistance/operit/core/tools/defaultTool/ToolGetter.kt): 選択中の権限レベルに応じてファイル操作の実装を選ぶ。
- [RootFileSystemTools](https://github.com/AAswordman/Operit/blob/dbf71916fae9750cfdc9f9a774f5a0fee56633fb/app/src/main/java/com/ai/assistance/operit/core/tools/defaultTool/root/RootFileSystemTools.kt)、[DebuggerFileSystemTools](https://github.com/AAswordman/Operit/blob/dbf71916fae9750cfdc9f9a774f5a0fee56633fb/app/src/main/java/com/ai/assistance/operit/core/tools/defaultTool/debugger/DebuggerFileSystemTools.kt): Root用実装が管理・デバッガ用実装を継承し、コピー元・保存先の確認やコピー実行を行う。
- [ShellExecutorFactory](https://github.com/AAswordman/Operit/blob/dbf71916fae9750cfdc9f9a774f5a0fee56633fb/app/src/main/java/com/ai/assistance/operit/core/tools/system/shell/ShellExecutorFactory.kt): Root等の実行バックエンドを選ぶ。

Komichiでは「権限に応じた実行器へ、実際のファイル操作ツールを渡す」という構造を参考にした。Operitの汎用shellや全機能と同等だという意味ではない。OperitはLGPL-3.0。実装コード・資産はコピーせず、このアプリの固定ツールとして独立実装した。OperitのRoot方式が全端末で動作することも、この調査だけでは検証していない。
