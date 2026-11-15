package com.baotime.app.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.net.toUri
import com.baotime.app.R
import com.baotime.app.routing.UrlRouter
import org.json.JSONObject

/** Owns permissions and navigation for exactly one room. Apple owns its media controls. */
class FaceTimeWebViewHelper @JvmOverloads constructor(
    private val webView: WebView,
    private val onStatusChanged: (Int) -> Unit,
    private val onMediaWaiting: (Boolean) -> Unit,
    private val onRendererGone: ((WebView) -> Unit)? = null
) {
    private var pendingMedia: PermissionRequest? = null
    private var disposed = false
    private var rendererGone = false
    private var destroyed = false
    private var mainFrameFailed = false
    private var mediaStatus: Int? = null
    private var pageStatus = R.string.call_status_connecting
    private var pageGeneration = 0L
    private var errorDocumentUrl: String? = null
    private val errorAccessibilityScript by lazy {
        webView.context.assets.open(ERROR_ACCESSIBILITY_ASSET).bufferedReader().use { it.readText() }
    }
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
                if (rendererGone) return
                if (disposed || mainFrameFailed || !UrlRouter.isAppleMediaUrl(request.origin.toString())) {
                    request.deny()
                    return
                }
                val resources = mediaResources(request)
                if (resources.isEmpty()) {
                    request.deny()
                    return
                }
                denyHeldMedia()
                if (resources.all(::hasPermission)) {
                    request.grant(resources.toTypedArray())
                    publishMediaStatus(R.string.call_status_media_granted)
                } else {
                    pendingMedia = request
                    onMediaWaiting(true)
                    publishMediaStatus(R.string.call_status_media_waiting)
                }
            }

            override fun onPermissionRequestCanceled(request: PermissionRequest) {
                if (pendingMedia === request) {
                    pendingMedia = null
                    onMediaWaiting(false)
                    mediaStatus = null
                    onStatusChanged(pageStatus)
                }
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (!disposed && !rendererGone) {
                    rendererGone = true
                    disposed = true
                    pendingMedia = null
                    onMediaWaiting(false)
                    if (onRendererGone != null) {
                        onRendererGone(view)
                    }
                    dispose()
                }
                return true
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (disposed) return true
                if (openErrorFooterLink(request)) return true
                val permitted = if (request.isForMainFrame) {
                    UrlRouter.isFaceTimeRoomUrl(request.url.toString())
                } else {
                    UrlRouter.isAppleMediaUrl(request.url.toString())
                }
                if (!permitted && request.isForMainFrame) {
                    failMainFrame()
                    view.stopLoading()
                    publishPageStatus(R.string.call_status_navigation_blocked)
                }
                return !permitted
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (disposed) return
                resetPageAccessibility()
                denyHeldMedia()
                view.settings.javaScriptEnabled = UrlRouter.isFaceTimeRoomUrl(url.orEmpty())
                if (!view.settings.javaScriptEnabled) {
                    view.stopLoading()
                    mainFrameFailed = true
                    publishPageStatus(R.string.call_status_navigation_blocked)
                    return
                }
                mainFrameFailed = false
                publishPageStatus(R.string.call_status_connecting)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (url == null || !isCurrentRoomPage(url, pageGeneration)) return
                publishPageStatus(R.string.call_status_page_ready)
                if (url.toUri().path == JOIN_PATH) updateErrorAccessibility(url, pageGeneration)
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
        resetPageAccessibility()
        denyHeldMedia()
        webView.settings.javaScriptEnabled = UrlRouter.isFaceTimeRoomUrl(url)
        if (!webView.settings.javaScriptEnabled) {
            webView.stopLoading()
            mainFrameFailed = true
            publishPageStatus(R.string.call_status_navigation_blocked)
            return false
        }
        webView.loadUrl(url)
        return true
    }

    fun pendingAndroidPermissions(): Array<String> = pendingMedia?.let { request ->
        mediaResources(request).map(::androidPermission).distinct().toTypedArray()
    } ?: emptyArray()

    internal fun captureMediaConsent(): MediaConsent? = pendingMedia?.let(::MediaConsent)

    internal inner class MediaConsent(private val request: PermissionRequest) {
        val missingPermissions: List<String> = mediaResources(request).filterNot(::hasPermission)
            .map(::androidPermission).distinct()

        fun complete(allowed: Boolean): Boolean {
            if (pendingMedia !== request) return false
            if (allowed && grantHeldMedia()) return true
            denyHeldMedia()
            publishMediaStatus(R.string.call_status_media_denied)
            return false
        }
    }

    fun grantHeldMedia(): Boolean {
        val request = pendingMedia ?: return false
        val allowed = mediaResources(request)
        if (disposed || mainFrameFailed || allowed.isEmpty() || !allowed.all(::hasPermission)) return false
        pendingMedia = null
        onMediaWaiting(false)
        request.grant(allowed.toTypedArray())
        publishMediaStatus(R.string.call_status_media_granted)
        return true
    }

    fun denyHeldMedia() {
        val request = pendingMedia ?: return
        pendingMedia = null
        request.deny()
        onMediaWaiting(false)
    }

    private fun publishMediaStatus(status: Int) {
        mediaStatus = status
        onStatusChanged(status)
    }

    private fun publishPageStatus(status: Int) {
        pageStatus = status
        if (mediaStatus == null) onStatusChanged(status)
    }

    fun reloadRoom() {
        if (!disposed) webView.reload()
    }

    fun dispose() {
        if (destroyed) return
        destroyed = true
        if (!rendererGone) {
            denyHeldMedia()
            disposed = true
            resetPageAccessibility()
            webView.settings.javaScriptEnabled = false
            webView.stopLoading()
            webView.webChromeClient = null
            webView.webViewClient = WebViewClient()
            webView.removeAllViews()
        }
        val parent = webView.parent
        if (parent is ViewGroup) parent.removeView(webView)
        webView.destroy()
    }

    private fun pageFailed() {
        failMainFrame()
        publishPageStatus(R.string.call_status_failed)
    }

    /** Shared main-frame failure core: reset accessibility, mark failed, deny held media, disable JS. */
    private fun failMainFrame() {
        resetPageAccessibility()
        mainFrameFailed = true
        denyHeldMedia()
        webView.settings.javaScriptEnabled = false
    }

    private fun isCurrentRoomPage(url: String, generation: Long): Boolean =
        !disposed && !mainFrameFailed && generation == pageGeneration && webView.url == url &&
            webView.settings.javaScriptEnabled && UrlRouter.isFaceTimeRoomUrl(url)

    private fun updateErrorAccessibility(url: String, generation: Long, attempt: Int = 1) {
        if (!isCurrentRoomPage(url, generation)) return
        val context = webView.context
        val density = context.resources.displayMetrics.density
        val targetSize = context.resources.getDimension(R.dimen.min_touch_target) / density
        val spacing = context.resources.getDimension(R.dimen.space_sm) / density
        val foreground = Integer.toHexString(context.getColor(R.color.color_call_text) and RGB_MASK).padStart(6, '0')
        val background = Integer.toHexString(context.getColor(R.color.color_call_chrome) and RGB_MASK).padStart(6, '0')
        val origin = JSONObject.quote("https://${UrlRouter.FACETIME_HOST}")
        val script = "$errorAccessibilityScript($origin, ${JSONObject.quote(url)}, \"#$foreground\", \"#$background\", $targetSize, $spacing)"
        webView.evaluateJavascript(script) { applied ->
            if (!isCurrentRoomPage(url, generation)) return@evaluateJavascript
            if (applied == "true") {
                errorDocumentUrl = url
                webView.settings.apply {
                    useWideViewPort = true
                    builtInZoomControls = true
                    displayZoomControls = false
                }
                publishPageStatus(R.string.call_status_room_unavailable)
            } else if (attempt < ERROR_RENDER_ATTEMPTS) {
                webView.postDelayed({ updateErrorAccessibility(url, generation, attempt + 1) }, ERROR_RENDER_INTERVAL_MS)
            }
        }
    }

    private fun resetPageAccessibility() {
        pageGeneration++
        errorDocumentUrl = null
        mediaStatus = null
        webView.settings.apply {
            useWideViewPort = false
            builtInZoomControls = false
        }
    }

    private fun openErrorFooterLink(request: WebResourceRequest): Boolean {
        val errorPage = errorDocumentUrl ?: return false
        if (!isCurrentRoomPage(errorPage, pageGeneration) || !request.isForMainFrame ||
            request.isRedirect || !request.hasGesture() || request.method != "GET" ||
            request.url.toString() !in ERROR_FOOTER_LINKS) return false

        val context = webView.context
        val intent = Intent(Intent.ACTION_VIEW, request.url).addCategory(Intent.CATEGORY_BROWSABLE)
        runCatching { context.startActivity(intent) }.onFailure { outcome ->
            if (outcome !is ActivityNotFoundException) throw outcome
            Toast.makeText(context, R.string.call_status_browser_unavailable, Toast.LENGTH_LONG).show()
        }
        return true
    }

    private fun mediaResources(request: PermissionRequest): List<String> = request.resources.filter {
        it == PermissionRequest.RESOURCE_VIDEO_CAPTURE || it == PermissionRequest.RESOURCE_AUDIO_CAPTURE
    }

    private fun hasPermission(resource: String): Boolean {
        return webView.context.checkSelfPermission(androidPermission(resource)) == PackageManager.PERMISSION_GRANTED
    }

    private fun androidPermission(resource: String): String =
        if (resource == PermissionRequest.RESOURCE_VIDEO_CAPTURE) Manifest.permission.CAMERA else Manifest.permission.RECORD_AUDIO

    private companion object {
        const val JOIN_PATH = "/join"
        const val ERROR_ACCESSIBILITY_ASSET = "facetime-error-accessibility.js"
        const val ERROR_RENDER_ATTEMPTS = 24
        const val ERROR_RENDER_INTERVAL_MS = 250L
        const val RGB_MASK = 0x00FF_FFFF
        val ERROR_FOOTER_LINKS = setOf(
            "https://www.apple.com/support/systemstatus/",
            "https://www.apple.com/privacy/",
            "https://www.apple.com/legal/internet-services/terms/site.html"
        )
    }
}
