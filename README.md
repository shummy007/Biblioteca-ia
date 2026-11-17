# Biblioteca IA

App de Android para consultar una biblioteca de libros **sin internet**, pensada para
apagones o caídas de la red.

- Conectás un pendrive con libros (PDF, EPUB, TXT) y elegís la carpeta una sola vez.
- La app lee los libros y arma un índice dentro del celular.
- Después buscás al instante, **aunque el pendrive esté desconectado**. Para abrir el libro
  original sí hace falta conectarlo.
- La app no tiene permiso de internet: no envía ni descarga nada.

## Descargar

En la sección **Releases** de este repositorio está el último APK.

## Próximamente

- IA local (llama.cpp) que responde preguntas usando los fragmentos encontrados.

## Compilación

GitHub Actions compila el APK en cada cambio (`.github/workflows/compilar.yml`).
Las versiones publicadas se crean con una etiqueta `v*`.
