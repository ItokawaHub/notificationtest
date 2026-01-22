package com.example.notification_test

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.notification_test.ui.theme.NotificationtestTheme
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * メッセージタイプを表すデータクラス
 */
data class FcmMessageConfig(
    val includeNotification: Boolean = true,
    val includeData: Boolean = false,
    val includeFcmOptions: Boolean = true,
    val includeAndroidConfig: Boolean = true,
    val channelId: String = "default_channel"
)

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val FCM_PROJECT_ID = "notification-test-2fae3"
    }

    private lateinit var firebaseAnalytics: FirebaseAnalytics
    private var fcmToken by mutableStateOf("Loading...")
    private var accessToken by mutableStateOf("")
    private var isLoadingToken by mutableStateOf(false)
    private var hasServiceAccount by mutableStateOf(false)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Log.d(TAG, "Notification permission granted")
        } else {
            Log.d(TAG, "Notification permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        firebaseAnalytics = FirebaseAnalytics.getInstance(this)

        requestNotificationPermission()
        getFcmToken()
        handleNotificationOpen(intent.extras)

        // サービスアカウントの有無をチェック
        hasServiceAccount = FcmAuthHelper.hasServiceAccount(this)

        enableEdgeToEdge()
        setContent {
            NotificationtestTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainContent(
                        fcmToken = fcmToken,
                        accessToken = accessToken,
                        isLoadingToken = isLoadingToken,
                        hasServiceAccount = hasServiceAccount,
                        onAccessTokenChange = { accessToken = it },
                        onGetAccessToken = { fetchAccessToken() },
                        onSendNotification = { title, body, dataPayload, analyticsLabel, config ->
                            sendFcmNotification(accessToken, title, body, dataPayload, analyticsLabel, config)
                        },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleNotificationOpen(intent.extras)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    Log.d(TAG, "Notification permission already granted")
                }
                else -> {
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }

    private fun getFcmToken() {
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                Log.w(TAG, "Fetching FCM token failed", task.exception)
                fcmToken = "Failed to get token"
                return@addOnCompleteListener
            }

            val token = task.result
            Log.d(TAG, "FCM Token: $token")
            fcmToken = token
        }
    }

    private fun handleNotificationOpen(extras: Bundle?) {
        if (extras?.getBoolean("notification_opened", false) == true) {
            Log.d(TAG, "App opened from notification")

            val params = Bundle().apply {
                putString("source", "push_notification")
            }
            firebaseAnalytics.logEvent("notification_opened", params)
        }
    }

    private fun fetchAccessToken() {
        if (!hasServiceAccount) {
            Toast.makeText(this, "service-account.json が assets に見つかりません", Toast.LENGTH_LONG).show()
            return
        }

        isLoadingToken = true
        CoroutineScope(Dispatchers.Main).launch {
            val result = FcmAuthHelper.getAccessToken(this@MainActivity)
            result.onSuccess { token ->
                accessToken = token
                Toast.makeText(this@MainActivity, "Access Token 取得成功", Toast.LENGTH_SHORT).show()
                Log.d(TAG, "Access Token obtained successfully")
            }.onFailure { error ->
                Toast.makeText(this@MainActivity, "エラー: ${error.message}", Toast.LENGTH_LONG).show()
                Log.e(TAG, "Failed to get access token", error)
            }
            isLoadingToken = false
        }
    }

    private fun sendFcmNotification(
        accessToken: String,
        title: String,
        body: String,
        dataPayload: Map<String, String>,
        analyticsLabel: String,
        config: FcmMessageConfig
    ) {
        if (accessToken.isBlank()) {
            Toast.makeText(this, "Access Tokenを取得してください", Toast.LENGTH_SHORT).show()
            return
        }

        if (fcmToken == "Loading..." || fcmToken == "Failed to get token") {
            Toast.makeText(this, "FCMトークンがまだ取得できていません", Toast.LENGTH_SHORT).show()
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = OkHttpClient()

                val messageJson = JSONObject().apply {
                    put("token", fcmToken)

                    // notification ペイロード
                    if (config.includeNotification) {
                        put("notification", JSONObject().apply {
                            put("title", title)
                            put("body", body)
                        })
                    }

                    // data ペイロード
                    if (config.includeData && dataPayload.isNotEmpty()) {
                        put("data", JSONObject().apply {
                            dataPayload.forEach { (key, value) ->
                                put(key, value)
                            }
                        })
                    }

                    // android 固有設定（channel_id など）
                    if (config.includeAndroidConfig && config.channelId.isNotBlank()) {
                        put("android", JSONObject().apply {
                            put("notification", JSONObject().apply {
                                put("channel_id", config.channelId)
                            })
                        })
                    }

                    // fcm_options
                    if (config.includeFcmOptions && analyticsLabel.isNotBlank()) {
                        put("fcm_options", JSONObject().apply {
                            put("analytics_label", analyticsLabel)
                        })
                    }
                }

                val jsonBody = JSONObject().apply {
                    put("message", messageJson)
                }

                val requestBody = jsonBody.toString()
                    .toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url("https://fcm.googleapis.com/v1/projects/$FCM_PROJECT_ID/messages:send")
                    .addHeader("Authorization", "Bearer $accessToken")
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody)
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string()

                withContext(Dispatchers.Main) {
                    if (response.isSuccessful) {
                        Toast.makeText(
                            this@MainActivity,
                            "通知を送信しました",
                            Toast.LENGTH_SHORT
                        ).show()
                        Log.d(TAG, "FCM API Response: $responseBody")
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            "エラー: ${response.code}",
                            Toast.LENGTH_LONG
                        ).show()
                        Log.e(TAG, "FCM API Error: $responseBody")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "通信エラー: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                    Log.e(TAG, "FCM API Exception", e)
                }
            }
        }
    }
}

