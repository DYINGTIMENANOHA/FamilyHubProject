package code.name.monkey.retromusic.activities

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
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

class LivestreamActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "LivestreamActivity"
        private const val EXTRA_ENV = "extra_env"
        private const val MOBILE_LIVESTREAM_SCRIPT = """
            (function() {
              var oldStyle = document.getElementById('familyhub-livestream-mobile-style');
              if (oldStyle) oldStyle.remove();
              var style = document.createElement('style');
              style.id = 'familyhub-livestream-mobile-style';
              style.textContent = `
                .familyhub-mobile-watch-banner {
                  display: flex !important;
                  align-items: center !important;
                  justify-content: space-between !important;
                  gap: 8px !important;
                  width: 100% !important;
                  margin: 0 0 10px !important;
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
                @media (max-width: 720px) {
                  .overlay { padding: 8px !important; }
                  .header { padding: 16px 10px !important; margin-bottom: 12px !important; }
                  .header h1 { font-size: 1.5em !important; }
                  .video-section { padding: 10px !important; }
                  .status-bar { align-items: flex-start !important; flex-direction: column !important; gap: 10px !important; }
                }
              `;
              document.head.appendChild(style);

              var hasPlayer = !!(document.getElementById('videoElement') || document.querySelector('.video-container'));
              var existingBanner = document.getElementById('familyhub-mobile-watch-banner');
              if (hasPlayer) {
                if (!existingBanner) {
                  var anchor = document.querySelector('.video-container') || document.querySelector('.video-section');
                  var banner = document.createElement('div');
                  banner.id = 'familyhub-mobile-watch-banner';
                  banner.className = 'familyhub-mobile-watch-banner';
                  banner.innerHTML = '<span>Mobile viewing</span><span class="familyhub-mobile-watch-actions"><button type="button" id="familyhub-mobile-fullscreen-btn">Fullscreen</button><button type="button" id="familyhub-mobile-orientation-btn">Landscape</button></span>';
                  if (anchor && anchor.parentNode) {
                    anchor.insertAdjacentElement('beforebegin', banner);
                  }
                  var fullBtn = document.getElementById('familyhub-mobile-fullscreen-btn');
                  if (fullBtn) {
                    fullBtn.addEventListener('click', function() {
                      var target = document.querySelector('.video-container') || document.getElementById('videoElement') || document.documentElement;
                      try {
                        if (target.requestFullscreen) {
                          target.requestFullscreen();
                        } else if (target.webkitRequestFullscreen) {
                          target.webkitRequestFullscreen();
                        }
                      } catch (e) {}
                      if (window.FamilyHubLivestream && window.FamilyHubLivestream.enterLandscape) {
                        window.FamilyHubLivestream.enterLandscape();
                      }
                    });
                  }
                  var orientationBtn = document.getElementById('familyhub-mobile-orientation-btn');
                  if (orientationBtn) {
                    orientationBtn.addEventListener('click', function() {
                      if (window.FamilyHubLivestream && window.FamilyHubLivestream.toggleOrientation) {
                        window.FamilyHubLivestream.toggleOrientation();
                      }
                    });
                  }
                }
              } else if (existingBanner) {
                existingBanner.remove();
              }
            })();
        """

        fun intent(context: Context, env: String? = null): Intent =
            Intent(context, LivestreamActivity::class.java).apply {
                if (!env.isNullOrBlank()) putExtra(EXTRA_ENV, env)
            }
    }

    private val api: SyncTuneApi by inject()

    private lateinit var toolbar: View
    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var previousSystemUiVisibility: Int = 0
    private var previousOrientation: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private val env: String? by lazy { intent.getStringExtra(EXTRA_ENV)?.lowercase() }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_cinema_web)

        toolbar = findViewById(R.id.cinema_toolbar)
        progress = findViewById(R.id.cinema_progress)
        webView = findViewById(R.id.cinema_webview)
        findViewById<TextView>(R.id.cinema_title).setText(R.string.familyhub_livestream)
        findViewById<MaterialButton>(R.id.cinema_rotate).visibility = android.view.View.GONE

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
        webView.setBackgroundColor(Color.BLACK)
        webView.addJavascriptInterface(LivestreamJavascriptBridge(), "FamilyHubLivestream")

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                injectMobileLivestreamFixes(view)
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress in 1..99) android.view.View.VISIBLE else android.view.View.GONE
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
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
        findViewById<MaterialButton>(R.id.cinema_refresh).setOnClickListener {
            if (webView.url.isNullOrBlank()) loadLivestream() else webView.reload()
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
            loadLivestream()
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
        updateOrientationButtonText()
        if (::webView.isInitialized) {
            injectMobileLivestreamFixes(webView)
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

    private fun loadLivestream() {
        val token = SyncTuneSession.token
        if (token.isNullOrBlank()) {
            showMessage("Please login first")
            return
        }
        progress.visibility = android.view.View.VISIBLE
        lifecycleScope.launch {
            runCatching { api.launchLivestream(token, env) }
                .onSuccess { launch -> webView.loadUrl(launch.launch_url) }
                .onFailure {
                    Log.e(TAG, "loadLivestream failed", it)
                    progress.visibility = android.view.View.GONE
                    showMessage("Failed to open Livestream: ${it.message}")
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
        updateOrientationButtonText()
    }

    private fun injectMobileLivestreamFixes(view: WebView) {
        view.evaluateJavascript(MOBILE_LIVESTREAM_SCRIPT, null)
        updateOrientationButtonText()
    }

    private fun enterLandscape() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        updateOrientationButtonText()
    }

    private fun toggleOrientation() {
        requestedOrientation = if (isLandscape()) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        updateOrientationButtonText()
    }

    private fun updateOrientationButtonText() {
        if (!::webView.isInitialized) return
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

    private fun isLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun String.quoteForJavascript(): String =
        "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun showMessage(message: String) {
        Snackbar.make(webView, message, Snackbar.LENGTH_LONG).show()
    }

    private inner class LivestreamJavascriptBridge {
        @JavascriptInterface
        fun enterLandscape() {
            runOnUiThread { this@LivestreamActivity.enterLandscape() }
        }

        @JavascriptInterface
        fun toggleOrientation() {
            runOnUiThread { this@LivestreamActivity.toggleOrientation() }
        }
    }
}
