package ar.biblioteca.ia

import android.content.Context
import android.net.Uri
import android.text.Html
import android.util.JsonReader
import android.util.JsonWriter
import androidx.documentfile.provider.DocumentFile
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Lee los libros de una carpeta (por ejemplo, del pendrive), extrae el texto,
 * lo corta en fragmentos y arma el índice biblioteca.json que usa el buscador.
 * Cada libro se guarda en caché: si no cambió, no se vuelve a leer.
 */
object Indexador {

    private val EXTENSIONES = setOf("pdf", "epub", "txt", "md", "html", "htm")
    private const val TAM_FRAGMENTO = 900
    private const val SOLAPE = 150

    class Libro(val doc: DocumentFile, val ruta: String)
    class Fragmento(val lugar: String, val texto: String)

    fun iniciar(ctx: Context) {
        PDFBoxResourceLoader.init(ctx)
    }

    // ---------- recorrer la carpeta ----------

    private fun buscarLibros(dir: DocumentFile, ruta: String, prof: Int, out: MutableList<Libro>) {
        if (prof > 6) return
        for (f in dir.listFiles()) {
            val nombre = f.name ?: continue
            if (nombre.startsWith(".")) continue
            if (f.isDirectory) {
                buscarLibros(f, "$ruta$nombre/", prof + 1, out)
            } else if (nombre.substringAfterLast('.', "").lowercase() in EXTENSIONES) {
                out.add(Libro(f, "$ruta$nombre"))
            }
        }
    }

    // ---------- indexar ----------

    /** Devuelve un resumen. Lanza una excepción con un mensaje en español si algo grave falla. */
    fun indexar(
        ctx: Context,
        carpeta: Uri,
        salida: File,
        cacheDir: File,
        progreso: (JSONObject) -> Unit
    ): JSONObject {
        val raiz = DocumentFile.fromTreeUri(ctx, carpeta)
            ?: throw Exception("No se puede abrir la carpeta elegida.")
        if (!conectada(raiz)) throw Exception("No encuentro la carpeta. ¿Está conectado el pendrive?")

        progreso(JSONObject().put("fase", "buscando"))
        val libros = mutableListOf<Libro>()
        buscarLibros(raiz, "", 0, libros)
        libros.sortBy { it.ruta.lowercase() }
        if (libros.isEmpty()) throw Exception("No encontré libros (PDF, EPUB o TXT) en esa carpeta.")

        cacheDir.mkdirs()
        salida.parentFile?.mkdirs()
        val usados = mutableSetOf<String>()
        val conError = mutableListOf<String>()
        val sinTexto = mutableListOf<String>()
        var totalFragmentos = 0
        val tmp = File(salida.parentFile, "biblioteca.tmp")

        val librosOk = mutableListOf<Pair<String, String>>() // título, uri
        JsonWriter(OutputStreamWriter(FileOutputStream(tmp), Charsets.UTF_8).buffered()).use { w ->
            w.beginObject()
            w.name("version").value(1)
            w.name("fragmentos").beginArray()
            libros.forEachIndexed { i, libro ->
                val nombre = libro.doc.name ?: libro.ruta
                fun avisar(parcial: Double) = progreso(
                    JSONObject().put("fase", "leyendo").put("actual", i + 1)
                        .put("total", libros.size).put("libro", nombre).put("parcial", parcial)
                )
                avisar(0.0)
                val clave = sha1("${libro.doc.uri}|${libro.doc.length()}|${libro.doc.lastModified()}")
                usados.add(clave)
                val cache = File(cacheDir, "$clave.json")
                val frags: List<Fragmento> = if (cache.exists()) {
                    try { leerCache(cache) } catch (e: Exception) { cache.delete(); emptyList() }
                } else {
                    try {
                        val f = extraer(ctx, libro) { p -> avisar(p) }
                        escribirCache(cache, f)
                        f
                    } catch (e: Throwable) {
                        if (!conectada(raiz)) throw Exception("Se desconectó el pendrive. Volvé a conectarlo y tocá «Actualizar libros».")
                        conError.add(nombre)
                        emptyList()
                    }
                }
                if (frags.isEmpty()) {
                    if (nombre !in conError) sinTexto.add(nombre)
                    return@forEachIndexed
                }
                val id = librosOk.size
                librosOk.add(titulo(nombre) to libro.doc.uri.toString())
                for (f in frags) {
                    w.beginObject().name("l").value(id.toLong()).name("u").value(f.lugar)
                        .name("t").value(f.texto).endObject()
                }
                totalFragmentos += frags.size
            }
            w.endArray()
            w.name("libros").beginArray()
            for ((t, u) in librosOk) w.beginObject().name("titulo").value(t).name("uri").value(u).endObject()
            w.endArray()
            w.endObject()
        }
        if (salida.exists()) salida.delete()
        if (!tmp.renameTo(salida)) throw Exception("No se pudo guardar el índice.")
        cacheDir.listFiles()?.forEach { if (it.nameWithoutExtension !in usados) it.delete() }

        return JSONObject()
            .put("libros", librosOk.size)
            .put("fragmentos", totalFragmentos)
            .put("conError", org.json.JSONArray(conError))
            .put("sinTexto", org.json.JSONArray(sinTexto))
    }

