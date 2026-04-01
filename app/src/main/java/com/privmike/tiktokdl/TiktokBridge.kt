package com.privmike.tiktokdl

import android.util.Log
import android.webkit.JavascriptInterface

class TiktokBridge (private val onVideoFound : (String)-> Unit){

    @JavascriptInterface
    fun sendVideoData(videoUrl: String?){
        if (!videoUrl.isNullOrEmpty()){
            Log.d("TiktokExtractor", videoUrl)
            onVideoFound(videoUrl)
        }else{
            Log.d("TiktokExtractor", "Empty")
        }
    }

}
