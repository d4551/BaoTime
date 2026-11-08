package com.baotime.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.baotime.app.R
import com.baotime.app.routing.UrlRouter

/** Owns permissions and navigation for exactly one room. Apple owns its media controls. */
class FaceTimeWebViewHelper(
    private val webView: WebView,
    private val onStatusChanged: (Int) -> Unit,
    private val onMediaWaiting: (Boolean) -> Unit
) {
    private var pendingMedia: PermissionRequest? = null
    private var disposed = false
    private var mainFrameFailed = false
    val hasPendingMedia: Boolean get() = pendingMedia != null

    init {
        webView.settings.apply {
            javaScriptEnabled = false
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
            allowContentAccess = false
            allowFileAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                denyHeldMedia()
                if (disposed || request.origin.scheme != "https" ||
                    !UrlRouter.isAppleMediaHost(request.origin.host)
                ) {
                    request.deny()
                    return
                }
                val resources = mediaResources(request)
                if (resources.isEmpty()) {
                    request.deny()
                } else if (resources.all(::hasPermission)) {
                    request.grant(resources.toTypedArray())
                    onStatusChanged(R.string.call_status_media_granted)
                } else {
                    pendingMedia = request
                    onMediaWaiting(true)
                    onStatusChanged(R.string.call_status_media_waiting)
                }
            }

            override fun onPermissionRequestCanceled(request: PermissionRequest) {
                if (pendingMedia === request) {
                    pendingMedia = null
                    onMediaWaiting(false)
                }
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (disposed) return true
                val permitted = if (request.isForMainFrame) {
                    UrlRouter.isFaceTimeRoomUrl(request.url.toString())
                } else {
                    request.url.scheme == "https" && UrlRouter.isAppleMediaHost(request.url.host)
                }
                if (!permitted && request.isForMainFrame) onStatusChanged(R.string.call_status_navigation_blocked)
                return !permitted
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (disposed) return
                denyHeldMedia()
                view.settings.javaScriptEnabled = UrlRouter.isFaceTimeRoomUrl(url.orEmpty())
                if (!view.settings.javaScriptEnabled) {
                    view.stopLoading()
                    mainFrameFailed = true
                    onStatusChanged(R.string.call_status_navigation_blocked)
                    return
                }
                mainFrameFailed = false
                onStatusChanged(R.string.call_status_connecting)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (!disposed && !mainFrameFailed) onStatusChanged(R.string.call_status_page_ready)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!disposed && request.isForMainFrame) pageFailed()
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (!disposed && request.isForMainFrame) pageFailed()
            }
        }
    }

    fun loadRoom(url: String): Boolean {
        if (disposed) return false
        denyHeldMedia()
        webView.settings.javaScriptEnabled = UrlRouter.isFaceTimeRoomUrl(url)
        if (!webView.settings.javaScriptEnabled) {
            webView.stopLoading()
            mainFrameFailed = true
            onStatusChanged(R.string.call_status_navigation_blocked)
            return false
        }
        webView.loadUrl(url)
        return true
    }

    fun pendingAndroidPermissions(): Array<String> = pendingMedia?.let { request ->
        mediaResources(request).map(::androidPermission).distinct().toTypedArray()
    } ?: emptyArray()

    fun grantHeldMedia(): Boolean {
        val request = pendingMedia ?: return false
        val allowed = mediaResources(request)
        if (disposed || !allowed.all(::hasPermission)) return false
        pendingMedia = null
        onMediaWaiting(false)
        request.grant(allowed.toTypedArray())
        onStatusChanged(R.string.call_status_media_granted)
        return true
    }

    fun denyHeldMedia() {
        val request = pendingMedia ?: return
        pendingMedia = null
        request.deny()
        onMediaWaiting(false)
    }

    fun dispose() {
        denyHeldMedia()
        disposed = true
        webView.settings.javaScriptEnabled = false
        webView.stopLoading()
        webView.webChromeClient = null
        webView.webViewClient = WebViewClient()
        webView.removeAllViews()
        webView.destroy()
    }

    private fun pageFailed() {
        mainFrameFailed = true
        denyHeldMedia()
        onStatusChanged(R.string.call_status_failed)
    }

    private fun mediaResources(request: PermissionRequest): List<String> = request.resources.filter {
        it == PermissionRequest.RESOURCE_VIDEO_CAPTURE || it == PermissionRequest.RESOURCE_AUDIO_CAPTURE
    }

    private fun hasPermission(resource: String): Boolean {
        return webView.context.checkSelfPermission(androidPermission(resource)) == PackageManager.PERMISSION_GRANTED
    }

    private fun androidPermission(resource: String): String =
        if (resource == PermissionRequest.RESOURCE_VIDEO_CAPTURE) Manifest.permission.CAMERA else Manifest.permission.RECORD_AUDIO
}