    fun conectada(dir: DocumentFile?): Boolean =
        try { dir != null && dir.exists() && dir.canRead() } catch (e: Exception) { false }

    private fun titulo(nombre: String) =
        nombre.substringBeforeLast('.').replace('_', ' ').trim()

    // ---------- extracción de texto ----------

    private fun extraer(ctx: Context, libro: Libro, parcial: (Double) -> Unit): List<Fragmento> {
        val ext = (libro.doc.name ?: "").substringAfterLast('.', "").lowercase()
        return when (ext) {
            "pdf" -> conCopia(ctx, libro, ".pdf") { leerPdf(it, parcial) }
            "epub" -> conCopia(ctx, libro, ".epub") { leerEpub(it) }
            "html", "htm" -> fragmentos("", htmlATexto(leerTexto(ctx, libro.doc.uri)))
            else -> fragmentos("", leerTexto(ctx, libro.doc.uri))
        }
    }

    /** Copia el archivo del pendrive a la memoria de la app (más rápido y permite acceso aleatorio). */
    private fun <T> conCopia(ctx: Context, libro: Libro, ext: String, leer: (File) -> T): T {
        val tmp = File(ctx.cacheDir, "libro_actual$ext")
        try {
            ctx.contentResolver.openInputStream(libro.doc.uri).use { entrada ->
                if (entrada == null) throw Exception("No se pudo abrir")
                FileOutputStream(tmp).use { entrada.copyTo(it, 64 * 1024) }
            }
            return leer(tmp)
        } finally {
            tmp.delete()
        }
    }

    private fun leerPdf(archivo: File, parcial: (Double) -> Unit): List<Fragmento> {
        val out = mutableListOf<Fragmento>()
        PDDocument.load(archivo).use { doc ->
            val stripper = PDFTextStripper()
            val paginas = doc.numberOfPages
            for (p in 1..paginas) {
                stripper.startPage = p
                stripper.endPage = p
                val texto = try { stripper.getText(doc) } catch (e: Exception) { "" }
                out.addAll(fragmentos("pág. $p", texto))
                if (p % 10 == 0) parcial(p.toDouble() / paginas)
            }
        }
        return out
    }

    private fun leerEpub(archivo: File): List<Fragmento> {
        val out = mutableListOf<Fragmento>()
        ZipFile(archivo).use { z ->
            val orden = mutableListOf<String>()
            try {
                val container = z.getInputStream(z.getEntry("META-INF/container.xml")).readBytes().toString(Charsets.UTF_8)
                val opf = atributo(Regex("<rootfile\\b[^>]*>").find(container)!!.value, "full-path")!!
                val base = opf.substringBeforeLast('/', "")
                val opfTxt = z.getInputStream(z.getEntry(opf)).readBytes().toString(Charsets.UTF_8)
                val items = mutableMapOf<String, String>()
                for (m in Regex("<item\\b[^>]*>").findAll(opfTxt)) {
                    val id = atributo(m.value, "id") ?: continue
                    val href = atributo(m.value, "href") ?: continue
                    items[id] = href
                }
                for (m in Regex("<itemref\\b[^>]*>").findAll(opfTxt)) {
                    val href = items[atributo(m.value, "idref") ?: continue] ?: continue
                    val decod = try { URLDecoder.decode(href.replace("+", "%2B"), "UTF-8") } catch (e: Exception) { href }
                    orden.add(normalizarRuta(if (base.isEmpty()) decod else "$base/$decod"))
                }
            } catch (e: Exception) { /* EPUB raro: se usa el orden alfabético */ }
            if (orden.isEmpty()) {
                z.entries().asSequence().map { it.name }
                    .filter { it.lowercase().let { n -> n.endsWith(".xhtml") || n.endsWith(".html") || n.endsWith(".htm") } }
                    .sorted().toCollection(orden)
            }
            orden.forEachIndexed { i, nombre ->
                val entrada = z.getEntry(nombre) ?: return@forEachIndexed
                val html = z.getInputStream(entrada).readBytes().toString(Charsets.UTF_8)
                out.addAll(fragmentos("cap. ${i + 1}", htmlATexto(html)))
            }
        }
        return out
    }

