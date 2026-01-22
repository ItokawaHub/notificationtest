package com.example.notification_test

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec

/**
 * サービスアカウントを使用してFCM用のアクセストークンを取得するヘルパー
 */
object FcmAuthHelper {

    private const val TAG = "FcmAuthHelper"
    private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    private const val FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
    private const val SERVICE_ACCOUNT_FILE = "service-account.json"

    /**
     * サービスアカウントJSONからアクセストークンを取得
     */
    suspend fun getAccessToken(context: Context): Result<String> = withContext(Dispatchers.IO) {
        try {
            // サービスアカウントJSONを読み込む
            val serviceAccountJson = loadServiceAccountJson(context)
                ?: return@withContext Result.failure(Exception("service-account.json が assets フォルダに見つかりません"))

            val clientEmail = serviceAccountJson.getString("client_email")
            val privateKeyPem = serviceAccountJson.getString("private_key")

            // JWTを生成
            val jwt = createJwt(clientEmail, privateKeyPem)

            // アクセストークンを取得
            val accessToken = exchangeJwtForAccessToken(jwt)

            Result.success(accessToken)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get access token", e)
            Result.failure(e)
        }
    }

    /**
     * サービスアカウントJSONがあるかチェック
     */
    fun hasServiceAccount(context: Context): Boolean {
        return try {
            context.assets.open(SERVICE_ACCOUNT_FILE).use { true }
        } catch (e: Exception) {
            false
        }
    }

    private fun loadServiceAccountJson(context: Context): JSONObject? {
        return try {
            context.assets.open(SERVICE_ACCOUNT_FILE).use { inputStream ->
                val json = inputStream.bufferedReader().readText()
                JSONObject(json)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load service account JSON", e)
            null
        }
    }

    private fun createJwt(clientEmail: String, privateKeyPem: String): String {
        val now = System.currentTimeMillis() / 1000
        val expiry = now + 3600 // 1時間後

        // JWTヘッダー
        val headerJson = """{"alg":"RS256","typ":"JWT"}"""

        // JWTペイロード
        val payloadJson = """{"iss":"$clientEmail","sub":"$clientEmail","aud":"$TOKEN_URL","iat":$now,"exp":$expiry,"scope":"$FCM_SCOPE"}"""

        val headerBase64 = base64UrlEncode(headerJson.toByteArray(Charsets.UTF_8))
        val payloadBase64 = base64UrlEncode(payloadJson.toByteArray(Charsets.UTF_8))
        val signatureInput = "$headerBase64.$payloadBase64"

        // RSA署名
        val signature = signWithRsa(signatureInput, privateKeyPem)
        val signatureBase64 = base64UrlEncode(signature)

        return "$headerBase64.$payloadBase64.$signatureBase64"
    }

    private fun signWithRsa(data: String, privateKeyPem: String): ByteArray {
        // PEM形式の秘密鍵をパース
        val privateKeyContent = privateKeyPem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\\n", "")
            .replace("\n", "")
            .replace("\r", "")
            .replace(" ", "")
            .trim()

        val keyBytes = Base64.decode(privateKeyContent, Base64.DEFAULT)
        val keySpec = PKCS8EncodedKeySpec(keyBytes)
        val keyFactory = KeyFactory.getInstance("RSA")
        val privateKey = keyFactory.generatePrivate(keySpec)

        // SHA256withRSAで署名
        val signature = Signature.getInstance("SHA256withRSA")
        signature.initSign(privateKey)
        signature.update(data.toByteArray(Charsets.UTF_8))
        return signature.sign()
    }

    private suspend fun exchangeJwtForAccessToken(jwt: String): String = withContext(Dispatchers.IO) {
        val client = OkHttpClient()

        val formBody = FormBody.Builder()
            .add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
            .add("assertion", jwt)
            .build()

        val request = Request.Builder()
            .url(TOKEN_URL)
            .post(formBody)
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string()
            ?: throw Exception("Empty response from OAuth server")

        if (!response.isSuccessful) {
            throw Exception("OAuth error: ${response.code} - $responseBody")
        }

        val jsonResponse = JSONObject(responseBody)
        jsonResponse.getString("access_token")
    }

    private fun base64UrlEncode(data: ByteArray): String {
        return Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}
