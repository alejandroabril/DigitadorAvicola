# Prompt: generar un archivo `.davi` para Flock Tracker

Copiá este prompt en un asistente (Claude / ChatGPT) y completá los datos del lote al
final. El asistente devolverá el contenido JSON que debés guardar con extensión `.davi`.

---

## PROMPT

> Sos un generador de archivos `.davi` para la app avícola **Flock Tracker**.
> Un `.davi` es un **archivo de texto JSON** (con extensión `.davi`) que la app abre
> automáticamente al recibirlo (WhatsApp, Archivos, etc.). Existen **dos formatos**;
> generá el que se te pida y respondé **solo el JSON**, sin texto extra.
>
> ### Reglas de unidades y convenciones (OBLIGATORIAS)
> - **Alimento** (ingreso / saldo final / consumo ajustado): siempre en **GRAMOS**.
> - **`pesoInicio`** de una parcela = peso **total** de las aves al recibir, en gramos
>   = `nº aves × peso por ave`. Ej: 120 aves × 45 g = `5400`.
> - **`peso`** de un dato semanal = peso **promedio por ave** de esa semana, en gramos.
> - **`mort`** = lista de **7 enteros** (un valor por día, Lun→Dom). Usá `0` o `null`.
> - **IDs** (convención):
>   - Galera: `"G1"`, `"G2"`, …
>   - Cada galera usa 2 líneas físicas: **G1 → A, B** · **G2 → C, D** (G3 → E, F…).
>   - Parcela: `"G{galera}{línea}{NN}"` con NN de 2 dígitos. Ej: `G1A01`, `G1B07`, `G2C03`.
>   - Corral/tratamiento: `"G{galera}-T{n}"`. Ej: `G1-T1`, `G1-T2`.
> - **Nº de partida**: 4 dígitos (string), único por lote. Ej: `"0001"`.
> - **Alimentos válidos** (`refsActivas` y claves de `refs`): subconjunto de
>   `["BR1","BR2","BR3","BR4"]`.
> - Las claves del mapa `datosPorParcela[parcelaId]` son **strings** del nº de semana:
>   `"1"`, `"2"`, …
>
> ---
>
> ### FORMATO A — Distribución (crea un "lote pendiente")
> Solo describe **qué parcelas van en qué tratamiento**. La app crea el lote vacío y el
> usuario completa identificación y recepción después. Estructura:
>
> ```json
> {
>   "G1": {
>     "T1": ["G1A01", "G1A02", "G1A03"],
>     "T2": ["G1A04", "G1A05", "G1A06"]
>   },
>   "G2": {
>     "T1": ["G2C01", "G2C02"],
>     "T2": ["G2C03", "G2C04"]
>   }
> }
> ```
> - Nivel 1: galeras. Nivel 2: tratamientos. Valor: lista de IDs de parcela.
> - NO lleva pesos, aves, fechas ni nº de partida.
>
> ---
>
> ### FORMATO B — Backup completo (restaura todo el lote)
> Contiene la estructura + identificación + semanas + todos los datos digitados.
> La app lo detecta porque tiene las claves `version` y `datosPorParcela`.
>
> ```json
> {
>   "version": 1,
>   "exportedAt": 1700000000000,
>   "partida": {
>     "id": 0,
>     "numero": "0001",
>     "lote": "Nombre del lote",
>     "edad": "0",
>     "fechaInicio": "2026-04-20",
>     "usarGuia": true,
>     "finalizada": false,
>     "uid": "id-unico-del-lote",
>     "galeras": [
>       {
>         "id": "G1",
>         "nombre": "Galera 1",
>         "corrales": [
>           {
>             "id": "G1-T1",
>             "galeraId": "G1",
>             "parcelas": [
>               { "id": "G1A01", "corralId": "G1-T1", "inicio": 120, "pesoInicio": 5400.0 }
>             ]
>           }
>         ]
>       }
>     ]
>   },
>   "semanas": [
>     {
>       "numero": 1,
>       "fechaInicio": "2026-04-20",
>       "fechaFin": "2026-04-26",
>       "refsActivas": ["BR1"],
>       "cerrada": false
>     }
>   ],
>   "datosPorParcela": {
>     "G1A01": {
>       "1": {
>         "semanaNumero": 1,
>         "parcelaId": "G1A01",
>         "mort": [0, 0, 0, 0, 0, 1, 0],
>         "peso": 173.3,
>         "pesos": [173.3],
>         "consAjust": null,
>         "refs": {
>           "BR1": { "tipo": "BR1", "ingreso": 17561.0, "saldoFin": 0.0 }
>         }
>       }
>     }
>   }
> }
> ```
>
> #### Significado de cada campo del Formato B
> - **partida**
>   - `id`: dejar en `0` (la app asigna el suyo).
>   - `numero`: 4 dígitos, único.
>   - `lote`: nombre/etiqueta del lote.
>   - `edad`: edad inicial en semanas (string). `"0"` si recién ingresa.
>   - `fechaInicio`: `YYYY-MM-DD`.
>   - `usarGuia`: `true`.
>   - `finalizada`: `false` (true = lote cerrado, solo lectura).
>   - `uid`: identificador único del lote (texto). Si se reabre el mismo `.davi`,
>     la app detecta el duplicado por este `uid`.
>   - `galeras[].corrales[].parcelas[]`:
>     - `inicio`: nº de aves recibidas en la parcela.
>     - `pesoInicio`: peso total recibido en gramos (`inicio × g/ave`).
> - **semanas[]**: una por cada semana digitada. `fechaFin = fechaInicio + 6 días`.
>   `refsActivas`: alimentos usados esa semana. `cerrada`: `true` si la semana fue terminada.
> - **datosPorParcela**: mapa `parcelaId → { "nºsemana" → dato }`.
>   - `mort`: 7 enteros (muertes por día).
>   - `peso`: peso promedio g/ave de la semana.
>   - `pesos`: lista de muestreos (podés poner `[peso]`).
>   - `consAjust`: consumo ajustado en gramos, o `null`.
>   - `refs`: por cada alimento activo, `ingreso` y `saldoFin` en **gramos**.
>     - Consumo de la semana = `saldoAnterior + ingreso − saldoFin` (todo en gramos).
>     - Si `saldoFin > 0`, ese alimento queda activo la semana siguiente.
>
> #### Coherencia recomendada (para que los indicadores salgan realistas)
> - Curva de peso típica (g/ave): sem1≈180, sem2≈460, sem3≈900, sem4≈1450, sem5≈2050, sem6≈2650.
> - Consumo típico (g/ave/semana): sem1≈150, sem2≈400, sem3≈620, sem4≈850, sem5≈1050, sem6≈1250.
> - Con esos valores el **FCR acumulado** final queda ≈ **1.6** (correcto para engorde).
> - `ingreso` de cada parcela ≈ `consumo_g_por_ave × nº aves`.
>
> ---
>
> ### TU TAREA
> Generá un `.davi` en **Formato {A o B}** con estos datos:
> - Nº de partida: __________
> - Nombre del lote: __________
> - Fecha de inicio: __________
> - Galeras: ____  ·  Tratamientos por galera: ____  ·  Parcelas por tratamiento: ____
> - Aves por parcela: ____  ·  Peso al ingreso (g/ave): ____
> - (Formato B) Semanas a generar: ____
>
> Respondé **solo** con el JSON resultante.

---

## Cómo la app decide qué hacer con el archivo

| Contenido del `.davi` | La app… |
|---|---|
| Tiene `version` + `datosPorParcela` (Formato B) | **Restaura el lote completo** y abre el seguimiento |
| Estructura de galeras/tratamientos/parcelas (Formato A) | **Crea un lote pendiente** y abre el asistente |
| `uid` ya existe en la app | Pregunta: *"Este lote ya está cargado"* → Cancelar o **Guardar como copia** (numera `0001-C2`, `0001-C3`…) |

## Cómo guardarlo
1. Pedile el JSON al asistente.
2. Guardalo en un archivo con extensión **`.davi`** (ej. `Lote_0001.davi`).
3. Envialo por WhatsApp / Drive / cable y **abrilo** en el teléfono → la app lo reconoce.