    private fun atributo(etiqueta: String, nombre: String): String? =
        Regex("\\b$nombre\\s*=\\s*[\"']([^\"']*)[\"']").find(etiqueta)?.groupValues?.get(1)

    private fun normalizarRuta(ruta: String): String {
        val partes = ArrayDeque<String>()
        for (p in ruta.split('/')) {
            when (p) {
                "", "." -> {}
                ".." -> partes.removeLastOrNull()
                else -> partes.addLast(p)
            }
        }
        return partes.joinToString("/")
    }

    private fun leerTexto(ctx: Context, uri: Uri): String {
        val bytes = ctx.contentResolver.openInputStream(uri).use { it?.readBytes() } ?: ByteArray(0)
        val utf8 = bytes.toString(Charsets.UTF_8)
        return if ('\uFFFD' in utf8) bytes.toString(Charsets.ISO_8859_1) else utf8
    }

    private val SIN_CONTENIDO = Regex("(?is)<head\\b.*?</head>|<script\\b.*?</script>|<style\\b.*?</style>")

    private fun htmlATexto(html: String): String =
        Html.fromHtml(SIN_CONTENIDO.replace(html, " "), Html.FROM_HTML_MODE_LEGACY).toString()

    // ---------- fragmentación (igual que la versión de PC) ----------

    private val R_GUION = Regex("-\n(?=[a-záéíóúñ])")
    private val R_ESPACIOS_LINEA = Regex("[ \\t]*\n[ \\t]*")
    private val R_RENGLON = Regex("(?<!\n)\n(?!\n)")
    private val R_ESPACIOS = Regex("[ \\t\\u00A0]+")
    private val R_SALTOS = Regex("\n{3,}")

    private fun limpiar(t0: String): String {
        var t = t0.replace("\r", "").replace("\uFFFC", "")
        t = R_GUION.replace(t, "")
        t = R_ESPACIOS_LINEA.replace(t, "\n")
        t = R_RENGLON.replace(t, " ")
        t = R_ESPACIOS.replace(t, " ")
        t = R_SALTOS.replace(t, "\n\n")
        return t.trim()
    }

    private fun fragmentos(lugar: String, texto: String): List<Fragmento> {
        val t = limpiar(texto)
        if (t.length <= TAM_FRAGMENTO) return if (t.length > 40) listOf(Fragmento(lugar, t)) else emptyList()
        val out = mutableListOf<Fragmento>()
        var i = 0
        while (i < t.length) {
            var fin = minOf(i + TAM_FRAGMENTO, t.length)
            if (fin < t.length) {
                val desde = i + TAM_FRAGMENTO / 2
                val punto = t.lastIndexOf(". ", fin - 1).let { if (it >= desde) it else -1 }
                val salto = t.lastIndexOf('\n', fin - 1).let { if (it >= desde) it else -1 }
                val corte = maxOf(punto, salto)
                if (corte > 0) fin = corte + 1
            }
            val f = t.substring(i, fin).trim()
            if (f.length > 40) out.add(Fragmento(lugar, f))
            if (fin >= t.length) break
            i = maxOf(fin - SOLAPE, i + 1)
        }
        return out
    }

    // ---------- caché por libro ----------

    private fun escribirCache(archivo: File, frags: List<Fragmento>) {
        val tmp = File(archivo.path + ".tmp")
        JsonWriter(OutputStreamWriter(FileOutputStream(tmp), Charsets.UTF_8).buffered()).use { w ->
            w.beginArray()
            for (f in frags) w.beginArray().value(f.lugar).value(f.texto).endArray()
            w.endArray()
        }
        tmp.renameTo(archivo)
    }

    private fun leerCache(archivo: File): List<Fragmento> {
        val out = mutableListOf<Fragmento>()
        JsonReader(InputStreamReader(FileInputStream(archivo), Charsets.UTF_8).buffered()).use { r ->
            r.beginArray()
            while (r.hasNext()) {
                r.beginArray()
                val lugar = r.nextString()
                val texto = r.nextString()
                r.endArray()
                out.add(Fragmento(lugar, texto))
            }
            r.endArray()
        }
        return out
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
