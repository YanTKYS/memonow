package com.yantkys.memonow.metadata

// Only fixed labels and processing state; no coordinates, IDs, text, URLs or exception messages.
enum class MetadataProblem(val label: String) {
    OFF("自動付与がOFFです"), PERMISSION("位置権限がありません"),
    LOCATION_OFF("端末の位置情報がOFFです"), PROVIDER("利用できる位置プロバイダーがありません"),
    LOCATION_TIMEOUT("位置を取得できません（タイムアウト）"), LOCATION_EMPTY("位置を取得できません"),
    LOCATION_STALE("位置が古いため使用できません"), LOCATION_ACCURACY("位置の精度が不十分です"),
    LOCATION_INVALID("位置情報が不正です"), GEOCODER_UNSUPPORTED("端末が地名取得に対応していません"),
    PLACE_EMPTY("地名が見つかりません"), PLACE_FAILED("地名を取得できません"),
    PLACE_TIMEOUT("地名を取得できません（タイムアウト）"),
    WEATHER_NETWORK("天気APIに接続できません"), WEATHER_HTTP("天気APIがエラーを返しました"),
    WEATHER_UNAVAILABLE("天気APIが一時的に利用できません"),
    WEATHER_INVALID("天気APIの応答・単位が不正です"), WEATHER_TIMEOUT("天気を取得できません（タイムアウト）"),
    WEATHER_TIME("天気の時刻が投稿時刻から離れています"),
    EXPIRED("投稿から時間が経ったため終了しました"), BUSY("取得の待機上限に達したため終了しました"),
    BACKGROUND("画面を離れたため終了しました。次の新規投稿で取得します"),
    RESTORE("復元のため終了しました"), CANCELLED("処理を中断しました"),
    POST_CHANGED("投稿の削除・復元により追記できませんでした"), SAVE_FAILED("属性を保存できませんでした"),
}
class MetadataFailure(val problem: MetadataProblem) : Exception(problem.name)
enum class MetadataStage(val label: String) {
    QUEUED("取得の順番を待っています"), RETRY("一時的な失敗のため再試行を待っています"),
    IDLE("まだ取得していません"), START("取得を開始しました"), LOCATION("位置取得を待機中"),
    PLACE("位置取得済み・地名を取得中"), WEATHER("地名処理済み・天気を取得中"),
    SAVE("属性を保存中"), DONE("処理完了"), STOPPED("処理終了"),
}
data class MetadataDiagnostic(
    val stage: MetadataStage = MetadataStage.IDLE,
    val attempt: Int = 1,
    val stoppedAt: MetadataStage? = null,
    val problem: MetadataProblem? = null,
    val placeProblem: MetadataProblem? = null,
    val weatherProblem: MetadataProblem? = null,
    val placeSaved: Boolean = false,
    val weatherSaved: Boolean = false,
) {
    val message: String get() = listOfNotNull(
        if (stage == MetadataStage.RETRY) stage.label else problem?.label ?: if (stage == MetadataStage.DONE) when {
            placeSaved && weatherSaved -> "地名・天気を付与しました"
            placeSaved -> "地名のみ付与しました"
            weatherSaved -> "天気のみ付与しました"
            else -> "属性を付与できませんでした"
        } else stage.label,
        if (stage == MetadataStage.RETRY) problem?.label else null,
        if (attempt > 1) "自動再試行：2回目（上限）" else null,
        stoppedAt?.let { when (it) {
            MetadataStage.LOCATION -> "終了した段階：位置取得"
            MetadataStage.PLACE -> "終了した段階：地名取得"
            MetadataStage.WEATHER -> "終了した段階：天気取得"
            MetadataStage.SAVE -> "終了した段階：DB追記"
            else -> null
        } },
        placeProblem?.label, weatherProblem?.label,
    ).joinToString("。")
}
