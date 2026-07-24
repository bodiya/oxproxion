package io.github.stardomains3.oxproxion

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Environment
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Renders a raw HTML code block from an assistant response as an actual web page.
 */
class HtmlPreviewFragment : Fragment() {
    companion object {
        private const val ARG_HTML = "html"

        fun newInstance(html: String): HtmlPreviewFragment {
            return HtmlPreviewFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_HTML, html)
                }
            }
        }
    }

    private var webView: WebView? = null
    private var isWebViewDestroyed = false
    private var currentHtml: String = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_html_preview, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val toolbar = view.findViewById<MaterialToolbar>(R.id.toolbar)

        toolbar.setNavigationOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        toolbar.inflateMenu(R.menu.menu_html_preview)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_save_html -> {
                    saveHtmlToDownloads()
                    true
                }
                R.id.action_print -> {
                    createWebPrintJob(webView)
                    true
                }
                else -> false
            }
        }

        currentHtml = arguments?.getString(ARG_HTML, "") ?: ""

        webView = view.findViewById(R.id.webview_html_preview)
        webView?.apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = false
                setSupportZoom(true)
                textZoom = 100
                builtInZoomControls = true
                displayZoomControls = false
                defaultTextEncodingName = "UTF-8"
                setGeolocationEnabled(false)
                mediaPlaybackRequiresUserGesture = true
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: return false
                    if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("mailto:")) {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                            return true
                        } catch (e: Exception) { e.printStackTrace() }
                    }
                    return false
                }
            }

            setBackgroundColor(Color.WHITE)
            loadDataWithBaseURL(null, currentHtml, "text/html", "UTF-8", null)
        }
    }

    private fun saveHtmlToDownloads() {
        if (currentHtml.isEmpty()) return
        val filename = "codeblock-${System.currentTimeMillis()}.html"
        val context = requireContext()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/html")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw Exception("MediaStore insert failed")
                resolver.openOutputStream(uri)?.use { out ->
                    out.write(currentHtml.toByteArray())
                } ?: throw Exception("Cannot open output stream")
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "✅ Saved to Downloads: $filename", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "❌ Save failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun createWebPrintJob(webView: WebView?) {
        if (webView == null) return
        val printManager = requireContext().getSystemService(Context.PRINT_SERVICE) as? PrintManager
        val printAdapter = webView.createPrintDocumentAdapter("Html_Preview")
        val jobName = getString(R.string.app_name) + " Document"
        printManager?.print(jobName, printAdapter, PrintAttributes.Builder().build())
    }

    override fun onDestroyView() {
        webView?.removeAllViews()
        super.onDestroyView()
    }

    override fun onDestroy() {
        if (!isWebViewDestroyed) {
            webView?.apply {
                stopLoading()
                destroy()
                isWebViewDestroyed = true
            }
            webView = null
        }
        super.onDestroy()
    }
}
