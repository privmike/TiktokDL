package com.privmike.tiktokdl

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.privmike.tiktokdl.data.AppDatabase
import com.privmike.tiktokdl.data.SavedVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.regex.Pattern

class MainActivity : ComponentActivity() {

    private lateinit var hiddenWebView: WebView

    // UI State variables
    private var isExtracting by mutableStateOf(false)
    private var extractedVideoUrl by mutableStateOf<String?>(null)
    private var isLoggedIn by mutableStateOf(false)
    // Add this near your other state variables
    private var urlToSniff by mutableStateOf<String?>(null)
    // Define the User-Agent we'll use for both WebView and Downloading
    private val DESKTOP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setupHiddenWebView()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(modifier = Modifier.size(1.dp).alpha(0f)) {
                        AndroidView(
                            factory = { context ->
                                WebView(context).apply {
                                    settings.apply {
                                        javaScriptEnabled = true
                                        domStorageEnabled = true
                                        mediaPlaybackRequiresUserGesture = false
                                        userAgentString = DESKTOP_USER_AGENT
                                    }

                                    webViewClient = object : WebViewClient() {
                                        // Prevent TikTok from trying to open intent:// schemes which crash the WebView
                                        override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                                            val url = request?.url?.toString() ?: ""
                                            if (url.startsWith("http")) {
                                                return false // Let WebView load it
                                            }
                                            return true // Block weird app intents
                                        }

                                        override fun onPageFinished(view: WebView?, url: String?) {
                                            super.onPageFinished(view, url)

                                            if (url != null && url.contains("vt.tiktok.com")) {
                                                Log.d("TiktokExtractor", "Redirecting... waiting for final page.")
                                                return
                                            }

                                            Log.d("TiktokExtractor", "Final Page Loaded! Scraping JSON State...")

                                            // Updated JS: Actually parses the JSON and hunts specifically for "playAddr"
                                            val extractJsonJS = """
        javascript:(function() {
            try {
                var dataElement = document.getElementById('__UNIVERSAL_DATA_FOR_REHYDRATION__');
                if (!dataElement) dataElement = document.getElementById('SIGI_STATE');
                
                if (dataElement) {
                    var jsonData = JSON.parse(dataElement.textContent);
                    var jsonString = JSON.stringify(jsonData);
                    
                    // Look specifically for the playAddr or downloadAddr URLs
                    // This regex is much broader and catches the URLs exactly as TikTok formats them
                    var match = jsonString.match(/"playAddr":"([^"]+)"/);
                    if (!match) {
                        match = jsonString.match(/"downloadAddr":"([^"]+)"/);
                    }
                    
                    if (match && match.length > 1) {
                        // The URL will have escaped unicode like \u002F, so we decode it
                        var decodedUrl = match[1].replace(/\\u002F/g, '/').replace(/\\\//g, '/');
                        return decodedUrl;
                    } else {
                        return "REGEX_FAILED: Could not find playAddr in JSON";
                    }
                }
                return "ELEMENT_NOT_FOUND: Universal data block missing";
            } catch (e) {
                return "JS_ERROR: " + e.message;
            }
        })();
    """.trimIndent()

                                            view?.evaluateJavascript(extractJsonJS) { result ->
                                                // The result is usually wrapped in extra quotes by the evaluator
                                                val cleanResult = result?.replace("^\"|\"$".toRegex(), "") ?: "NULL_RESULT"

                                                if (cleanResult.startsWith("http")) {
                                                    Log.d("TiktokExtractor", "SUCCESS! Scraped JSON Video URL: $cleanResult")
                                                    lifecycleScope.launch(Dispatchers.Main) {
                                                        processExtractedURL(cleanResult)
                                                        urlToSniff = null // Reset state
                                                    }
                                                } else {
                                                    // WE NEED THIS LOG! This will tell us exactly why it's failing
                                                    Log.e("TiktokExtractor", "Scrape Failed! JS Returned: $cleanResult")
                                                }
                                            }

                                        }
                                    }
                                }
                            },
                            update = { view ->
                                urlToSniff?.let { url ->
                                    // 1. Safest way to force language: Inject cookies before loading!
                                    val cookieManager = CookieManager.getInstance()
                                    cookieManager.setCookie("https://tiktok.com", "store-language=en; store-country=us;")
                                    cookieManager.setCookie("https://www.tiktok.com", "store-language=en; store-country=us;")

                                    // 2. Just load the raw URL. Don't add ?lang=en, it breaks vt.tiktok shortlinks.
                                    val extraHeaders = mapOf("Accept-Language" to "en-US,en;q=0.9")
                                    view.loadUrl(url, extraHeaders)
                                }
                            }
                        )
                    }
                    if (intent?.action == Intent.ACTION_SEND) {
                        ShareInterceptScreen()
                    } else {
                        var viewedCollection by remember { mutableStateOf<String?>(null) }

                        if (viewedCollection == null) {
                            MainGalleryScreen(
                                onCollectionClick = { clickedName -> viewedCollection = clickedName }
                            )
                        } else {
                            CollectionVideosScreen(
                                collectionName = viewedCollection!!,
                                onBackClick = { viewedCollection = null }
                            )
                        }
                    }
                }
            }
        }

        val currentIntent = intent
        if (currentIntent?.action == Intent.ACTION_SEND && currentIntent.type == "text/plain")  {
            isExtracting = true
            handleShareText(currentIntent)
        }
    }

    override fun onResume() {
        super.onResume()
        isLoggedIn = checkTiktokLoginStatus()
    }

    private fun checkTiktokLoginStatus(): Boolean {
        val cookieManager = CookieManager.getInstance()
        val cookies = cookieManager.getCookie("https://www.tiktok.com")
        return cookies?.contains("sessionid") == true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain")  {
            isExtracting = true
            handleShareText(intent)
        }
    }

    private fun handleShareText(intent: Intent) {
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        Log.d("TiktokExtractor","Received: $sharedText")

        val urlPattern = Pattern.compile("(https?://\\S+)")
        val matcher  = urlPattern.matcher(sharedText)

        if (matcher.find()){
            val tiktokURL = matcher.group(1)
            Log.d("TiktokExtractor","Extracted URL : $tiktokURL")
            // Trigger the Compose WebView to load!
            urlToSniff = tiktokURL
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupHiddenWebView() {
        hiddenWebView = WebView(this)

        // Android 12+ Fix: Give it a real size so it doesn't throttle background JS,
        // but shove it 10,000 pixels off-screen so the user never sees it.
        val params = ViewGroup.LayoutParams(800, 800)
        hiddenWebView.layoutParams = params
        hiddenWebView.translationX = -10000f

        hiddenWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            userAgentString = DESKTOP_USER_AGENT
        }

        hiddenWebView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)

                // 1. Guard against shortlink redirects (e.g. vt.tiktok.com -> www.tiktok.com)
                if (url != null && url.contains("vt.tiktok.com")) {
                    Log.d("TiktokExtractor", "Redirecting... waiting for final page.")
                    return
                }

                Log.d("TiktokExtractor", "Final Page Loaded! Scraping JSON State...")

                // 2. The Chrome Extension Strategy: Extract the universal data JSON
                val extractJsonJS = """
                    javascript:(function() {
                        try {
                            var dataElement = document.getElementById('__UNIVERSAL_DATA_FOR_REHYDRATION__');
                            if (!dataElement) dataElement = document.getElementById('SIGI_STATE');
                            
                            if (dataElement) {
                                var rawString = dataElement.textContent;
                                
                                // Regex to find raw video URLs embedded in the JSON
                                var mp4Links = rawString.match(/https:\/\/[^"'\\]*(?:tiktokcdn\.com|tiktokv\.com)[^"'\\]*(?:mime_type=video_mp4|video\/tos)[^"'\\]*/g);
                                
                                if (mp4Links && mp4Links.length > 0) {
                                    // Fix escaped forward slashes (\u002F or \/)
                                    return mp4Links[0].replace(/\\u002F/g, '/').replace(/\\\//g, '/');
                                }
                            }
                            return "NOT_FOUND";
                        } catch (e) {
                            return "ERROR: " + e.message;
                        }
                    })();
                """.trimIndent()

                view?.evaluateJavascript(extractJsonJS) { result ->
                    // result comes back wrapped in quotes (e.g., "https://...")
                    val cleanResult = result?.replace("^\"|\"$".toRegex(), "")

                    if (cleanResult != null && cleanResult.startsWith("http")) {
                        Log.d("TiktokExtractor", "SUCCESS! Scraped JSON Video URL: $cleanResult")

                        lifecycleScope.launch(Dispatchers.Main) {
                            processExtractedURL(cleanResult)
                        }
                    } else {
                        Log.w("TiktokExtractor", "Failed to scrape JSON. Result: $cleanResult")
                        // Optional: Could reload or try a fallback API call here
                    }
                }
            }
        }
    }

    private fun processExtractedURL(extractedURL: String) {
        if (extractedURL.startsWith("http")){
            isExtracting = false
            extractedVideoUrl = extractedURL
        } else {
            Log.e("TiktokExtractor","Invalid URL failed: $extractedURL")
            isExtracting = false
            extractedVideoUrl = null
        }
    }

    private fun saveVideoToCollection(url: String, collectionName: String) {
        Toast.makeText(this, "Downloading to $collectionName...", Toast.LENGTH_SHORT).show()

        // Trigger the download immediately
        triggerDownload(this, url)

        // Run the database save on a background thread so we don't freeze the UI
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(this@MainActivity)
            db.videoDao().insertVideo(SavedVideo(videoUrl = url, collectionName = collectionName))

            // Switch back to the Main thread to close the app UI after a short delay
            withContext(Dispatchers.Main) {
                delay(500)
                finish()
            }
        }
    }

    fun triggerDownload(context: Context, videoUrl: String) {
        try {
            val uri = Uri.parse(videoUrl)
            val request = DownloadManager.Request(uri)

            // Inject Cookies from the WebView so TikTok doesn't give a 403 error
            val cookies = CookieManager.getInstance().getCookie("https://www.tiktok.com")
            if (cookies != null) {
                request.addRequestHeader("Cookie", cookies)
            }

// Spoof the exact User-Agent we used to scrape the data
            request.addRequestHeader("User-Agent", DESKTOP_USER_AGENT)
            request.addRequestHeader("Referer", "https://www.tiktok.com/")

            val fileName = "TikTok_${System.currentTimeMillis()}.mp4"
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_MOVIES, "TikTokDL/$fileName")

            request.setTitle("Downloading Video")
            request.setDescription("Saving TikTok to your device...")
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadManager.enqueue(request)

            Log.d("TiktokExtractor", "Success! Download enqueued as: $fileName")

        } catch (e: Exception) {
            Log.e("TiktokExtractor", "Download crashed: ${e.message}")
        }
    }

    // --- COMPOSE UI BLOCKS BELOW ---

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun ShareInterceptScreen() {
        var showCreateDialog by remember { mutableStateOf(false) }
        var newCollectionName by remember { mutableStateOf("") }
        var collections by remember { mutableStateOf(listOf("My Favorites")) }

        LaunchedEffect(Unit) {
            val db = AppDatabase.getDatabase(this@MainActivity)
            db.videoDao().getAllCollections().collect { dbCollections ->
                if (dbCollections.isNotEmpty()) {
                    collections = dbCollections
                }
            }
        }

        ModalBottomSheet(
            onDismissRequest = { finish() },
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (isExtracting) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Sniffing video from TikTok...", style = MaterialTheme.typography.bodyLarge)
                    Spacer(modifier = Modifier.height(32.dp))
                } else if (extractedVideoUrl != null) {
                    Text("Video Ready!", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Where would you like to save it?", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(16.dp))

                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(collections) { collectionName ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                onClick = { saveVideoToCollection(extractedVideoUrl!!, collectionName) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Folder, contentDescription = "Folder", tint = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Text(collectionName, style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }

                        item {
                            OutlinedButton(
                                onClick = { showCreateDialog = true },
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "Add")
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Create New Collection")
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(32.dp))
                } else {
                    Text("Failed to extract video.", color = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }

        if (showCreateDialog) {
            AlertDialog(
                onDismissRequest = { showCreateDialog = false },
                title = { Text("New Collection") },
                text = {
                    OutlinedTextField(
                        value = newCollectionName,
                        onValueChange = { newCollectionName = it },
                        label = { Text("Collection Name") },
                        singleLine = true
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (newCollectionName.isNotBlank()) {
                                saveVideoToCollection(extractedVideoUrl!!, newCollectionName)
                                showCreateDialog = false
                            }
                        }
                    ) { Text("Save Here") }
                },
                dismissButton = {
                    TextButton(onClick = { showCreateDialog = false }) { Text("Cancel") }
                }
            )
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MainGalleryScreen(onCollectionClick: (String) -> Unit) {
        var collections by remember { mutableStateOf(listOf("My Favorites")) }

        LaunchedEffect(Unit) {
            val db = AppDatabase.getDatabase(this@MainActivity)
            db.videoDao().getAllCollections().collect { dbCollections ->
                if (dbCollections.isNotEmpty()) {
                    collections = dbCollections
                }
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("My Collections") },
                    actions = {
                        if (isLoggedIn) {
                            TextButton(onClick = { }, enabled = false) {
                                Text("Linked ✓", color = MaterialTheme.colorScheme.primary)
                            }
                        } else {
                            TextButton(onClick = {
                                startActivity(Intent(this@MainActivity, TiktokLoginActivity::class.java))
                            }) {
                                Text("Login TikTok", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }
        ) { padding ->
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(collections) { collection ->
                    Card(
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        onClick = { onCollectionClick(collection) }
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.Folder, contentDescription = "Folder", modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(collection, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun CollectionVideosScreen(collectionName: String, onBackClick: () -> Unit) {
        var videos by remember { mutableStateOf(emptyList<SavedVideo>()) }

        LaunchedEffect(collectionName) {
            val db = AppDatabase.getDatabase(this@MainActivity)
            db.videoDao().getVideosForCollection(collectionName).collect { dbVideos ->
                videos = dbVideos
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(collectionName) },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }
        ) { padding ->
            if (videos.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text("No videos in this collection yet.")
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.padding(padding).fillMaxSize().padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(videos) { video ->
                        Card(
                            modifier = Modifier.fillMaxWidth().aspectRatio(0.56f),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                            onClick = {
                                Toast.makeText(this@MainActivity, "Clicked video ID: ${video.id}", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "Play", modifier = Modifier.size(48.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}