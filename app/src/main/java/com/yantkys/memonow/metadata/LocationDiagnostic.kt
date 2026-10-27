package com.yantkys.memonow.metadata

// Fixed labels only. These per-attempt snapshots contain no coordinates or identifiers.
enum class LocationProvider(val label: String) { FUSED("統合位置"), NETWORK("ネットワーク位置") }
enum class LocationCache(val label: String) {
    UNAVAILABLE("利用不可"), EMPTY("なし"), FRESH("有効"), STALE("古い／時刻不適合"),
    ACCURACY("精度不足"), INVALID("不正"),
}
enum class LocationResult(val label: String) {
    DISABLED("候補なし／無効"), NOT_REQUESTED("要求なし"), CACHE("キャッシュ採用"),
    ACQUIRED("取得成功"), EMPTY("位置なし応答"), TIMEOUT("期限内に応答なし"),
    PERMISSION("権限なし"), STALE("古い／時刻不適合"), ACCURACY("精度不足"),
    ERROR("取得APIの失敗"), INVALID("不正な位置"), CANCELLED("他候補採用により解除"), UNAVAILABLE("候補消失／取得不可"),
}
data class LocationCandidate(val provider: LocationProvider, val cache: LocationCache, val result: LocationResult)
data class LocationDiagnostic(val candidates: List<LocationCandidate>) {
    val message: String get() = candidates.joinToString("。") {
        "${it.provider.label}：キャッシュ${it.cache.label}・${it.result.label}"
    }
}
