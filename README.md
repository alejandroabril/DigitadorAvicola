<div align="center">

# 🐔 DigitadorAvicola · Flock Tracker

**App Android para la gestión de ensayos de engorde de pollo (broiler) en granja experimental.**

Digitación semanal · Indicadores zootécnicos · Exportación Excel/PDF · Respaldo `.davi`

![Kotlin](https://img.shields.io/badge/Kotlin-2.3-7F52FF?logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white)
![Room](https://img.shields.io/badge/Room-2.8-orange)
![Hilt](https://img.shields.io/badge/DI-Hilt-2C4F7C)
![minSdk](https://img.shields.io/badge/minSdk-26-3DDC84?logo=android&logoColor=white)

</div>

---

## ¿Qué hace?

Reemplaza la planilla de Excel hecha a mano que se usaba para los ensayos. Permite:

- 📋 **Cargar lotes** con su distribución de tratamientos (importando un archivo `.davi`).
- ✍️ **Digitar semana a semana**: mortalidad (por día), peso promedio y consumo de alimento por jaula.
- 📊 **Calcular indicadores** zootécnicos: peso, consumo, **FCR/conversión**, GDP, mortalidad, **coeficiente de variación del peso**, FEP, FCR ajustado.
- 📤 **Exportar** resultados a **Excel** y **PDF**, y respaldar/compartir lotes como archivos **`.davi`**.
- 🔒 Protección con **PIN**, papelera con retención de 15 días y edición de recepción.

Funciona **100 % offline**: los datos viven solo en el dispositivo (SQLite + SharedPreferences). No hay servidor ni nube.

## Stack

Kotlin · Jetpack Compose (Material 3) · MVVM + StateFlow · Room (SQLite) · Hilt (DI) · Navigation Compose · Coroutines · Apache POI (Excel) · Gson.

`compileSdk 35` · `minSdk 26 (Android 8.0)` · `targetSdk 35`.

## Estructura (resumen)

```
app/src/main/java/com/digitador/avicola/
├─ data/        Room (entidades, DAOs, migraciones) + repositorios + exportación
├─ domain/      Calculadora (indicadores), modelos, utilidades
├─ ui/          Pantallas Compose + ViewModels + navegación + tema
├─ di/          Módulo Hilt
└─ MainActivity.kt   Punto de entrada + recepción de archivos .davi
```

Jerarquía del dominio: **Partida (lote) → Galera (galpón) → Corral (tratamiento) → Parcela (repetición)**.

## Compilar

1. Abrir en **Android Studio**.
2. `Build > Make Project` o `./gradlew assembleDebug`.

> ⚠️ El build de *release* usa hoy la **keystore de debug**; para distribución real hay que configurar una keystore propia. Ver [`DOCUMENTACION.md`](DOCUMENTACION.md#12-compilar-firmar-y-publicar).

## Respaldo de datos

Los datos **solo** están en el teléfono (`allowBackup=false`). La única red de recuperación es **exportar `.davi` periódicamente** (Ajustes → Exportar) y guardarlos fuera del dispositivo.

## 📖 Documentación

- **Resumen / traspaso de mantenimiento:** [`DOCUMENTACION.md`](DOCUMENTACION.md) — visión general, build/release, recetas para cambios comunes y pendientes conocidos.
- **Documentación detallada** (carpeta [`docs/`](docs/), con diagramas):
  - [Arquitectura](docs/arquitectura.md) · [Modelo de datos](docs/modelo-datos.md) · [Cálculos](docs/calculos.md) · [Pantallas y navegación](docs/pantallas.md) · [Formato `.davi` y exportación](docs/davi-y-exportacion.md)
  - [Manual de usuario](docs/manual-usuario.md) (para operar la app en campo)

---

<div align="sub">

_Proyecto privado · uso interno de la granja experimental. La marca Cargill pertenece a sus respectivos titulares._

</div>
