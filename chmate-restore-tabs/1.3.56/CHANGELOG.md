# ChMate Restore Tabs 1.3.56

ChMate 0.8.10.191 dev 用。Morphe Manager で作者版 Haiagaru 1.45.0 とこの追加パッチを選び、「MEGAバックアップ・同期」「処理ログ（MEGA）」を有効にします。

- MEGAのバックアップ・復元の開始、完了、失敗をローカルに記録します。認証情報、URL、バックアップ本文は記録しません。
- 「処理ログをMEGAへ自動保存」は初期オフです。保存先はMEGAの `Haiagaru-processing-logs` です。
- 「MEGAの処理ログをダウンロード」でログを選び、端末の「ダウンロード/Haiagaru」に保存できます。

GitHub Actions #45757542168 の配布用MPPを作者版と同時に適用し、18パッチの適用とAPK生成、処理ログへの呼び出しと既存のZIP再取得処理を生成APKで確認済みです。実機操作とMEGAへの通信は未検証です。詳細は `validation.json` を参照してください。

## 処理ログのソース

同梱の処理ログ部分は [Haiagaru-Morphe 1.45.0](https://github.com/areteruhiro/Haiagaru-Morphe/tree/030b7615364d0b1157fab6b0e416f5e8c4ac9e4e) を改変したものです。GPLv3および同梱の NOTICE が適用されます。対応する差分とビルド用ファイルは `source` にあります。

JDK 21 で上記のコミットをチェックアウトし、`source/haiagaru-processing-log.patch` を `git apply` で適用して `bash gradlew :patches:buildAndroid --no-daemon` を実行します。出力したMPPを `work/patched.mpp`、公式配布の1.45.0を `work/official.mpp`、このリポジトリの1.3.55を `work/base.mpp` として、`source/build-processing-log-addon.yml` のコンパイル工程を実行します。再ビルド時は生成物のハッシュが変わる場合があります。
