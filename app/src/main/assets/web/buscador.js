// Buscador BM25 en español, sin dependencias. Funciona 100% offline.
"use strict";

const PALABRAS_VACIAS = new Set((
  "a al algo algun alguna algunas alguno algunos ante antes aquel aquella aquellas aquellos aqui asi aun aunque " +
  "bajo bien cada como con contra cual cuales cuando cuanto de del desde donde dos el ella ellas ello ellos en " +
  "entre era eran es esa esas ese eso esos esta estaba estan estar este esto estos fue fueron ha hace hacer hacia " +
  "han hasta hay la las le les lo los mas me mi mis mucho muy nada ni no nos nosotros o otra otras otro otros " +
  "para pero poco por porque puede pueden que quien se sea segun ser si sido sin sobre son su sus tambien tan " +
  "tanto te tener tiene tienen todo todos tu tus un una unas uno unos usted y ya yo " +
  "cual cuales debo debe deben puedo podemos quiero sirve sirven hago hacen hago"
).split(" "));

function normalizar(t) {
  return t.toLowerCase().normalize("NFD").replace(/[̀-ͯ]/g, "");
}

// Raíz aproximada de palabras en español (para que "hervir" ≈ "hervida" ≈ "hervido").
const SUFIJOS = ["amientos", "imientos", "amiento", "imiento", "aciones", "uciones", "adoras", "adores",
  "ancias", "encias", "mente", "acion", "ucion", "ancia", "encia", "adora", "ador", "ante", "ables", "ibles",
  "able", "ible", "istas", "ista", "osos", "osas", "oso", "osa", "ando", "iendo", "ados", "idos", "adas", "idas",
  "ado", "ido", "ada", "ida", "aron", "ieron", "aba", "ian", "ar", "er", "ir", "es", "as", "os", "a", "o", "e", "s"];

function raiz(p) {
  if (p.length <= 4) return p;
  for (const s of SUFIJOS) {
    if (p.endsWith(s) && p.length - s.length >= 3) return p.slice(0, -s.length);
  }
  return p;
}

function tokenizar(texto) {
  const out = [];
  for (const p of normalizar(texto).split(/[^a-z0-9ñ]+/)) {
    if (p.length < 2 || PALABRAS_VACIAS.has(p)) continue;
    out.push(raiz(p));
  }
  return out;
}

class Buscador {
  constructor(biblioteca) {
    this.libros = biblioteca.libros;
    this.frags = biblioteca.fragmentos;
    this.indice = new Map();   // término -> [[idFragmento, frecuencia], ...]
    this.largos = new Uint32Array(this.frags.length);
    let total = 0;
    this.frags.forEach((f, id) => {
      const toks = tokenizar(f.t);
      this.largos[id] = toks.length;
      total += toks.length;
      const tf = new Map();
      for (const t of toks) tf.set(t, (tf.get(t) || 0) + 1);
      for (const [t, n] of tf) {
        let lista = this.indice.get(t);
        if (!lista) this.indice.set(t, (lista = []));
        lista.push(id, n);   // plano para ahorrar memoria
      }
    });
    this.largoMedio = total / Math.max(1, this.frags.length);
  }

  buscar(consulta, cuantos = 5) {
    const k1 = 1.4, b = 0.75, N = this.frags.length;
    const terminos = [...new Set(tokenizar(consulta))];
    const puntajes = new Map();
    for (const t of terminos) {
      const lista = this.indice.get(t);
      if (!lista) continue;
      const df = lista.length / 2;
      const idf = Math.log(1 + (N - df + 0.5) / (df + 0.5));
      for (let i = 0; i < lista.length; i += 2) {
        const id = lista[i], tf = lista[i + 1];
        const s = idf * (tf * (k1 + 1)) / (tf + k1 * (1 - b + b * this.largos[id] / this.largoMedio));
        puntajes.set(id, (puntajes.get(id) || 0) + s);
      }
    }
    // Premio a los fragmentos que tienen más términos distintos de la pregunta
    const candidatos = [...puntajes].sort((a, b) => b[1] - a[1]).slice(0, 60);
    const res = candidatos.map(([id, s]) => {
      const toks = new Set(tokenizar(this.frags[id].t));
      const cubiertos = terminos.filter(t => toks.has(t)).length;
      return { id, puntaje: s * (1 + cubiertos / Math.max(1, terminos.length)) };
    });
    res.sort((a, b) => b.puntaje - a.puntaje);
    return res.slice(0, cuantos).map(r => ({
      ...r, frag: this.frags[r.id], libro: this.libros[this.frags[r.id].l]
    }));
  }
}

if (typeof module !== "undefined") module.exports = { Buscador, tokenizar, raiz, normalizar };
