# DigitadorAvicola (Flock Tracker) — Documentación técnica y de traspaso

> Documento de **handover** para quien herede la administración/mantenimiento del proyecto.
> Cubre qué es la app, cómo está construida, cómo modificarla sin romper nada y dónde están los riesgos.
> Última actualización: 2026-06-25.

---

## Índice
1. [Qué es la app](#1-qué-es-la-app)
2. [Stack técnico y versiones](#2-stack-técnico-y-versiones)
3. [Estructura del proyecto](#3-estructura-del-proyecto)
4. [Modelo de dominio y glosario](#4-modelo-de-dominio-y-glosario)
5. [Base de datos (Room)](#5-base-de-datos-room)
6. [Unidades: kg vs gramos ⚠️](#6-unidades-kg-vs-gramos-)
7. [Lógica de cálculo (Calculadora)](#7-lógica-de-cálculo-calculadora)
8. [Pantallas y navegación](#8-pantallas-y-navegación)
9. [Formato .davi (importar/exportar)](#9-formato-davi-importarexportar)
10. [Exportación a Excel y PDF](#10-exportación-a-excel-y-pdf)
11. [Configuración, PIN y seguridad](#11-configuración-pin-y-seguridad)
12. [Compilar, firmar y publicar](#12-compilar-firmar-y-publicar)
13. [Recetas: cómo hacer cambios comunes](#13-recetas-cómo-hacer-cambios-comunes)
14. [Respaldo y recuperación de datos](#14-respaldo-y-recuperación-de-datos)
15. [Estado del código y pendientes](#15-estado-del-código-y-pendientes)
16. [Herramientas auxiliares](#16-herramientas-auxiliares)

---

## 1. Qué es la app

App Android para gestionar **ensayos de engorde de pollo (broiler)** en granja experimental. Reemplaza una planilla de Excel hecha a mano. Permite:
- Cargar un lote (partida) con su distribución de tratamientos.
- Digitar semana a semana: **mortalidad** (por día), **peso** promedio y **consumo de alimento** por jaula.
- Calcular indicadores zootécnicos (peso, consumo, **FCR/conversión**, GDP, mortalidad, **CV del peso**, etc.).
- Exportar resultados a **Excel** y **PDF**, y respaldar/compartir lotes como archivos **`.davi`**.

Funciona **100% offline**; los datos viven solo en el dispositivo (SQLite + SharedPreferences). No hay backend ni nube.

---

## 2. Stack técnico y versiones

| Componente | Versión | Para qué |
|---|---|---|
| Android Gradle Plugin | 9.2.1 | build |
| Kotlin | 2.3.21 | lenguaje |
| KSP | 2.3.7 | procesador anotaciones (Room/Hilt) |
| Jetpack Compose BOM | 2024.09.00 | UI declarativa (Material 3) |
| Room | 2.8.4 | base de datos SQLite |
| Hilt (Dagger) | 2.59.2 | inyección de dependencias |
| Navigation Compose | 2.9.8 | navegación type-safe |
| Coroutines | 1.8.1 | concurrencia |
| Gson | 2.10.1 | JSON (.davi, converters de Room) |
| Apache POI | 5.2.5 | generación de Excel (.xlsx) |
| DataStore | 1.1.1 | (declarado; el PIN/config usa SharedPreferences) |

**SDK:** `compileSdk=35`, `minSdk=26` (Android 8.0), `targetSdk=35`.
**App:** `applicationId="com.digitador.avicola"` · `versionCode=1` · `versionName="1.0.0"` · build debug con sufijo `.debug`.
Versiones centralizadas en [`gradle/libs.versions.toml`](gradle/libs.versions.toml).

---

## 3. Estructura del proyecto

Paquete raíz: `com.digitador.avicola`

```
MainActivity.kt          Punto de entrada; recibe .davi por intent ACTION_VIEW; splash.
DigitadorApp.kt          Application con @HiltAndroidApp.
di/AppModule.kt          Provee DigitadorDatabase + DAOs (Hilt, Singleton).
data/
  db/
    DigitadorDatabase.kt  @Database (v12) + migraciones 7→8 … 11→12.
    entity/Entities.kt    Entidades Room + Converters (listas ↔ JSON).
    dao/PartidaDao.kt     CRUD de partida/galera/corral/parcela/papelera/borradores.
    dao/SemanaDao.kt      CRUD de semana/dato_parcela/ref_alimento.
  repository/
    DigitadorRepository.kt  Orquesta DAOs; mantiene appState (StateFlow) + transacciones.
    ConfigRepository.kt     SharedPreferences: PIN (hasheado), modo, refs excluidas, etc.
    ExportService.kt        Excel (POI), PDF (PdfDocument), import/export .davi.
domain/
  Calculadora.kt         TODA la matemática de indicadores (única fuente de verdad).
  Models.kt              Modelos de dominio (Partida, Galera, Corral, Parcela, Semana,
                         DatoParcela, RefAlimento, MetricasCorral, CvPeso, OpcionesExport…).
  DateUtils.kt           Utilidades de fecha.
ui/
  navigation/NavGraph.kt Rutas (sealed interface Screen) + NavHost.
  screen/<pantalla>/     Cada pantalla = XxxScreen.kt (Compose) + XxxViewModel.kt (MVVM).
  components/            PinPad.kt (diálogo de PIN), Components.kt (UI compartida).
  theme/                 Colores y tema Material 3.
```

**Patrón:** MVVM. Cada pantalla tiene un `ViewModel` (`@HiltViewModel`) que expone un `StateFlow<XxxUiState>`. Los ViewModels hablan con `DigitadorRepository` / `ConfigRepository` / `ExportService`. La UI es Compose puro y solo observa el estado.

---

## 4. Modelo de dominio y glosario

Jerarquía física del ensayo:

```
Partida (lote)
└─ Galera (galpón físico: G1, G2)         ← "Bloque" en algunas planillas
   └─ Corral (= un TRATAMIENTO: K1, K2…)  ← agrupa las repeticiones de ese tratamiento
      └─ Parcela (= una REPETICIÓN/jaula: G1A03, G2C01…)
```

- **Partida / Lote:** un ensayo completo. Tiene `uid` (UUID inmutable) que evita duplicados al importar.
- **Galera:** galpón físico. Por convención G1 usa líneas A/B y G2 usa líneas C/D — **esto NO lo valida el código**, es solo convención de nombres de parcela.
- **Corral:** dentro del modelo de la app, un corral **es un tratamiento** (su label se obtiene de `id.substringAfterLast("-")`, p.ej. `G1-K1` → `K1`).
- **Parcela:** la unidad experimental (jaula). Tiene `inicio` (aves recibidas) y `pesoInicio` (peso total al ingreso, en gramos).
- **Semana:** período de digitación. `refsActivas` = lista de referencias de alimento activas esa semana (BR1, BR2…). `cerrada` = terminada/solo lectura.
- **DatoParcela:** datos de una parcela en una semana → `mort` (lista de 7 días), `peso` (promedio g/ave), `refs` (por tipo de alimento), `consAjust` (consumo ajustado manual, opcional).
- **Referencia de alimento (BR1, BR2…):** una ración. `ingreso` = kg entregados; `saldoFin` = kg sobrantes al final de la semana.
- **Repetición:** cada parcela de un tratamiento. En un diseño con T tratamientos × R repeticiones hay T×R parcelas.

---

## 5. Base de datos (Room)

Archivo físico: `digitador_avicola.db` (almacenamiento interno de la app).
Versión actual: **12**. Esquemas exportados a [`app/schemas/`](app/schemas) (desde la v7).

**Entidades** (`data/db/entity/Entities.kt`):
| Tabla | PK | Notas |
|---|---|---|
| `partida` | `id` auto | + `uid`, `finalizada`, `eliminadaEn` (soft delete). `lineaGenetica` = columna **obsoleta inerte** (no usar). |
| `galera` | `id,partidaId` | FK→partida CASCADE |
| `corral` | `id,partidaId` | FK→galera CASCADE; el label tras `-` es el tratamiento |
| `parcela` | `id,partidaId` | FK→corral CASCADE; `inicio`, `pesoInicio` |
| `semana` | `partidaId,numero` | FK→partida CASCADE; `refsActivas` (lista→JSON), `cerrada` |
| `dato_parcela` | `partidaId,semanaNumero,parcelaId` | FK→parcela CASCADE; `mort` (7), `peso`, `consAjust` |
| `ref_alimento` | `partidaId,semanaNumero,parcelaId,tipo` | FK→parcela CASCADE; `ingreso`, `saldoFin` |
| `borrador_lote` | `id` auto | borradores en JSON |

Las listas (`mort`, `refsActivas`, `pesos`) se guardan como **JSON** vía `Converters` (Gson).

### Migraciones ⚠️ LO MÁS IMPORTANTE DE MANTENER
En [`DigitadorDatabase.kt`](app/src/main/java/com/digitador/avicola/data/db/DigitadorDatabase.kt):
```kotlin
.addMigrations(MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
.fallbackToDestructiveMigrationFrom(1, 2, 3, 4, 5, 6)
```
- De la **v7 en adelante los datos se preservan** con migraciones reales.
- Solo se recrea (con pérdida) viniendo de versiones 1–6 (pre-esquema, inexistentes en campo).
- **Regla de oro:** si cambiás el esquema (agregar/quitar columna o tabla) **DEBÉS subir `version` y escribir una nueva `MIGRATION_X_Y`**. Si no, la app **fallará al abrir** (ya no borra en silencio). Esto es a propósito: protege los datos del usuario.

### Repositorio
`DigitadorRepository` (Singleton) es el centro. Mantiene `appState: StateFlow<AppState>` (lote activo en memoria) y `currentPartidaId`. Escrituras compuestas (guardar lote, crear pendiente, borrar definitivo) van en **`db.withTransaction { }}` (atómicas)**. Los `save*` parchean `appState` en memoria para no recargar todo en cada tecla.

---

## 6. Unidades: kg vs gramos ⚠️

**Convención del código (verificada):**
- **Alimento** (`ingreso`, `saldoFin`, `consAjust`): se digita y almacena en **KILOGRAMOS**. La `Calculadora` multiplica ×1000 (`GRAMOS_POR_KG`) para obtener consumo en **g/ave**.
- **Pesos** (`pesoInicio`, `peso` semanal): en **GRAMOS**.

> ⚠️ **Inconsistencia documental conocida:** `PROMPT_DAVI.md` y `gen_davi.py` (generadores de ejemplo) dicen que el alimento va en **gramos**. Eso está **desactualizado respecto al código**. Un `.davi` hecho siguiendo esa doc daría consumo/FCR inflados ×1000. Los `.davi` que **exporta la app** y los armados desde la planilla (en kg) funcionan bien. Si se unifica el criterio algún día, actualizar ambos lados.

---

## 7. Lógica de cálculo (Calculadora)

Toda la matemática está en [`domain/Calculadora.kt`](app/src/main/java/com/digitador/avicola/domain/Calculadora.kt). `computeMetricasCorral(...)` calcula, para un grupo de parcelas (un tratamiento, una galera o todo el lote) en una semana:

| Indicador | Cómo se calcula |
|---|---|
| **Saldo (aves vivas)** | `inicio − Σ mortalidad` hasta la semana (nunca negativo). |
| **Peso prom (g/ave)** | promedio **ponderado por saldo** de los pesos de las parcelas. |
| **Consumo semanal (g/ave)** | `Σ_refActiva[(saldoFin_previo + ingreso) − saldoFin]` (kg) × 1000 / saldo. Si hay `consAjust` (≥0) **reemplaza** ese cálculo. |
| **Consumo acumulado** | suma de los consumos semanales por ave desde la semana 1. |
| **FCR semanal** (conversión) | promedio **ponderado por saldo** de `consumoGave / ganancia` por parcela. |
| **FCR acumulado** | `consumoAcum / pesoProm`. |
| **GDP semanal** | `(ganancia / 7)` ponderado por saldo. |
| **GDP lineal** | `pesoProm / (semana × 7)`. |
| **Mortalidad acum %** | `Σ mort acumulada / Σ inicio`. |
| **CV % del peso** | desv. estándar **muestral (n−1)** ÷ media de los pesos prom de las jaulas (sin ponderar). Ver `cvPesoDeParcelas`. |
| **Ratio** (solo semana 1) | `pesoProm / pesoPrevio(recepción)`. |
| **FEP** (opcional) | `(viabilidad × peso_kg) / (edad_días × FCRacum) × 100`. |
| **FCR ajustado** (sem ≥5, opcional) | `FCRacum + (2500 − pesoProm) / 3200`. |

**Jaulas suspendidas.** Una parcela con `suspendida = true` se sigue digitando —el dato de
campo se conserva— pero no entra en ningún indicador: se filtra en los **seis** puntos de
`Calculadora` (`computeMetricasDeParcelas`, `cvPesoDeParcelas`, `validarSemana` y las tres
de progreso). La suspensión es TOTAL, vale para todas las semanas: si valiera solo desde
una, las series acumuladas de un tratamiento mezclarían composiciones distintas. Sale
marcada en el Excel (columna `SUSPENDIDA`) y en una nota del PDF con su motivo — una
exclusión invisible no se puede defender ante quien revise el ensayo.

> ✅ **Un solo motor.** La unidad de cálculo es la jaula: `Calculadora.computeMetricasParcela`
> devuelve un `MetricasParcela` con todos los indicadores. Si hacen falta varias semanas
> de la misma jaula (el Excel escribe una fila por jaula y semana), se usa
> `computeSerieParcela`, que las resuelve todas en un recorrido: repetir la llamada por
> semana rehace el arrastre desde la semana 1 y vuelve el cálculo cuadrático. `computeMetricasCorral` no
> recalcula nada, solo pondera esas métricas por saldo de aves, y el Excel escribe esas
> mismas métricas fila a fila. Para cambiar una fórmula se toca **un solo sitio**.
> `CalculadoraEquivalenciaTest` compara el motor contra una copia del código anterior
> para que ningún cambio mueva los números sin querer.

`validarSemana` / `calcProgreso` calculan completitud para habilitar el cierre de semana y la barra de progreso.

---

## 8. Pantallas y navegación

Rutas type-safe en [`NavGraph.kt`](app/src/main/java/com/digitador/avicola/ui/navigation/NavGraph.kt) (`sealed interface Screen`). Destino inicial: **History**.

| Ruta | Pantalla | Función |
|---|---|---|
| `History` | HistoryScreen | Lista de lotes (activos/pendientes). Acceso a papelera y config. **Long-press** en un lote → editar recepción (pide PIN). |
| `Setup(startStep)` | SetupScreen | Asistente: Identificación → galeras → **Recepción** (aves + peso inicial). `startStep=2` entra directo a editar recepción de un lote activo. |
| `Main(semana)` | SemanaScreen | Barra de semanas + digitación por galera. Vista **por línea** o **por tratamiento**. Botón "Terminar semana" (cierra/bloquea). |
| `Ingreso(...)` | DigitacionScreen | Digitar **mortalidad / peso / alimento** por parcela. **Autosave** con debounce. |
| `Resumen(semana)` | ResumenScreen | Indicadores por tratamiento → galera → global. Exportar **Excel/PDF**. |
| `Ajustes` | AjustesScreen | Cerrar (finalizar) lote, exportar **.davi**, compartir APK. |
| `Config` | ConfigScreen | Cambiar **PIN**, activar PIN, modo por tratamiento. |
| `Papelera` | PapeleraScreen | Lotes borrados (retención **15 días**), restaurar o borrar definitivo. |

Flujo típico: recibir/abrir `.davi` → completar Setup → digitar cada semana en Main/Digitación → revisar y exportar en Resumen → al final, finalizar en Ajustes.

---

## 9. Formato .davi (importar/exportar)

`.davi` = archivo de texto **JSON**. La app lo abre por intent `ACTION_VIEW` (MIME `application/octet-stream`) — ver `MainActivity.handleIncoming`. Tres formatos (detección en `ExportService.esBackupCompleto` / `MainActivity.parseDistribucion`):

1. **Respaldo completo** (lo que exporta Ajustes → Exportar):
   `{ version, exportedAt, partida{…}, semanas[…], datosPorParcela{…} }`
   → restaura el lote entero. Si el `uid` ya existe, ofrece **reemplazar o importar como copia**.
2. **Experimental** (anidado): `{ partida{…}, semanas[ {…, datos:{galera:{corral:{parcela:{…}}}}} ] }`.
3. **Distribución** (solo el reparto, sin datos):
   `{ "G1": { "K1": ["G1A01","G1A02"], "K2": [...] }, "G2": {...} }`
   → crea un lote **pendiente** para completar en Setup.

Notas:
- La importación es **defensiva** (JSON inválido → Toast de error, no crashea) y **se limpia el lote a medias** si falla a mitad.
- El número de partida **no** viaja en el formato de distribución pura (solo el reparto).

---

## 10. Exportación a Excel y PDF

En [`ExportService.kt`](app/src/main/java/com/digitador/avicola/data/repository/ExportService.kt):
- **Excel (.xlsx, Apache POI):** hoja "Estadística" (una fila por parcela/semana, formateada como tabla). ⚠️ `autoSizeColumn` **NO funciona en Android** (requiere AWT) → anchos fijos.
- **PDF (`android.graphics.pdf.PdfDocument`):** "Análisis de la semana" transpuesto (KPIs en filas, tratamientos en columnas), una tabla por galera y, con más de una galera, una **tabla consolidada** que junta cada tratamiento en todas las galeras y cierra con una columna del lote entero + **hoja aparte con el Coeficiente de Variación del peso** (por tratamiento, por galera y global). Lleva el **logo Cargill** (recurso `R.drawable.cargill_logo`) arriba a la derecha de cada página.
- Los archivos se generan en `cacheDir/exports` (se autolimpia >24 h) y se comparten con `FileProvider` + `shareFile`.

> Si se quita el recurso `cargill_logo.png`, el PDF **no compila** (referencia `R.drawable.cargill_logo`). Mantenerlo en `app/src/main/res/drawable/`.

---

## 11. Configuración, PIN y seguridad

[`ConfigRepository.kt`](app/src/main/java/com/digitador/avicola/data/repository/ConfigRepository.kt) (SharedPreferences `config_app`):
- **PIN** (protege papelera, edición de recepción y desbloqueo de semanas): se guarda como **hash SHA-256 salado**, nunca en texto plano. Default inicial `0000` (se re-hashea al primer uso). El diálogo `PinDialog` verifica vía callback `onVerify`, sin recibir el PIN real.
- `pinHabilitado`, `modoPorTratamiento`, última semana vista por lote, y **referencias de alimento excluidas** del cálculo (por lote y semana).
- `android:allowBackup="false"` → la BD y el PIN **no** se respaldan a la nube/adb.

---

## 12. Compilar, firmar y publicar

1. Abrir el proyecto en **Android Studio** (no requiere SDK extra fuera del estándar de Android).
2. `Build > Make Project` o `./gradlew assembleDebug` / `assembleRelease`.
3. Al cambiar el esquema de Room, **el rebuild regenera** `app/schemas/<n>.json` (úsalo para escribir migraciones).

### Subir la versión antes de repartir

**Al tocar el esquema de la base hay que subir `versionCode`.** No es cosmético: sin eso,
Android deja instalar un APK viejo encima de uno nuevo, y una app vieja sobre datos
nuevos **no abre**. Comprobado abriendo con la app una base marcada con una versión
superior:

```
IllegalStateException: A migration from 99 to 12 was required but not found
```

No hay camino de bajada ni fallback destructivo para ese caso —y es lo correcto: entre
reventar y borrar los lotes del digitador, revienta—. La defensa es que el `versionCode`
crezca, porque Android rechaza instalar uno menor sobre uno mayor.

**Compartir app (APK)** copia el APK instalado, así que siempre reparte lo que hay en ese
equipo. El archivo sale como `FlockTracker_v<versionName>(<versionCode>).apk`. Si el build
es de depuración, el nombre lleva `_DEPURACION-NO-DISTRIBUIR`: ese APK se instala como una
app aparte (`applicationId` con sufijo `.debug`) y con sus propios datos, así que repartirlo
por error deja al digitador con dos apps y los lotes en la que no toca.

### Firmar el release

El build ya está preparado: `app/build.gradle.kts` lee `keystore.properties` de la raíz
del proyecto (ignorado por git, porque **el repo es público**). Si ese archivo no existe,
release sigue firmando con la clave de DEBUG y el build avisa por consola — así nadie
reparte por descuido un APK que no sirve para distribuir.

Para firmar de verdad, una sola vez:

```bash
keytool -genkeypair -v -keystore digitador-avicola.jks \
  -alias digitador -keyalg RSA -keysize 4096 -validity 10000
```

y crear `keystore.properties` con `storeFile`, `storePassword`, `keyAlias` y `keyPassword`.

> 🔑 **Guardá el `.jks` y sus contraseñas fuera del equipo** (gestor de contraseñas +
> copia en otro sitio). Android exige que una actualización esté firmada con la MISMA
> clave que la versión instalada. Si la clave se pierde, el APK nuevo ya no se puede
> instalar encima: hay que desinstalar, y como los datos viven solo en el teléfono
> (`allowBackup=false`), **eso borra los lotes digitados en campo**.

La clave de debug no vale para distribuir: es local a cada máquina (si se pierde, mismo
problema de arriba), Play no la acepta, y es pública —contraseña `android`, alias
`androiddebugkey`— así que cualquiera podría firmar un APK con la misma identidad.

Distribución actual: se comparte el **APK** directamente (Ajustes → compartir APK, o el `.apk` generado).

---

## 13. Recetas: cómo hacer cambios comunes

**Agregar una columna a la BD (lo más delicado):**
1. Editar la `@Entity` en `Entities.kt`.
2. Subir `version` en `@Database` (`DigitadorDatabase.kt`).
3. Escribir `MIGRATION_X_Y` con `ALTER TABLE … ADD COLUMN … NOT NULL DEFAULT …` y registrarla en `.addMigrations(...)`. (Para columnas NOT NULL, SQLite exige un DEFAULT; Room lo ignora porque la entidad no declara `defaultValue`.)
4. Rebuild → verificar el nuevo `schemas/<version>.json`.
5. **Nunca** volver a `fallbackToDestructiveMigration()` sin `From(...)`.

**Agregar/cambiar un indicador:** se edita **solo** en `Calculadora`; el Excel y el PDF consumen ese mismo motor (ver §7).

**Agregar una pantalla:** nueva entrada en `sealed interface Screen` + `composable<...>` en `NavGraph` + `XxxScreen.kt` + `XxxViewModel.kt` (`@HiltViewModel`).

**Cambiar reglas del PIN / config:** `ConfigRepository`.

---

## 14. Respaldo y recuperación de datos

- Los datos viven **solo en el dispositivo** (SQLite interno). No hay copia automática (`allowBackup=false`).
- **Respaldo manual:** Ajustes → **Exportar** genera un `.davi` con todo el lote. Recomendación firme: **exportar `.davi` periódicamente** (p. ej. cada semana) y guardarlos fuera del teléfono (WhatsApp, Drive, PC). Es la única red de recuperación si el dispositivo se pierde o se reinstala.
- Para **mover un lote a otro teléfono:** exportar `.davi` y abrirlo en el otro dispositivo.
- La **papelera** retiene lotes borrados 15 días antes de purgarlos.

---

## 15. Estado del código y pendientes

Tras una auditoría completa (2026-06) se corrigieron los puntos críticos: **autosave** en digitación, **transacciones** atómicas, **migraciones reales** de Room, **PIN hasheado**, `allowBackup=false`, manejo de errores en importación, limpieza de cache, y varios más.

En 2026-09 se **unificó el motor de cálculo** (ver §7): `ExportService` ya no reimplementa
ninguna fórmula, y `app/src/test/` cubre el motor con tests de equivalencia contra el
código anterior. En el mismo paso se arregló el build de *release*, que estaba roto.

**Pendientes conocidos (menor riesgo):**
- **Rendimiento:** resuelto en pantallas y export. Queda `exportResumen` (hoja deshabilitada), que sigue pidiendo el corral semana por semana; si se reactiva, necesita una serie por corral como la de `computeSerieParcela`.
- **Generar la keystore propia** de release: el build ya la lee de `keystore.properties`, solo falta crear la clave (ver §12).
- **Accesibilidad — un objetivo táctil pendiente:** el ícono "!" de unidades en
  `DigitacionScreen` (`UnidadGramosTip`) es un `IconButton` encogido a 28 dp dentro de una
  columna de 40 dp de ancho. Reservar los 48 dp ahí desborda la columna de la tabla de
  digitación, así que hay que rediseñar ese hueco con el teléfono delante.
  Los chips de 32 dp **no** son un defecto: es la altura que define Material 3
  (`FilterChipTokens.ContainerHeight`). Y Compose ya expande por su cuenta el área de
  *toque* de los pulsables pequeños (`NodeCoordinator.isInExpandedTouchBounds`); lo que
  `minimumInteractiveComponentSize()` aporta es reservar el espacio en el layout, que es
  lo que miden las auditorías de accesibilidad.
- Las preferencias de lotes purgados **antes** de este cambio siguen en el archivo:
  se limpian al purgar de ahora en adelante, pero las ya huérfanas no se barren. Si
  molestan, haría falta un barrido contra los uid vivos — con cuidado, porque borrar de
  más quita las referencias excluidas y eso **cambia los indicadores** de un lote.
- Ampliar los tests a la UI (los ViewModels y las pantallas siguen sin cobertura; haría
  falta `compose-ui-test`).
- En la hoja por galera, la columna **"Consumo(g)"** trae kilogramos, no gramos: el
  encabezado miente. Esa hoja está deshabilitada hoy, pero hay que corregirlo antes de
  reactivarla.

---

### Tests

`./gradlew testDebugUnitTest` — todo corre en la JVM, sin emulador ni dispositivo.

| Suite | Qué cubre |
|---|---|
| `CalculadoraEquivalenciaTest`, `ProgresoTest` | El motor de cálculo, en JVM pura. Comparan contra una copia del código anterior al refactor. |
| `ClavesDeLoteTest` | Qué preferencias se borran al purgar un lote (JVM pura). |
| `DigitadorRepositorioTest`, `ImportarDaviTest` | Repositorio e importación `.davi` contra una base Room **real en memoria**, con Robolectric. |

Robolectric necesita **4.17 o superior**: las versiones anteriores no entienden el
bytecode del JDK 25 que trae Android Studio y fallan con
`Unsupported class file major version 69`.

---

## 16. Herramientas auxiliares

Estas herramientas se mantienen **fuera del repo** (en el disco local del desarrollador); el repositorio contiene únicamente la app:
- **`PROMPT_DAVI.md`**: guía para generar `.davi`. ⚠️ desactualizada en unidades (ver §6).
- **`gen_davi.py`**: generador de `.davi` de ejemplo (mismo aviso de unidades).
- **`distribuidor_davi.html`**: herramienta web local para **analizar** `.davi` y **crear distribuciones** nuevas (con mapa visual y descarga). Replica la `Calculadora` en JavaScript.
- **`app/schemas/`** (sí versionado): esquemas de Room — base para escribir migraciones.

---

### Contacto / contexto
Mantenedor saliente: ver historial de Git. Dominio: granja experimental avícola (broiler). Ante dudas de negocio (tratamientos, raciones, fórmulas), consultar al responsable técnico del ensayo.
