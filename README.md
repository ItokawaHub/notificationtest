# FCM Notification Test App

FCM（Firebase Cloud Messaging）の通知動作を検証するためのAndroidアプリです。

## セットアップ

### 1. google-services.json の設置

1. [Firebase Console](https://console.firebase.google.com/) でプロジェクトを開く
2. プロジェクトの設定 > 全般 > マイアプリ からAndroidアプリを選択
3. `google-services.json` をダウンロード
4. ダウンロードしたファイルを以下に配置：
   ```
   app/google-services.json
   ```

### 2. service-account.json の設置（FCM API送信用）

1. [Firebase Console](https://console.firebase.google.com/) でプロジェクトを開く
2. プロジェクトの設定 > サービスアカウント を選択
3. 「新しい秘密鍵の生成」をクリック
4. ダウンロードしたJSONファイルを `service-account.json` にリネーム
5. 以下に配置：
   ```
   app/src/main/assets/service-account.json
   ```

> **注意**: `service-account.json` には秘密鍵が含まれています。gitにコミットしないでください（`.gitignore` で除外済み）。

## 機能

- FCMトークンの取得・コピー
- サービスアカウントを使用したアクセストークンの自動取得
- 各種ペイロードの通知送信テスト
  - `notification`: 通知ペイロード
  - `data`: データペイロード
  - `android`: Android固有設定（channel_id等）
  - `fcm_options`: Analytics用ラベル

## 通知の挙動

| ペイロード              | バックグラウンド | フォアグラウンド     |
| ----------------------- | ---------------- | -------------------- |
| `notification` のみ     | 表示される       | 表示される(独自実装) |
| `data` のみ             | 表示されない     | 表示されない         |
| `notification` + `data` | 表示される       | 表示される           |