@Composable
fun MainContent(
    fcmToken: String,
    accessToken: String,
    isLoadingToken: Boolean,
    hasServiceAccount: Boolean,
    onAccessTokenChange: (String) -> Unit,
    onGetAccessToken: () -> Unit,
    onSendNotification: (title: String, body: String, dataPayload: Map<String, String>, analyticsLabel: String, config: FcmMessageConfig) -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboardManager = LocalClipboardManager.current
    val scrollState = rememberScrollState()

    // Notification設定
    var notificationTitle by remember { mutableStateOf("Test Notification") }
    var notificationBody by remember { mutableStateOf("This is a test notification") }

    // Data設定
    var dataKey1 by remember { mutableStateOf("action") }
    var dataValue1 by remember { mutableStateOf("OPEN_DETAIL") }
    var dataKey2 by remember { mutableStateOf("item_id") }
    var dataValue2 by remember { mutableStateOf("12345") }

    // fcm_options設定
    var analyticsLabel by remember { mutableStateOf("test_campaign") }

    // メッセージタイプ設定
    var includeNotification by remember { mutableStateOf(true) }
    var includeData by remember { mutableStateOf(false) }
    var includeAndroidConfig by remember { mutableStateOf(true) }
    var includeFcmOptions by remember { mutableStateOf(true) }

    // Android固有設定
    var channelId by remember { mutableStateOf("default_channel") }

    var isSending by remember { mutableStateOf(false) }

    // データペイロードをMapに変換
    val dataPayload = remember(dataKey1, dataValue1, dataKey2, dataValue2) {
        buildMap {
            if (dataKey1.isNotBlank() && dataValue1.isNotBlank()) {
                put(dataKey1, dataValue1)
            }
            if (dataKey2.isNotBlank() && dataValue2.isNotBlank()) {
                put(dataKey2, dataValue2)
            }
        }
    }

    // JSONペイロードを生成
    val jsonPayload = remember(
        fcmToken, notificationTitle, notificationBody,
        dataPayload, analyticsLabel, channelId,
        includeNotification, includeData, includeAndroidConfig, includeFcmOptions
    ) {
        buildJsonPayload(
            token = fcmToken,
            title = notificationTitle,
            body = notificationBody,
            dataPayload = dataPayload,
            analyticsLabel = analyticsLabel,
            config = FcmMessageConfig(
                includeNotification = includeNotification,
                includeData = includeData,
                includeFcmOptions = includeFcmOptions,
                includeAndroidConfig = includeAndroidConfig,
                channelId = channelId
            )
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        Text(text = "FCM Token:")
        Text(
            text = fcmToken,
            modifier = Modifier.padding(top = 8.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = {
                clipboardManager.setText(AnnotatedString(fcmToken))
            }
        ) {
            Text("Copy Token")
        }

        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "--- FCM API Test ---",
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(modifier = Modifier.height(16.dp))

        // サービスアカウント状態表示
        Text(
            text = if (hasServiceAccount) "service-account.json: Found" else "service-account.json: Not Found",
            style = MaterialTheme.typography.bodySmall,
            color = if (hasServiceAccount) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Access Token取得ボタン
        Button(
            onClick = onGetAccessToken,
            enabled = hasServiceAccount && !isLoadingToken,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isLoadingToken) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("取得中...")
            } else {
                Text("Get Access Token (自動)")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Access Token表示
        OutlinedTextField(
            value = if (accessToken.length > 50) "${accessToken.take(50)}..." else accessToken,
            onValueChange = onAccessTokenChange,
            label = { Text("Access Token") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            readOnly = false
        )

        if (accessToken.isNotBlank()) {
            Text(
                text = "Token取得済み (${accessToken.length} chars)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))

        // メッセージタイプ選択
        Text(
            text = "Message Type:",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.align(Alignment.Start)
        )

        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = includeNotification,
                    onCheckedChange = { includeNotification = it }
                )
                Text("notification")
                Spacer(modifier = Modifier.width(8.dp))
                Checkbox(
                    checked = includeData,
                    onCheckedChange = { includeData = it }
                )
                Text("data")
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = includeAndroidConfig,
                    onCheckedChange = { includeAndroidConfig = it }
                )
                Text("android")
                Spacer(modifier = Modifier.width(8.dp))
                Checkbox(
                    checked = includeFcmOptions,
                    onCheckedChange = { includeFcmOptions = it }
                )
                Text("fcm_options")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Notification設定
        if (includeNotification) {
            Text(
                text = "Notification Payload:",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.align(Alignment.Start)
            )
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = notificationTitle,
                onValueChange = { notificationTitle = it },
                label = { Text("title") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = notificationBody,
                onValueChange = { notificationBody = it },
                label = { Text("body") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))
        }

        // Data設定
        if (includeData) {
            Text(
                text = "Data Payload:",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.align(Alignment.Start)
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = dataKey1,
                    onValueChange = { dataKey1 = it },
                    label = { Text("key") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedTextField(
                    value = dataValue1,
                    onValueChange = { dataValue1 = it },
                    label = { Text("value") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = dataKey2,
                    onValueChange = { dataKey2 = it },
                    label = { Text("key") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedTextField(
                    value = dataValue2,
                    onValueChange = { dataValue2 = it },
                    label = { Text("value") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // Android固有設定
        if (includeAndroidConfig) {
            Text(
                text = "Android Config:",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.align(Alignment.Start)
            )
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = channelId,
                onValueChange = { channelId = it },
                label = { Text("notification.channel_id") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))
        }

        // fcm_options設定
        if (includeFcmOptions) {
            Text(
                text = "fcm_options:",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.align(Alignment.Start)
            )
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = analyticsLabel,
                onValueChange = { analyticsLabel = it },
                label = { Text("analytics_label") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))
        }

        // JSONペイロードプレビュー
        Text(
            text = "Request Payload Preview:",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.align(Alignment.Start)
        )
        Spacer(modifier = Modifier.height(4.dp))

        val horizontalScrollState = rememberScrollState()
        Text(
            text = jsonPayload,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp)
                )
                .padding(12.dp)
                .horizontalScroll(horizontalScrollState)
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 送信ボタン
        Button(
            onClick = {
                isSending = true
                onSendNotification(
                    notificationTitle,
                    notificationBody,
                    dataPayload,
                    analyticsLabel,
                    FcmMessageConfig(
                        includeNotification = includeNotification,
                        includeData = includeData,
                        includeFcmOptions = includeFcmOptions,
                        includeAndroidConfig = includeAndroidConfig,
                        channelId = channelId
                    )
                )
                isSending = false
            },
            enabled = !isSending && accessToken.isNotBlank() && (includeNotification || includeData),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isSending) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
            } else {
                val typeText = buildString {
                    if (includeNotification) append("notification")
                    if (includeNotification && includeData) append(" + ")
                    if (includeData) append("data")
                }
                Text("Send ($typeText)")
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

/**
 * FCM APIリクエスト用のJSONペイロードを整形して生成
 */
private fun buildJsonPayload(
    token: String,
    title: String,
    body: String,
    dataPayload: Map<String, String>,
    analyticsLabel: String,
    config: FcmMessageConfig
): String {
    val tokenDisplay = if (token.length > 20) "${token.take(20)}..." else token

    return buildString {
        appendLine("{")
        appendLine("  \"message\": {")
        appendLine("    \"token\": \"$tokenDisplay\",")

        val parts = mutableListOf<String>()

        // notification
        if (config.includeNotification) {
            parts.add(buildString {
                appendLine("    \"notification\": {")
                appendLine("      \"title\": \"$title\",")
                appendLine("      \"body\": \"$body\"")
                append("    }")
            })
        }

        // data
        if (config.includeData && dataPayload.isNotEmpty()) {
            parts.add(buildString {
                appendLine("    \"data\": {")
                val dataEntries = dataPayload.entries.toList()
                dataEntries.forEachIndexed { index, (key, value) ->
                    if (index < dataEntries.size - 1) {
                        appendLine("      \"$key\": \"$value\",")
                    } else {
                        appendLine("      \"$key\": \"$value\"")
                    }
                }
                append("    }")
            })
        }

        // android (channel_id)
        if (config.includeAndroidConfig && config.channelId.isNotBlank()) {
            parts.add(buildString {
                appendLine("    \"android\": {")
                appendLine("      \"notification\": {")
                appendLine("        \"channel_id\": \"${config.channelId}\"")
                appendLine("      }")
                append("    }")
            })
        }

        // fcm_options
        if (config.includeFcmOptions && analyticsLabel.isNotBlank()) {
            parts.add(buildString {
                appendLine("    \"fcm_options\": {")
                appendLine("      \"analytics_label\": \"$analyticsLabel\"")
                append("    }")
            })
        }

        // パーツを結合
        parts.forEachIndexed { index, part ->
            append(part)
            if (index < parts.size - 1) {
                appendLine(",")
            } else {
                appendLine()
            }
        }

        appendLine("  }")
        append("}")
    }
}

@Preview(showBackground = true)
@Composable
fun MainContentPreview() {
    NotificationtestTheme {
        MainContent(
            fcmToken = "sample_token",
            accessToken = "",
            isLoadingToken = false,
            hasServiceAccount = true,
            onAccessTokenChange = {},
            onGetAccessToken = {},
            onSendNotification = { _, _, _, _, _ -> }
        )
    }
}
