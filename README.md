# NicoTVOverlay

Android TV / Google TV の画面に、ニコニコ実況のリアルタイムコメントを重ねるネイティブAndroidアプリです。Kotlin、Android 8.0（API 26）以降。動画・音声の受信や録画、テレビの選局操作、コメント投稿は行いません。

**早期テスト版です。実際のテレビ機種での動作は未検証です。全機種での自動選局・放送映像への重ね表示は保証しません。**

## できること

- **ニコニコ公式**の現在の実況番組を解決し、コメントを匿名・読み取り専用で受信。放送IDを固定しません。
- 流れるコメント・上／下固定、文字サイズ、速度、透明度、最大表示数、0〜30秒の遅延、NGワード。
- **手動固定**、機種別に校正する**自動OSD**、実験的な**BRAVIA連携**。
- NX-Jikkyoは明示選択する別サービス。公式のエラー時に勝手に切り替えません。
- リモコンのD-pad操作に対応。重ね表示ウィンドウはフォーカス・タッチを奪いません。
- Stop、画面消灯、必要な権限の取消で終了。端末やアプリの起動だけでは再開しません。

## インストール

1. [GitHub Actions](https://github.com/nmt3325/NicoTVOverlay/actions) の成功した実行を開き、`NicoTVOverlay-debug-…` をダウンロードします（GitHubへのログインが必要）。
2. ZIPから`app-debug.apk`を取り出し、**テレビ本体のAndroid OS**へインストールします。開発用署名付きAPKです。`release-unsigned`はそのままインストールできません。
3. アプリの「詳細・権限」から重ね表示を許可し、まず「手動」「ニコニコ公式」で局を選び「開始」を押します。
4. 放送アプリへ戻ります。テレビ側を選局したら、手動モードでは実況局も変更してください。

詳しくは **[テレビへの導入と自動選局の設定](docs/DEVICE_SETUP.md)** を参照してください。

## 自動選局の重要な制約

Androidには、一般アプリから別のテレビアプリの現在局を読む共通の公開APIがありません。本アプリは隠しAPI・root・視聴履歴への特権アクセスを使いません。

- **自動OSD:** ユーザー補助の明示許可と、テレビアプリの正確なパッケージ名・現在局ID・ライブ専用目印IDの設定が必要です。番組表やHomeなど対象外になれば旧コメントを消します。一般の校正プロファイルの局情報は30秒で失効します。AQUOS向けの実機確認済み設定では、全画面ライブを毎秒再確認し、局表示が消えた後も確定済みの局に追従します。初回・画面復帰後に未検出なら、選局またはリモコンの画面表示を押してください。
- **BRAVIA:** アプリをそのBRAVIA自身にインストールし、設定したプライベートIPv4が端末自身のアドレスと一致する必要があります。さらにユーザー補助でライブ視聴画面を確認し、Sony RESTの放送URIを実況局に対応付けます。LAN上の別テレビを遠隔追跡する機能ではありません。機種・ファームウェアによって利用できません。
- **外付けHDMIボックス:** テレビ本体が別入力の内蔵チューナーを表示している画面には、外付けボックスから重ね描きできません。
- 内蔵チューナー、HDMI、HDR、DRM、重ね表示を禁止するアプリはメーカーの映像合成方式・制限に依存します。

## ビルドと検証

JDK 17、Android SDK 35を使用します。Gradle Wrapperを同梱しています。

```bash
./gradlew --no-daemon :core:test :comment-client:test \
  :detection:testDebugUnitTest :detection:lintDebug \
  :overlay:testDebugUnitTest :overlay:lintDebug \
  :app:testDebugUnitTest :app:lintDebug \
  :app:assembleDebug :app:assembleRelease
```

mainへのpush、pull request、手動実行でテスト・lint・APKビルドを実行します。Actionsの成果物は30日保存されます。通常CIのテストは外部サービスへの接続に依存しません。公式サービスへの実接続試験は任意実行です。

`tv-fixture`は自動選局を検証する別APKです。実放送・チューナー・動画プレイヤーではありません。端末内の合成デモも公式コメントの受信確認とは区別します。

今回の実行結果と未検証範囲は[検証記録](docs/VALIDATION.md)を参照してください。

## 設計・調査

- [公式コメントAPIと受信方式](docs/research/comment-api.md)
- [現在局を知る方法とAndroidの制約](docs/research/station-detection.md)
- [描画と混雑時の制御](docs/research/rendering.md)
- [アプリの権限とライフサイクル](docs/research/app-lifecycle.md)

調査の起点は[ニコニコ実況](https://site.nicovideo.jp/jk/)。[NCOverlay](https://github.com/Midra429/NCOverlay)の設計も参考にしましたが、ブラウザ拡張の移植ではなくAndroidのネイティブ描画を使用しています。公式protobufスキーマのMIT通知はソースとAPK内に同梱しています。サービスの変更により接続できなくなる場合があります。
