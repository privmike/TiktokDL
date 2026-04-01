package com.privmike.tiktokdl

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity

class TiktokLoginActivity : ComponentActivity() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Create the WebView programmatically so we don't need an XML layout
        webView = WebView(this)
        setContentView(webView)

        setupCookies()
        setupWebView()

        // Send the user directly to the TikTok login page
        webView.loadUrl("https://www.tiktok.com/login")
    }

    private fun setupCookies() {
        // This links the cookies from this visible WebView to the global CookieManager
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)
    }

    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            // IMPORTANT: This must match the hidden WebView in MainActivity perfectly
//            userAgentString = "Mozilla/5.0 (Linux; Android 15; SM-S931B Build/AP3A.240905.015.A2; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/127.0.6533.103 Mobile Safari/537.36"
            userAgentString = "Mozilla/5.0 (Windows NT 11.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.6998.166 Safari/537.36"
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)

                // Force Android to save the current cookies to disk immediately
                CookieManager.getInstance().flush()

                // When login is successful, TikTok usually redirects to the home feed
                if (url != null && (url == "https://www.tiktok.com/" || url.contains("/foryou") || url.contains("/explore"))) {

                    // Double-check that the authentication cookie actually exists
                    val cookies = CookieManager.getInstance().getCookie("https://www.tiktok.com")
                    if (cookies != null && cookies.contains("sessionid")) {
                        Toast.makeText(this@TiktokLoginActivity, "Login Successful! Session saved.", Toast.LENGTH_SHORT).show()

                        // Close the login screen and return to MainActivity
                        finish()
                    }
                }
            }
        }
    }

    // This allows the user to use the phone's back button to navigate *within* the webpage
    // (e.g., backing out of an email login screen) instead of instantly closing the whole activity.

}