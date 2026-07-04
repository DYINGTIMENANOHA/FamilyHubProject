package code.name.monkey.retromusic.activities

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.SyncTuneApi
import code.name.monkey.retromusic.SyncTuneSession
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class CinemaActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "CinemaActivity"
        private const val EXTRA_ADMIN = "extra_admin"

        fun intent(context: Context, admin: Boolean = false): Intent =
            Intent(context, CinemaActivity::class.java).putExtra(EXTRA_ADMIN, admin)

        private const val MOBILE_CINEMA_SCRIPT = """
            (function() {
              var viewport = document.querySelector('meta[name="viewport"]');
              if (!viewport) {
                viewport = document.createElement('meta');
                viewport.name = 'viewport';
                document.head.appendChild(viewport);
              }
              viewport.content = 'width=device-width, initial-scale=1, viewport-fit=cover';

              var oldStyle = document.getElementById('familyhub-cinema-mobile-style');
              if (oldStyle) oldStyle.remove();
              var style = document.createElement('style');
              style.id = 'familyhub-cinema-mobile-style';
              style.textContent = `
                html, body {
                  width: 100% !important;
                  min-height: 100% !important;
                  height: auto !important;
                  overflow-x: hidden !important;
                  overflow-y: auto !important;
                  -webkit-overflow-scrolling: touch !important;
                  overscroll-behavior-y: contain !important;
                  touch-action: pan-x pan-y manipulation !important;
                  background: #000 !important;
                }
                body {
                  padding-bottom: max(24px, env(safe-area-inset-bottom)) !important;
                }
                .library-main,
                .watch-main {
                  width: 100% !important;
                  max-width: 100% !important;
                  padding-left: 12px !important;
                  padding-right: 12px !important;
                }
                .topbar {
                  gap: 8px !important;
                  padding: 10px 12px !important;
                  overflow-x: auto !important;
                  -webkit-overflow-scrolling: touch !important;
                }
                .topbar-left,
                .topbar-right {
                  min-width: 0 !important;
                  gap: 6px !important;
                }
                .topbar-btn {
                  flex: 0 0 auto !important;
                  padding: 6px 10px !important;
                  font-size: 12px !important;
                  white-space: nowrap !important;
                }
                .player-wrapper {
                  border-radius: 0 !important;
                  border-left: none !important;
                  border-right: none !important;
                }
                .familyhub-mobile-watch-banner {
                  display: flex !important;
                  align-items: center !important;
                  justify-content: space-between !important;
                  gap: 8px !important;
                  width: 100% !important;
                  margin: 8px 0 10px !important;
                  padding: 8px 10px !important;
                  border: 1px solid rgba(255,255,255,0.16) !important;
                  border-radius: 8px !important;
                  background: rgba(18,18,18,0.94) !important;
                  color: #fff !important;
                  font-size: 13px !important;
                  position: relative !important;
                  z-index: 30 !important;
                }
                .familyhub-mobile-watch-actions {
                  display: flex !important;
                  align-items: center !important;
                  justify-content: flex-end !important;
                  gap: 8px !important;
                  flex: 0 0 auto !important;
                }
                .familyhub-mobile-watch-banner button {
                  flex: 0 0 auto !important;
                  min-width: 88px !important;
                  min-height: 36px !important;
                  border-radius: 7px !important;
                  border: 1px solid rgba(255,255,255,0.22) !important;
                  background: #2d6cdf !important;
                  color: #fff !important;
                  padding: 6px 10px !important;
                  font-size: 14px !important;
                }
                .player-controls {
                  padding: 34px 8px 8px !important;
                }
                .control-row {
                  gap: 6px !important;
                  overflow-x: auto !important;
                  -webkit-overflow-scrolling: touch !important;
                  flex-wrap: nowrap !important;
                  padding-bottom: 4px !important;
                  touch-action: pan-x !important;
                }
                .progress-slider,
                .volume-slider {
                  touch-action: none !important;
                }
                .progress-row {
                  padding: 10px 0 !important;
                }
                .video-picker-body {
                  overflow-y: auto !important;
                  -webkit-overflow-scrolling: touch !important;
                  touch-action: pan-y !important;
                  overscroll-behavior: contain !important;
                }
                .sidebar-body,
                .chat-sidebar #chat-body {
                  -webkit-overflow-scrolling: touch !important;
                  touch-action: pan-y !important;
                  overscroll-behavior: contain !important;
                }
                .ctrl-btn {
                  flex: 0 0 auto !important;
                  min-width: 40px !important;
                  padding: 7px 9px !important;
                  font-size: 12px !important;
                  white-space: nowrap !important;
                }
                .volume-group,
                .preload-group,
                .download-status {
                  display: none !important;
                }
                .ctrl-spacer {
                  display: none !important;
                }
                video {
                  width: 100% !important;
                  max-width: 100% !important;
                  height: 100% !important;
                  background: #000 !important;
                }
                video:fullscreen,
                video:-webkit-full-screen,
                .player-wrapper:fullscreen video {
                  width: 100vw !important;
                  height: 100vh !important;
                  object-fit: contain !important;
                }
                img, iframe, canvas {
                  max-width: 100% !important;
                }
                input, textarea, select, button {
                  font-size: 16px !important;
                }
                * {
                  box-sizing: border-box !important;
                }
              `;
              document.head.appendChild(style);

              var hasPlayer = !!(
                document.querySelector('video') ||
                document.querySelector('.player-wrapper') ||
                document.querySelector('.player-controls') ||
                document.querySelector('.watch-main')
              );
              var existingBanner = document.getElementById('familyhub-mobile-watch-banner');
              if (hasPlayer) {
                if (!existingBanner) {
                  var anchor = document.getElementById('mode-bar') || document.querySelector('.watch-main');
                  var banner = document.createElement('div');
                  banner.id = 'familyhub-mobile-watch-banner';
                  banner.className = 'familyhub-mobile-watch-banner';
                  banner.innerHTML = '<span>Mobile viewing</span><span class="familyhub-mobile-watch-actions"><button type="button" id="familyhub-mobile-fullscreen-btn">Fullscreen</button><button type="button" id="familyhub-mobile-orientation-btn">Landscape</button></span>';
                  if (anchor && anchor.parentNode) {
                    anchor.insertAdjacentElement('afterend', banner);
                  }
                  var fullBtn = document.getElementById('familyhub-mobile-fullscreen-btn');
                  if (fullBtn) {
                    fullBtn.addEventListener('click', function() {
                      var target = document.getElementById('player-wrapper') || document.querySelector('video') || document.documentElement;
                      try {
                        if (target.requestFullscreen) {
                          target.requestFullscreen();
                        } else if (target.webkitRequestFullscreen) {
                          target.webkitRequestFullscreen();
                        }
                      } catch (e) {}
                      if (window.FamilyHubCinema && window.FamilyHubCinema.enterLandscape) {
                        window.FamilyHubCinema.enterLandscape();
                      }
                    });
                  }
                  var btn = document.getElementById('familyhub-mobile-orientation-btn');
                  if (btn) {
                    btn.addEventListener('click', function() {
                      if (window.FamilyHubCinema && window.FamilyHubCinema.toggleOrientation) {
                        window.FamilyHubCinema.toggleOrientation();
                      }
                    });
                  }
                }
              } else if (existingBanner) {
                existingBanner.remove();
              }
            })();
        """
    }

    private val api: SyncTuneApi by inject()

    private lateinit var toolbar: View
    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var rotateButton: MaterialButton
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var previousSystemUiVisibility: Int = 0
    private var previousOrientation: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private val adminMode: Boolean by lazy { intent.getBooleanExtra(EXTRA_ADMIN, false) }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_cinema_web)

        toolbar = findViewById(R.id.cinema_toolbar)
        progress = findViewById(R.id.cinema_progress)
        webView = findViewById(R.id.cinema_webview)
        rotateButton = findViewById(R.id.cinema_rotate)
        findViewById<TextView>(R.id.cinema_title).setText(
            if (adminMode) R.string.familyhub_cinema_admin else R.string.familyhub_cinema_watch,
        )

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        webView.isVerticalScrollBarEnabled = true
        webView.isHorizontalScrollBarEnabled = false
        webView.setBackgroundColor(Color.BLACK)
        webView.addJavascriptInterface(CinemaJavascriptBridge(), "FamilyHubCinema")
        // No custom touch handler here: WebView's native handling is required for nested
        // scrollables (video picker list, chat sidebar) and slider drags to receive events.

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                injectMobileCinemaFixes(view)
                updateRotateButtonVisibility(url)
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                val allowed = request.resources.filter {
                    it == PermissionRequest.RESOURCE_AUDIO_CAPTURE ||
                        it == PermissionRequest.RESOURCE_VIDEO_CAPTURE
                }.toTypedArray()
                if (allowed.isNotEmpty()) request.grant(allowed) else request.deny()
            }

            override fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                previousSystemUiVisibility = window.decorView.systemUiVisibility
                previousOrientation = requestedOrientation
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    )
                toolbar.visibility = View.GONE
                webView.visibility = View.GONE
                view.setBackgroundColor(Color.BLACK)
                (window.decorView as ViewGroup).addView(
                    view,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            }

            override fun onHideCustomView() {
                hideCustomView()
            }
        }

        findViewById<MaterialButton>(R.id.cinema_close).setOnClickListener { finish() }
        rotateButton.setOnClickListener { toggleOrientation() }
        rotateButton.visibility = View.GONE
        updateRotateButtonText()
        findViewById<MaterialButton>(R.id.cinema_refresh).setOnClickListener {
            if (webView.url.isNullOrBlank()) loadCinema() else webView.reload()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> hideCustomView()
                    webView.canGoBack() -> webView.goBack()
                    else -> finish()
                }
            }
        })

        if (savedInstanceState == null) {
            loadCinema()
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::webView.isInitialized) webView.saveState(outState)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateRotateButtonText()
        if (::webView.isInitialized) {
            injectMobileCinemaFixes(webView)
        }
    }

    override fun onDestroy() {
        hideCustomView()
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.webChromeClient = null
            webView.webViewClient = WebViewClient()
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun loadCinema() {
        val token = SyncTuneSession.token
        if (token.isNullOrBlank()) {
            showMessage("Please login first")
            return
        }
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            runCatching {
                if (adminMode) api.launchCinemaAdmin(token) else api.launchCinema(token)
            }
                .onSuccess { launch -> webView.loadUrl(launch.launch_url) }
                .onFailure {
                    Log.e(TAG, "loadCinema failed", it)
                    progress.visibility = View.GONE
                    val target = if (adminMode) "Cinema Admin" else "Cinema"
                    showMessage("Failed to open $target: ${it.message}")
                }
        }
    }

    private fun hideCustomView() {
        val currentCustomView = customView ?: return
        (window.decorView as ViewGroup).removeView(currentCustomView)
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        window.decorView.systemUiVisibility = previousSystemUiVisibility
        requestedOrientation = previousOrientation
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (::toolbar.isInitialized) toolbar.visibility = View.VISIBLE
        if (::webView.isInitialized) webView.visibility = View.VISIBLE
        updateRotateButtonText()
    }

    private fun injectMobileCinemaFixes(view: WebView) {
        view.evaluateJavascript(MOBILE_CINEMA_SCRIPT, null)
    }

    private fun updateRotateButtonVisibility(url: String?) {
        val probablyWatchPage = url?.contains("watch", ignoreCase = true) == true ||
            url?.contains("player", ignoreCase = true) == true
        webView.evaluateJavascript(
            """
                (function() {
                  return !!(
                    document.querySelector('video') ||
                    document.querySelector('.player-wrapper') ||
                    document.querySelector('.player-controls') ||
                    document.querySelector('.watch-main')
                  );
                })();
            """.trimIndent(),
        ) { result ->
            val hasPlayer = result == "true"
            rotateButton.visibility = View.GONE
            if (!probablyWatchPage && !hasPlayer && customView == null) {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }

    private fun toggleOrientation() {
        requestedOrientation = if (isLandscape()) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        updateRotateButtonText()
    }

    private fun updateRotateButtonText() {
        if (!::rotateButton.isInitialized) return
        rotateButton.setText(
            if (isLandscape()) R.string.familyhub_portrait else R.string.familyhub_landscape,
        )
        if (::webView.isInitialized) {
            val label = if (isLandscape()) "Portrait" else "Landscape"
            webView.evaluateJavascript(
                """
                    (function() {
                      var btn = document.getElementById('familyhub-mobile-orientation-btn');
                      if (btn) btn.textContent = ${label.quoteForJavascript()};
                    })();
                """.trimIndent(),
                null,
            )
        }
    }

    private fun isLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun String.quoteForJavascript(): String =
        "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun showMessage(message: String) {
        Snackbar.make(webView, message, Snackbar.LENGTH_LONG).show()
    }

    private inner class CinemaJavascriptBridge {
        @JavascriptInterface
        fun enterLandscape() {
            runOnUiThread {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                updateRotateButtonText()
            }
        }

        @JavascriptInterface
        fun toggleOrientation() {
            runOnUiThread { this@CinemaActivity.toggleOrientation() }
        }
    }

}
