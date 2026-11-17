package ar.biblioteca.ia

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.io.File

class MainActivity : Activity() {

    private lateinit var web: WebView
    private val principal = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("biblioteca", MODE_PRIVATE) }
    private val carpetaPublica by lazy { File(filesDir, "publico").apply { mkdirs() } }
    private val archivoIndice by lazy { File(carpetaPublica, "biblioteca.json") }
    private val carpetaCache by lazy { File(filesDir, "cache_libros") }

    @Volatile private var indexando = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Indexador.iniciar(applicationContext)

        val cargador = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/datos/", WebViewAssetLoader.InternalStoragePathHandler(this, carpetaPublica))
            .build()

        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                cargador.shouldInterceptRequest(request.url)

            // Nunca navegar fuera de la app
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.host != WebViewAssetLoader.DEFAULT_DOMAIN
        }
        web.addJavascriptInterface(Puente(), "Android")
        web.loadUrl("https://${WebViewAssetLoader.DEFAULT_DOMAIN}/assets/web/index.html")
    }

    override fun onResume() {
        super.onResume()
        // Puede que hayan conectado o desconectado el pendrive mientras tanto
        if (::web.isInitialized) enviar("estado", estado())
    }

    @Deprecated("API de Activity simple")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    // ---------- estado ----------

    private fun carpetaGuardada(): Uri? = prefs.getString("carpeta", null)?.let { Uri.parse(it) }

    private fun estado(): JSONObject {
        val uri = carpetaGuardada()
        val dir = uri?.let { try { DocumentFile.fromTreeUri(this, it) } catch (e: Exception) { null } }
        val conectada = Indexador.conectada(dir)
        return JSONObject()
            .put("carpeta", if (uri != null) (if (conectada) dir?.name else prefs.getString("nombre", "")) ?: "" else JSONObject.NULL)
            .put("conectada", conectada)
            .put("hayIndice", archivoIndice.exists())
            .put("version", archivoIndice.lastModified())
            .put("indexando", indexando)
    }

    private fun enviar(tipo: String, datos: JSONObject) {
        val js = "window.onNativo && window.onNativo(${JSONObject.quote(tipo)}, $datos)"
        principal.post { web.evaluateJavascript(js, null) }
    }

    private fun aviso(texto: String) = principal.post { Toast.makeText(this, texto, Toast.LENGTH_LONG).show() }

    // ---------- elegir carpeta del pendrive ----------

    private fun abrirSelector() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, PEDIDO_CARPETA)
        } catch (e: ActivityNotFoundException) {
            aviso("Este celular no tiene el selector de carpetas de Android.")
        }
    }

    @Deprecated("API de Activity simple")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PEDIDO_CARPETA) return
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            enviar("estado", estado())
            return
        }
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) { /* algunos proveedores no lo permiten; se usa igual */ }
        // Liberar el permiso de la carpeta anterior
        carpetaGuardada()?.takeIf { it != uri }?.let {
            try { contentResolver.releasePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) {}
        }
        val nombre = DocumentFile.fromTreeUri(this, uri)?.name ?: ""
        prefs.edit().putString("carpeta", uri.toString()).putString("nombre", nombre).apply()
        indexar()
    }

    // ---------- indexar ----------

    private fun indexar() {
        val uri = carpetaGuardada()
        if (uri == null) { abrirSelector(); return }
        if (indexando) return
        indexando = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enviar("estado", estado())
        Thread {
            try {
                val resumen = Indexador.indexar(this, uri, archivoIndice, carpetaCache) { enviar("progreso", it) }
                indexando = false
                enviar("listo", resumen.put("estado", estado()))
            } catch (e: Throwable) {
                indexando = false
                enviar("error", JSONObject().put("mensaje", e.message ?: e.toString()).put("estado", estado()))
            } finally {
                indexando = false
                principal.post { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }
        }.start()
    }

    // ---------- abrir el libro original ----------

    private fun abrirLibro(uriTexto: String) {
        val uri = Uri.parse(uriTexto)
        val existe = try { DocumentFile.fromSingleUri(this, uri)?.exists() == true } catch (e: Exception) { false }
        if (!existe) {
            aviso("Conectá el pendrive para abrir el libro original.")
            return
        }
        val tipo = contentResolver.getType(uri) ?: "*/*"
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, tipo)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(i)
        } catch (e: ActivityNotFoundException) {
            aviso("No hay ninguna app instalada para abrir este tipo de archivo.")
        }
    }

    // ---------- puente con la página ----------

    inner class Puente {
        @JavascriptInterface fun estado(): String = this@MainActivity.estado().toString()
        @JavascriptInterface fun elegirCarpeta() { principal.post { abrirSelector() } }
        @JavascriptInterface fun actualizar() { principal.post { indexar() } }
        @JavascriptInterface fun abrirLibro(uri: String) { principal.post { this@MainActivity.abrirLibro(uri) } }
    }

    companion object {
        private const val PEDIDO_CARPETA = 1
    }
}
