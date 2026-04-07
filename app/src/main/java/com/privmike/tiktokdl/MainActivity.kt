package com.privmike.tiktokdl

import com.whispercpp.whisper.WhisperContext
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
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
import androidx.compose.foundation.background
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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.privmike.tiktokdl.data.AppDatabase
import com.privmike.tiktokdl.data.SavedVideo
import com.privmike.tiktokdl.data.VideoCollection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
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

    private var currentDownloadId: Long = -1L
    private var currentDownloadFileName: String = ""
    // Add this near your other state variables like `isExtracting`
    private var translationProgressText by mutableStateOf<String?>(null)
    // This keeps track of which video belongs to which download!
    private val pendingDownloads = mutableMapOf<Long, SavedVideo>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setupHiddenWebView()

        registerReceiver(
            downloadCompleteReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            Context.RECEIVER_EXPORTED // Needed for newer Android versions
        )
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

        lifecycleScope.launch(Dispatchers.IO) {
            // 1. Generate the filename and exact path FIRST
            val fileName = "TikTok_${System.currentTimeMillis()}.mp4"
            val expectedFile = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "TikTokDL/$fileName")

            // 2. Trigger the download, passing the filename
            val downloadId = triggerDownload(this@MainActivity, url, fileName)
            if (downloadId == -1L) return@launch // Crash safeguard

            // 3. Setup the Database
            val db = AppDatabase.getDatabase(this@MainActivity)
            db.collectionDao().insertCollection(VideoCollection(name = collectionName))
            val videoDao = db.videoDao()
            val currentPartNumber = videoDao.getMaxPartNumberForCollection(collectionName) + 1

            // 4. Create the Database Entity with the TRUE path
            val newVideo = SavedVideo(
                collectionName = collectionName,
                videoUrl = url,
                partNumber = currentPartNumber,
                localVideoPath = expectedFile.absolutePath // FIXED: Save the real path now!
            )

            // 5. Save to Room and grab the true ID
            val generatedID = videoDao.insertVideo(newVideo)
            val videoWithId = newVideo.copy(id = generatedID.toString().toInt())

            // 6. LINK THEM TOGETHER!
            pendingDownloads[downloadId] = videoWithId
        }
    }

    // FIXED: Added fileName as a parameter
    fun triggerDownload(context: Context, videoUrl: String, fileName: String): Long {
        try {
            val uri = Uri.parse(videoUrl)
            val request = DownloadManager.Request(uri)

            val cookies = CookieManager.getInstance().getCookie("https://www.tiktok.com")
            if (cookies != null) request.addRequestHeader("Cookie", cookies)

            request.addRequestHeader("User-Agent", DESKTOP_USER_AGENT)
            request.addRequestHeader("Referer", "https://www.tiktok.com/")

            // FIXED: Use the filename passed from the save function
            request.setDestinationInExternalFilesDir(context, Environment.DIRECTORY_MOVIES, "TikTokDL/$fileName")

            request.setTitle("Downloading Video")
            request.setDescription("Saving TikTok to your device...")
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val downloadID = downloadManager.enqueue(request)

            Log.d("TiktokExtractor", "Success! Download enqueued as: $fileName")
            return downloadID

        } catch (e: Exception) {
            Log.e("TiktokExtractor", "Download crashed: ${e.message}")
            return -1L
        }
    }
    private val downloadCompleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
                val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)

                // Did WE trigger this download?
                val pendingVideo = pendingDownloads[downloadId]
                if (pendingVideo != null) {
                    pendingDownloads.remove(downloadId) // Clear it from memory

                    val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    val query = DownloadManager.Query().setFilterById(downloadId)
                    val cursor = downloadManager.query(query)

                    if (cursor.moveToFirst()) {
                        val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        if (status == DownloadManager.STATUS_SUCCESSFUL) {

                            // 🚨 FIXED: Use the REAL path we saved earlier! No more content:// crashes!
                            val downloadedFile = File(pendingVideo.localVideoPath!!)

                            Toast.makeText(this@MainActivity, "Download Complete! Starting AI...", Toast.LENGTH_SHORT).show()

                            // FIRE THE AI PIPELINE!
                            lifecycleScope.launch {
                                val db = AppDatabase.getDatabase(this@MainActivity)
                                translateVideoOffline(this@MainActivity, pendingVideo, downloadedFile, db)
                            }
                        }
                    }
                    cursor.close()
                }
            }
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
            db.collectionDao().getAllCollections().collect { dbCollections ->
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
                                onClick = {
                                    saveVideoToCollection(extractedVideoUrl!!, collectionName)
                                    val homeIntent = Intent(this@MainActivity, MainActivity::class.java)
                                    homeIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                    startActivity(homeIntent)
                                }
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

                                val homeIntent = Intent(this@MainActivity, MainActivity::class.java)
                                homeIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                startActivity(homeIntent)
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

        var showCreateDialog by remember { mutableStateOf(false) }
        var newCollectionName by remember { mutableStateOf("") }
        val context = androidx.compose.ui.platform.LocalContext.current
        LaunchedEffect(Unit) {
            val db = AppDatabase.getDatabase(this@MainActivity)
            db.collectionDao().getAllCollections().collect { dbCollections ->
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
            },
            floatingActionButton = {
                FloatingActionButton(onClick = { showCreateDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = "New Collection")
                }
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
                                lifecycleScope.launch(Dispatchers.IO) {
                                    val db = AppDatabase.getDatabase(context)
                                    // Actually save it to the database!
                                    db.collectionDao().insertCollection(com.privmike.tiktokdl.data.VideoCollection(name = newCollectionName))
                                    showCreateDialog = false
                                    newCollectionName = "" // Reset for next time
                                }
                            }
                        }
                    ) { Text("Create") }
                },
                dismissButton = {
                    TextButton(onClick = { showCreateDialog = false }) { Text("Cancel") }
                }
            )
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun CollectionVideosScreen(collectionName: String, onBackClick: () -> Unit) {
        var videos by remember { mutableStateOf(emptyList<SavedVideo>()) }

        // NEW: This state tracks if we are currently watching a video!
        var selectedVideo by remember { mutableStateOf<SavedVideo?>(null) }

        LaunchedEffect(collectionName) {
            val db = AppDatabase.getDatabase(this@MainActivity)
            db.videoDao().getVideosForCollection(collectionName).collect { dbVideos ->
                videos = dbVideos
            }
        }

        // If the user clicked a video, show the full-screen player instead of the gallery
        if (selectedVideo != null) {
            VideoPlayerScreen(
                videoPath = selectedVideo!!.localVideoPath!!,
                subtitlePath = selectedVideo!!.srtFilePath, // Pass the subtitle path!
                onClose = { selectedVideo = null } // Go back to the gallery
            )
            return // Stop drawing the rest of the screen
        }

        // Otherwise, show the normal Gallery Grid
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
                                if (video.localVideoPath != null && File(video.localVideoPath!!).exists()) {
                                    // FIXED: Pass the whole video object so we have the subtitles too!
                                    selectedVideo = video
                                } else {
                                    Toast.makeText(this@MainActivity, "Video is still processing or file not found!", Toast.LENGTH_SHORT).show()
                                }
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



    // TRANSLATE VIDEO CODE
    fun decodeWaveFile(file: File): FloatArray {
        val bytes = file.readBytes()
        // A standard WAV header is 44 bytes. We skip it to get to the raw audio data.
        val headerSize = 44
        val payloadSize = bytes.size - headerSize
        val floatArray = FloatArray(payloadSize / 2) // 16-bit PCM = 2 bytes per float

        val byteBuffer = ByteBuffer.wrap(bytes, headerSize, payloadSize)
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN)

        for (i in floatArray.indices) {
            // Convert the 16-bit integer to a 32-bit float between -1.0 and 1.0
            floatArray[i] = byteBuffer.short / 32768.0f
        }
        return floatArray
    }

    fun extractAudioForWhisper(videoFile: File, onComplete: (File?) -> Unit) {
        // Create a temporary WAV file in the app's cache directory
        val wavFile = File(videoFile.parent, "${videoFile.nameWithoutExtension}.wav")

        // The exact command Whisper requires: 16kHz (-ar 16000), 1 Channel/Mono (-ac 1), 16-bit PCM
        val ffmpegCommand = "-i \"${videoFile.absolutePath}\" -ar 16000 -ac 1 -c:a pcm_s16le \"${wavFile.absolutePath}\""

        // Run FFmpeg in the background
        FFmpegKit.executeAsync(ffmpegCommand) { session ->
            val returnCode = session.returnCode

            if (ReturnCode.isSuccess(returnCode)) {
                Log.d("WhisperPipeline", "Audio extracted successfully: ${wavFile.name}")
                onComplete(wavFile)
            } else {
                Log.e("WhisperPipeline", "Audio extraction failed! Logs: ${session.failStackTrace}")
                onComplete(null)
            }
        }
    }

    fun copyModelToStorage(context: Context, modelName: String): File { //allows the model to be copied to the internal storage during runtime
        val modelFile = File(context.filesDir, modelName)
        // Only copy if it doesn't already exist to save time
        if (!modelFile.exists()) {
            context.assets.open(modelName).use { inputStream ->
                FileOutputStream(modelFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
        }
        return modelFile
    }

//    private val downloadReceiver = object : BroadcastReceiver() {
//        override fun onReceive(context: Context?, intent: Intent?) {
//            if (intent?.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
//                val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
//
//                // Look up the exact video entity based on the download ID!
//                val pendingVideo = pendingDownloads[downloadId]
//
//                if (pendingVideo != null && context != null) {
//                    pendingDownloads.remove(downloadId) // Clean up the map
//
//                    val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
//                    val query = DownloadManager.Query().setFilterById(downloadId)
//                    val cursor = downloadManager.query(query)
//
//                    if (cursor.moveToFirst()) {
//                        val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
//
//                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
//                            // Let Android tell us EXACTLY where it put the file
//                            val uriString = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
//                            val downloadedFile = File(Uri.parse(uriString).path!!)
//
//                            Toast.makeText(context, "Download Complete! Starting AI...", Toast.LENGTH_SHORT).show()
//
//                            // Launch the translation with ALL required parameters
//                            lifecycleScope.launch {
//                                val db = AppDatabase.getDatabase(context)
//                                translateVideoOffline(context, pendingVideo, downloadedFile, db)
//                            }
//                        } else {
//                            Log.e("WhisperPipeline", "Download failed with status code: $status")
//                        }
//                    }
//                    cursor.close()
//                }
//            }
//        }
//    }



    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(downloadCompleteReceiver)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancelAll()
    }

    private fun convertTextToBasicSrt(rawText: String): String {
        val srtBuilder = StringBuilder()
        // Split the giant text block into individual sentences
        val chunks = rawText.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
        var startTimeSeconds = 0

        chunks.forEachIndexed { index, text ->
            val endTimeSeconds = startTimeSeconds + 3 // Show each sentence for 3 seconds

            srtBuilder.append("${index + 1}\n")
            srtBuilder.append("${formatSrtTime(startTimeSeconds)} --> ${formatSrtTime(endTimeSeconds)}\n")
            srtBuilder.append("$text\n\n")

            // Move start time forward for the next sentence
            startTimeSeconds = endTimeSeconds
        }
        return srtBuilder.toString()
    }

    private fun formatSrtTime(totalSeconds: Int): String {
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        // SRT requires the format HH:MM:SS,mmm
        return String.format("%02d:%02d:%02d,000", hours, minutes, seconds)
    }
    suspend fun translateVideoOffline(
        context: Context,
        videoEntity: SavedVideo,
        mp4File: File,
        db: AppDatabase
    ) {
        withContext(Dispatchers.IO) {
            val dramaName = videoEntity.collectionName
            val partNumber = videoEntity.partNumber

            val displayTitle = "$dramaName - Part $partNumber"
            val safeFileName = "${dramaName.replace(Regex("[^A-Za-z0-9 ]"), "_")}_Part_${partNumber.toString().padStart(2)}"

            try {
                var activeVideoFile = mp4File
                val finalMp4File = File(mp4File.parent, "${safeFileName}.mp4")

                if (mp4File.exists() && mp4File.absolutePath != finalMp4File.absolutePath) {
                    mp4File.copyTo(finalMp4File, overwrite = true)
                    if (finalMp4File.exists()) {
                        mp4File.delete()
                        activeVideoFile = finalMp4File // Update our variable to use the new file!
                    }
                }

                // Save the MP4 path to the database right now, just in case AI crashes later!
                videoEntity.localVideoPath = activeVideoFile.absolutePath
                db.videoDao().updateVideo(videoEntity)

                updateProcessingNotification(context, displayTitle, "1/4: Extracting audio...")
                Log.d("WhisperPipeline", "1. Starting FFmpeg Audio Extraction...")
                val wavFile = File(context.cacheDir, "temp_audio.wav")
                if (wavFile.exists()) wavFile.delete()

                // 🚨 IMPORTANT: Use activeVideoFile here, NOT mp4File!
                val ffmpegCommand = "-i \"${activeVideoFile.absolutePath}\" -ar 16000 -ac 1 -c:a pcm_s16le \"${wavFile.absolutePath}\""
                com.arthenica.ffmpegkit.FFmpegKit.execute(ffmpegCommand)

                updateProcessingNotification(context, displayTitle, "2/4: Loading AI Model...")
                Log.d("WhisperPipeline", "2. Loading AI Model...")
                val modelFile = copyModelToStorage(context, "ggml-tiny.bin")
                val whisperContext = WhisperContext.createContextFromFile(modelFile.absolutePath)

                updateProcessingNotification(context, displayTitle, "3/4: Preparing audio data...")
                Log.d("WhisperPipeline", "3. Loading Audio into Memory...")
                val audioData = decodeWaveFile(wavFile)

                updateProcessingNotification(context, displayTitle, "4/4: AI is translating ")
                Log.d("WhisperPipeline", "4. AI Processing Audio...")

                // 🚨 IMPORTANT: Use transcribeDataToSrt!
                val raw = whisperContext.transcribeData(audioData)
                val srtContent = convertTextToBasicSrt(raw)


                val srtFile = File(activeVideoFile.parent, "${safeFileName}.srt")
                srtFile.writeText(srtContent)

                videoEntity.srtFilePath = srtFile.absolutePath
                db.videoDao().updateVideo(videoEntity)

                withContext(Dispatchers.Main) {
                    updateProcessingNotification(context, displayTitle, "Subtitle Complete", isComplete = true)
                }

                Log.d("WhisperPipeline", "SUCCESS! Output saved to:\n${srtFile.absolutePath}")

                whisperContext.release()
                wavFile.delete()

            } catch (e: CancellationException){
                withContext(Dispatchers.Main) {
                    updateProcessingNotification(context, displayTitle, "Translation Stopped", isComplete = true)
                }
                Log.e("WhisperPipeline", "Translation was cancelled (App Closed).")
                throw e
            }
            catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    updateProcessingNotification(context, displayTitle, "Error: AI Crashed", isComplete = true)
                }
                Log.e("WhisperPipeline", "Pipeline Crashed: ${e.message}")
            }
        }
    }

    private fun updateProcessingNotification(
        context: Context,
        videoTitle: String, // NEW: Pass the title in!
        stepText: String,
        isComplete: Boolean = false
    ) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "whisper_processing_channel"

        val channel = NotificationChannel(
            channelId,
            "AI Translation Progress",
            NotificationManager.IMPORTANCE_LOW
        )
        notificationManager.createNotificationChannel(channel)

        // Update the title to show the specific video!
        val title = if (isComplete) "Finished: $videoTitle" else "Translating: $videoTitle"

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle(title)
            .setContentText(stepText)
            .setOngoing(!isComplete)
            .setProgress(0, 0, !isComplete)

        notificationManager.notify(1, builder.build())
    }

    @Composable
    fun VideoPlayerScreen(videoPath: String, subtitlePath: String?, onClose: () -> Unit) {
        val context = androidx.compose.ui.platform.LocalContext.current

        // Initialize ExoPlayer
        val exoPlayer = remember {
            androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {

                // 1. Start building the media item with the Video File
                val mediaItemBuilder = androidx.media3.common.MediaItem.Builder()
                    .setUri(Uri.fromFile(File(videoPath)))

                // 2. If we have a subtitle file, attach it!
                if (subtitlePath != null && File(subtitlePath).exists()) {
                    val subtitleConfig = androidx.media3.common.MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(File(subtitlePath)))
                        .setMimeType(androidx.media3.common.MimeTypes.APPLICATION_SUBRIP) // Tells ExoPlayer it's an .srt file
                        .setLanguage("en")
                        .setSelectionFlags(androidx.media3.common.C.SELECTION_FLAG_DEFAULT)
                        .build()

                    mediaItemBuilder.setSubtitleConfigurations(listOf(subtitleConfig))
                }

                setMediaItem(mediaItemBuilder.build())

                // 3. Force the player to show English subtitles automatically
                trackSelectionParameters = trackSelectionParameters
                    .buildUpon()
                    .setPreferredTextLanguage("en")
                    .build()

                prepare()
                playWhenReady = true
            }
        }

        // Clean up the player to prevent memory leaks
        DisposableEffect(Unit) {
            onDispose {
                exoPlayer.release()
            }
        }

        Box(modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
            AndroidView(
                factory = { ctx ->
                    androidx.media3.ui.PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = true
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Back button
            IconButton(
                onClick = onClose,
                modifier = Modifier.padding(16.dp).align(Alignment.TopStart)
            ) {
                Icon(
                    Icons.Default.ArrowBack,
                    contentDescription = "Close Video",
                    tint = androidx.compose.ui.graphics.Color.White
                )
            }
        }
    }
}