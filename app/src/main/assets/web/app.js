"use strict";
// Interfaz de la app Android. Habla con el código nativo a través de window.Android.

const $ = id => document.getElementById(id);
const RESULTADOS = 8;
let buscador = null;
let estado = {};
let ultimaConsulta = "";

// ---------- utilidades ----------

function escapar(t) {
  return t.replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

function resaltar(texto, consulta) {
  const raices = new Set(tokenizar(consulta));
  return escapar(texto).replace(/[A-Za-zÁÉÍÓÚÜÑáéíóúüñ0-9]+/g, p => {
    const n = normalizar(p);
    return n.length >= 2 && raices.has(raiz(n)) ? `<mark>${p}</mark>` : p;
  });
}

function mostrar(id, si) { $(id).hidden = !si; }

function mensaje(html, esError) {
  $("mensaje").innerHTML = html;
  $("mensaje").classList.toggle("error", !!esError);
  mostrar("mensaje", !!html);
}

// ---------- estado general ----------

function pintarEstado() {
  const hayCarpeta = estado.carpeta !== null && estado.carpeta !== undefined;
  mostrar("bienvenida", !hayCarpeta && !estado.indexando);
  mostrar("progreso", !!estado.indexando);
  mostrar("zona-busqueda", !!buscador);

  const pie = [];
  if (buscador) {
    pie.push(`${buscador.libros.length} libros · ${buscador.frags.length.toLocaleString("es")} fragmentos`);
  }
  if (hayCarpeta) {
    pie.push(estado.conectada
      ? `Carpeta: <b>${escapar(estado.carpeta || "")}</b> (conectada)`
      : `Pendrive desconectado — la búsqueda igual funciona`);
  }
  let botones = "";
  if (hayCarpeta && !estado.indexando) {
    botones = `<br><button class="secundario" onclick="Android.actualizar()">Actualizar libros</button>` +
              `<button class="secundario" onclick="Android.elegirCarpeta()">Cambiar carpeta</button>`;
  }
  $("pie").innerHTML = pie.join("<br>") + botones;
}

async function cargarIndice() {
  if (!estado.hayIndice) { buscador = null; return; }
  try {
    const r = await fetch(`/datos/biblioteca.json?v=${estado.version}`);
    if (!r.ok) throw new Error(r.status);
    buscador = new Buscador(await r.json());
  } catch (e) {
    buscador = null;
    mensaje("No se pudo cargar el índice de libros. Tocá «Actualizar libros».", true);
  }
}

// ---------- mensajes desde Android ----------

window.onNativo = async (tipo, datos) => {
  if (tipo === "estado") {
    const cambioIndice = datos.version !== estado.version;
    estado = datos;
    if (cambioIndice) await cargarIndice();
    pintarEstado();
  } else if (tipo === "progreso") {
    estado.indexando = true;
    mensaje("");
    pintarEstado();
    if (datos.fase === "buscando") {
      $("progreso-texto").textContent = "Buscando libros en la carpeta…";
      $("progreso-barra").style.width = "0%";
    } else {
      const total = (datos.actual - 1 + (datos.parcial || 0)) / datos.total;
      $("progreso-texto").textContent = `Libro ${datos.actual} de ${datos.total}: ${datos.libro}`;
      $("progreso-barra").style.width = `${Math.round(total * 100)}%`;
    }
  } else if (tipo === "listo") {
    estado = datos.estado;
    await cargarIndice();
    pintarEstado();
    let txt = `Listo: ${datos.libros} libros preparados.`;
    if (datos.sinTexto.length) txt += `<br><small>Sin texto (¿escaneados?): ${datos.sinTexto.map(escapar).join(", ")}</small>`;
    if (datos.conError.length) txt += `<br><small>No se pudieron leer: ${datos.conError.map(escapar).join(", ")}</small>`;
    mensaje(txt);
    setTimeout(() => { if (!datos.sinTexto.length && !datos.conError.length) mensaje(""); }, 5000);
  } else if (tipo === "error") {
    estado = datos.estado;
    pintarEstado();
    mensaje(escapar(datos.mensaje), true);
  }
};

// ---------- búsqueda ----------

function buscar(consulta) {
  ultimaConsulta = consulta;
  const res = buscador.buscar(consulta, RESULTADOS);
  const ol = $("fuentes");
  ol.innerHTML = "";
  mostrar("fuentes-caja", true);
  if (!res.length) {
    $("fuentes-titulo").textContent = "Sin resultados";
    ol.innerHTML = "<li>No encontré nada. Probá con otras palabras (por ejemplo, sinónimos).</li>";
    return;
  }
  $("fuentes-titulo").textContent = "Resultados";
  res.forEach((r, i) => {
    const li = document.createElement("li");
    li.innerHTML =
      `<span class="fuente-titulo">${escapar(r.libro.titulo)}</span> ` +
      `<span class="fuente-lugar">${escapar(r.frag.u || "")}</span>` +
      `<div class="fragmento">${resaltar(r.frag.t, consulta)}</div>` +
      (r.libro.uri ? `<button class="secundario abrir" data-uri="${escapar(r.libro.uri)}">Abrir libro</button>` : "");
    ol.appendChild(li);
  });
  window.scrollTo({ top: $("fuentes-caja").offsetTop - 70, behavior: "smooth" });
}

$("fuentes").addEventListener("click", e => {
  const uri = e.target.dataset && e.target.dataset.uri;
  if (uri) Android.abrirLibro(uri);
});

$("form").addEventListener("submit", e => {
  e.preventDefault();
  const consulta = $("pregunta").value.trim();
  if (consulta && buscador) { $("pregunta").blur(); buscar(consulta); }
});

$("pregunta").addEventListener("keydown", e => {
  if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); $("form").requestSubmit(); }
});

$("btn-elegir").addEventListener("click", () => Android.elegirCarpeta());

// ---------- inicio ----------

(async () => {
  if (!window.Android) {
    document.body.innerHTML = "<p style='padding:20px'>Esta página funciona dentro de la app Biblioteca IA.</p>";
    return;
  }
  estado = JSON.parse(Android.estado());
  await cargarIndice();
  pintarEstado();
})();
