package com.mapconductor.longdo

import com.longdo.sdk3.LongdoMap
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * Longdo Map API3 SDK のグローバル初期化を担うヘルパ。
 *
 * Longdo Map は [LongdoMap.API_KEY]（API キー）と [LongdoMap.PACKAGE_NAME]（登録済みパッケージ名）を
 * 設定してから地図を `load()` する必要がある。キーはアプリの `AndroidManifest.xml` の
 * `<meta-data android:name="longdo.map.key" .../>` から読み取る（Secrets Gradle Plugin により
 * `secrets.properties` の `LONGDO_API_KEY` が注入される）。
 *
 * アプリ側で直接 [LongdoMap.API_KEY] を設定している場合はそちらを優先する。
 */
object LongdoInitSDK {
    private const val META_DATA_KEY = "longdo.map.key"
    private const val TAG = "LongdoInitSDK"

    /**
     * API キーが未設定なら Manifest の meta-data から読み込んで設定する。
     * パッケージ名は Longdo コンソールの登録と照合されるため、常にアプリのパッケージ名を設定する。
     *
     * @param context アプリ／アクティビティのコンテキスト。
     * @param apiKey 明示的に指定する API キー（省略時は Manifest から取得）。
     * @return キーが設定できた場合 true。
     */
    fun ensureInitialized(
        context: Context,
        apiKey: String? = null,
    ): Boolean {
        // パッケージ名は毎回設定しておく（Longdo の登録アプリ照合に必要）。
        if (LongdoMap.PACKAGE_NAME.isNullOrBlank()) {
            LongdoMap.PACKAGE_NAME = context.packageName
        }

        if (!LongdoMap.API_KEY.isNullOrBlank()) return true

        val resolved = apiKey?.takeIf { it.isNotBlank() } ?: readApiKeyFromManifest(context)
        if (resolved.isNullOrBlank()) {
            Log.w(
                TAG,
                "Longdo Map API key not found. Set LongdoMap.API_KEY directly or add a " +
                    "<meta-data android:name=\"$META_DATA_KEY\" .../> entry to your AndroidManifest.",
            )
            return false
        }

        LongdoMap.API_KEY = resolved
        return true
    }

    private fun readApiKeyFromManifest(context: Context): String? =
        runCatching {
            val appInfo =
                context.packageManager.getApplicationInfo(
                    context.packageName,
                    PackageManager.GET_META_DATA,
                )
            appInfo.metaData?.getString(META_DATA_KEY)
        }.getOrNull()
}
