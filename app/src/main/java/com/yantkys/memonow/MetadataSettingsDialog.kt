package com.yantkys.memonow

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun MetadataSettingsDialog(app: MemonowApplication, onDisable: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val settings = app.metadataSettings
    val enabled by settings.enabled.collectAsStateWithLifecycle()
    val diagnostic by app.postMetadata.diagnostic.collectAsStateWithLifecycle()
    var permitted by remember { mutableStateOf(app.locationSource.hasPermission()) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permitted = app.locationSource.hasPermission()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permitted = granted
        settings.setEnabled(granted)
        if (!granted) app.postMetadata.interrupt(com.yantkys.memonow.metadata.MetadataProblem.PERMISSION)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("位置・天気の自動付与（任意）") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("投稿を先に保存し、取得できた市区町村名・天気・気温だけを後から追加します。失敗しても投稿できます。")
                Text("ONの場合、おおよその位置を投稿時だけ取得します。座標は約1km単位（小数2桁）に丸め、Androidの地名解決サービスとOpen-Meteoへ送ります。サービス側にはIPアドレスも伝わります。Open-Meteoは座標を含み得るログを最大90日保持するとしています。")
                Text("端末には地名・天気・気温を追加保存し、座標は保存しません。本文・履歴・投稿IDは外部送信しません。OFF時は位置・天気を取得しません。")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (enabled) "自動付与 ON" else "自動付与 OFF", Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { value ->
                        if (!value) {
                            settings.setEnabled(false)
                            onDisable()
                        } else if (app.locationSource.hasPermission()) {
                            permitted = true
                            settings.setEnabled(true)
                        } else if (!settings.permissionAsked) {
                            settings.permissionAsked = true
                            permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                        }
                    })
                }
                Text("最後の自動付与結果：${diagnostic.message}")
                Text("結果は今回の起動中だけ保持します。新規投稿で更新し、過去投稿は再取得しません。")
                if (!permitted && settings.permissionAsked) {
                    Text("位置権限がありません。繰り返し要求はしません。利用する場合は端末の設定で位置を許可し、戻ってからONにしてください。")
                    TextButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                    }) { Text("端末のアプリ設定を開く") }
                }
                Text("一時的な取得失敗は2秒後に1回だけ再試行します。連続投稿は上限付きで順番を待ちます。画面離脱・設定OFFで中断し、投稿後2分を超えて補完しません。取得を保証するものではありません。OFFにしても既存の属性は残ります。")
                Text("天気は投稿時刻に近い推定値です（最大20分差）。Weather data by Open-Meteo.com · CC BY 4.0。無料APIは非商用利用向けです。")
                TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://open-meteo.com/en/terms"))) }) { Text("Open-Meteo（出典・利用条件）") }
                TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://creativecommons.org/licenses/by/4.0/"))) }) { Text("CC BY 4.0 ライセンス") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
    )
}
