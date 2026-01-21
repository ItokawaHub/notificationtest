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
import androidx.compose.material3.CircularProgressIndicator
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
                        onSendNotification = { title, body, analyticsLabel ->
                            sendFcmNotification(accessToken, title, body, analyticsLabel)
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

    private fun sendFcmNotification(accessToken: String, title: String, body: String, analyticsLabel: String) {
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

                val jsonBody = JSONObject().apply {
                    put("message", JSONObject().apply {
                        put("token", fcmToken)
                        put("notification", JSONObject().apply {
                            put("title", title)
                            put("body", body)
                        })
                        // Analytics labelを追加してFirebase Consoleのレポートに集計
                        if (analyticsLabel.isNotBlank()) {
                            put("fcm_options", JSONObject().apply {
                                put("analytics_label", analyticsLabel)
                            })
                        }
                    })
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
    onSendNotification: (title: String, body: String, analyticsLabel: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboardManager = LocalClipboardManager.current
    val scrollState = rememberScrollState()

    var notificationTitle by remember { mutableStateOf("Test Notification") }
    var notificationBody by remember { mutableStateOf("This is a test notification from the app") }
    var analyticsLabel by remember { mutableStateOf("test_campaign") }
    var isSending by remember { mutableStateOf(false) }

    // JSONペイロードを生成（fcm_optionsあり/なし）
    val jsonPayloadWithOptions = remember(fcmToken, notificationTitle, notificationBody, analyticsLabel) {
        buildJsonPayload(fcmToken, notificationTitle, notificationBody, analyticsLabel, includeFcmOptions = true)
    }
    val jsonPayloadWithoutOptions = remember(fcmToken, notificationTitle, notificationBody) {
        buildJsonPayload(fcmToken, notificationTitle, notificationBody, "", includeFcmOptions = false)
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

        Spacer(modifier = Modifier.height(32.dp))

        Text(text = "--- FCM API Test ---")

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

        // Access Token表示（読み取り専用風）
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

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = notificationTitle,
            onValueChange = { notificationTitle = it },
            label = { Text("Notification Title") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = notificationBody,
            onValueChange = { notificationBody = it },
            label = { Text("Notification Body") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = analyticsLabel,
            onValueChange = { analyticsLabel = it },
            label = { Text("Analytics Label (for Reports)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(16.dp))

        // JSONペイロードプレビュー（fcm_optionsあり）
        Text(
            text = "Payload WITH fcm_options:",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.align(Alignment.Start)
        )
        Spacer(modifier = Modifier.height(4.dp))

        val horizontalScrollState1 = rememberScrollState()
        Text(
            text = jsonPayloadWithOptions,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp)
                )
                .padding(12.dp)
                .horizontalScroll(horizontalScrollState1)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = {
                isSending = true
                onSendNotification(notificationTitle, notificationBody, analyticsLabel)
                isSending = false
            },
            enabled = !isSending && accessToken.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isSending) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
            } else {
                Text("Send WITH fcm_options")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // JSONペイロードプレビュー（fcm_optionsなし）
        Text(
            text = "Payload WITHOUT fcm_options:",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.align(Alignment.Start)
        )
        Spacer(modifier = Modifier.height(4.dp))

        val horizontalScrollState2 = rememberScrollState()
        Text(
            text = jsonPayloadWithoutOptions,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp)
                )
                .padding(12.dp)
                .horizontalScroll(horizontalScrollState2)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = {
                isSending = true
                // analyticsLabelを空にして送信（fcm_optionsなし）
                onSendNotification(notificationTitle, notificationBody, "")
                isSending = false
            },
            enabled = !isSending && accessToken.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isSending) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
            } else {
                Text("Send WITHOUT fcm_options")
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
    analyticsLabel: String,
    includeFcmOptions: Boolean
): String {
    val tokenDisplay = if (token.length > 20) "${token.take(20)}..." else token

    return buildString {
        appendLine("{")
        appendLine("  \"message\": {")
        appendLine("    \"token\": \"$tokenDisplay\",")
        appendLine("    \"notification\": {")
        appendLine("      \"title\": \"$title\",")
        appendLine("      \"body\": \"$body\"")
        if (includeFcmOptions) {
            appendLine("    },")
            appendLine("    \"fcm_options\": {")
            appendLine("      \"analytics_label\": \"$analyticsLabel\"")
            appendLine("    }")
        } else {
            appendLine("    }")
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
            onSendNotification = { _, _, _ -> }
        )
    }
}
