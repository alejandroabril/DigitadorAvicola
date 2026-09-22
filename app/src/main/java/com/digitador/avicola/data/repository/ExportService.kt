package com.digitador.avicola.data.repository

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.digitador.avicola.R
import com.digitador.avicola.domain.*
import java.util.Locale
import org.apache.poi.ss.usermodel.*
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.xssf.usermodel.XSSFSheet
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTTable
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTTableColumn
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTTableColumns
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTTableStyleInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExportService @Inject constructor(
    private val repo: DigitadorRepository,
    private val config: ConfigRepository
) {

    suspend fun exportarExcel(
        context: Context,
        opciones: OpcionesExport = OpcionesExport()
    ): File = withContext(Dispatchers.IO) {
        val state = repo.cargarEstado()
        val wb    = XSSFWorkbook()

        try {
            // Estilos creados UNA sola vez y reutilizados en todas las hojas.
            val styles = createStyles(wb)

            // A pedido: el Excel exportado contiene SOLO la hoja "Estadística".
            // Las hojas por galera y la de Resumen quedan deshabilitadas (las funciones
            // exportGalera/exportResumen se conservan por si se reactivan).
            exportEstadistica(wb, state, styles, opciones)

            val dir  = exportsDir(context)
            // Nombre identificable: Lote_{partida}_{fecha}.xlsx
            val numero = state.partida?.numero?.filter { it.isLetterOrDigit() || it == '-' }?.ifBlank { "lote" } ?: "lote"
            val fecha = java.time.LocalDate.now().toString()
            val file = File(dir, "Lote_${numero}_$fecha.xlsx")
            FileOutputStream(file).use { wb.write(it) }
            wb.close()
            file
        } catch (e: Exception) {
            wb.close()
            throw e
        }
    }

    /**
     * Genera un PDF del "Análisis de la semana": por cada galera, una tabla con
     * TODOS los tratamientos de [semNum] y CADA indicador medido (saldo, peso,
     * consumo sem/acum, FCR sem/acum, GDP sem/lineal, mortalidad acum). Si
     * [opciones] lo pide, agrega FEP, FCR ajustado y CV%; en la semana 1 agrega Ratio.
     * Cada fila usa el mismo cálculo que la pantalla (Calculadora.computeMetricasCorral).
     */
    suspend fun exportarPdfResumen(
        context: Context,
        semNum: Int,
        opciones: OpcionesExport = OpcionesExport()
    ): File = withContext(Dispatchers.IO) {
        val state = repo.cargarEstado()
        val partida = state.partida ?: error("No hay partida activa")
        val semana = state.getSemana(semNum)

        // Paleta (ARGB) — Color de POI también existe, por eso el alias AndroidColor.
        val green = AndroidColor.rgb(0x1B, 0x6E, 0x37)
        val ink = AndroidColor.rgb(0x15, 0x26, 0x1C)
        val gray = AndroidColor.rgb(0x8A, 0x98, 0x8F)
        val rowAlt = AndroidColor.rgb(0xF1, 0xF8, 0xF3)
        val lineCol = AndroidColor.rgb(0xDD, 0xE4, 0xDE)
        val bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

        val pTitle = Paint().apply { color = ink; textSize = 16f; typeface = bold; isAntiAlias = true }
        val pSub = Paint().apply { color = gray; textSize = 9f; isAntiAlias = true }
        val pGalera = Paint().apply { color = green; textSize = 11f; typeface = bold; isAntiAlias = true }
        val pHead = Paint().apply { color = AndroidColor.WHITE; textSize = 7.5f; typeface = bold; isAntiAlias = true }
        val pCell = Paint().apply { color = ink; textSize = 8f; isAntiAlias = true }
        val pCellB = Paint().apply { color = ink; textSize = 8f; typeface = bold; isAntiAlias = true }
        val pFill = Paint()
        val pLine = Paint().apply { color = lineCol; strokeWidth = 0.5f }

        // KPIs = FILAS (los tratamientos van en COLUMNAS). Cada KPI extrae su valor
        // de las métricas de un tratamiento.
        fun f(v: Double?, pat: String, mult: Double = 1.0) =
            if (v == null) "—" else String.format(Locale.US, pat, v * mult)
        fun d1(v: Double?) = if (v == null) "—" else String.format(Locale.US, "%.1f", v)

        data class Kpi(val label: String, val get: (MetricasCorral?) -> String)
        val kpis = buildList {
            add(Kpi("Saldo (aves)") { m -> m?.saldo?.toString() ?: "—" })
            add(Kpi("Peso (g)") { m -> d1(m?.promPeso) })
            add(Kpi("Consumo sem (g)") { m -> d1(m?.consumoGave) })
            add(Kpi("Consumo acum (g)") { m -> d1(m?.consumoAcum) })
            add(Kpi("FCR semanal") { m -> f(m?.fcrSem, "%.3f") })
            add(Kpi("FCR acumulado") { m -> f(m?.fcrAcum, "%.3f") })
            add(Kpi("GDP sem (g/día)") { m -> d1(m?.gdpSem) })
            add(Kpi("GDP lineal (g/día)") { m -> d1(m?.gdpLineal) })
            add(Kpi("Mortalidad %") { m -> if (m == null) "—" else String.format(Locale.US, "%.1f%%", m.mortAcumPct * 100) })
            if (semNum == 1) add(Kpi("Ratio crecimiento") { m -> f(m?.ratio, "%.2f") })
            if (opciones.incluirFep) add(Kpi("FEP") { m -> f(m?.fep, "%.0f") })
            if (opciones.incluirFcrAjustado) add(Kpi("FCR ajustado") { m -> f(m?.fcrAdj, "%.3f") })
            // El CV del peso ya no va aquí: tiene su propia sección dedicada al final
            // del PDF (por tratamiento, por galera y global).
        }

        // A4 horizontal (puntos a 72 dpi).
        val pageW = 842; val pageH = 595
        val margin = 30f
        val tableW = pageW - margin * 2
        val labelW = 150f   // primera columna = nombres de los indicadores

        // Logo Cargill (esquina superior derecha). Si el recurso no existe, se omite
        // sin romper la generación del PDF. La imagen es cuadrada con fondo blanco,
        // así que el espacio en blanco se funde con la hoja.
        val logo: Bitmap? = try {
            BitmapFactory.decodeResource(context.resources, R.drawable.cargill_logo)
        } catch (e: Exception) { null }
        val logoSize = 78f

        val pdf = PdfDocument()
        var pageNo = 1
        var page = pdf.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
        var canvas = page.canvas
        var y = margin

        fun drawLogo() {
            val bmp = logo ?: return
            val left = pageW - margin - logoSize
            val top = 8f
            canvas.drawBitmap(bmp, null, RectF(left, top, left + logoSize, top + logoSize), null)
        }

        fun nuevaPagina() {
            canvas.drawText("Flock Tracker", margin, pageH - 14f, pSub)
            pdf.finishPage(page)
            pageNo += 1
            page = pdf.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
            canvas = page.canvas
            y = margin
            drawLogo()
        }
        fun asegurar(alto: Float): Boolean {
            if (y + alto > pageH - margin) { nuevaPagina(); return true }
            return false
        }

        val headH = 20f
        val rowH = 17f

        // Encabezado del documento.
        drawLogo()
        canvas.drawText("Análisis de la semana ${semNum.toString().padStart(2, '0')}", margin, y + 14f, pTitle)
        y += 24f
        val fechaSem = semana?.fechaInicio ?: ""
        canvas.drawText(
            "Partida ${partida.numero}    ·    Lote ${partida.lote}" +
                (if (fechaSem.isNotBlank()) "    ·    Inicio de semana: $fechaSem" else ""),
            margin, y + 10f, pSub
        )
        y += 24f

        partida.galeras.forEach { galera ->
            // Tratamientos (COLUMNAS), ordenados por número (K1..K5 / K7..K11).
            val corrales = galera.corrales.sortedBy { c ->
                c.id.substringAfterLast("-").filter { it.isDigit() }.toIntOrNull() ?: 0
            }
            if (corrales.isEmpty()) return@forEach
            val labels = corrales.map { it.id.substringAfterLast("-") }
            val exclMapPdf = (1..semNum).associateWith { config.refsExcluidas(partida.uid, it) }
            val metricas = corrales.associate { c ->
                c.id.substringAfterLast("-") to (
                    if (semana != null)
                        Calculadora.computeMetricasCorral(c, semNum, semana, state.semanas, state.datosPorParcela, exclMapPdf)
                    else null
                )
            }
            val colW = (tableW - labelW) / labels.size
            fun colCenter(i: Int) = margin + labelW + colW * i + colW / 2f

            fun drawHeaderTrat() {
                pFill.color = green
                canvas.drawRect(margin, y, margin + tableW, y + headH, pFill)
                pHead.textAlign = Paint.Align.LEFT
                canvas.drawText("INDICADOR", margin + 6f, y + 13f, pHead)
                pHead.textAlign = Paint.Align.CENTER
                labels.forEachIndexed { i, lbl -> canvas.drawText(lbl, colCenter(i), y + 13f, pHead) }
                pHead.textAlign = Paint.Align.LEFT
                y += headH
            }

            asegurar(18f + headH + rowH)
            canvas.drawText(galera.nombre, margin, y + 10f, pGalera)
            y += 16f
            drawHeaderTrat()

            kpis.forEachIndexed { idx, kpi ->
                if (asegurar(rowH)) drawHeaderTrat()
                if (idx % 2 == 1) {
                    pFill.color = rowAlt
                    canvas.drawRect(margin, y, margin + tableW, y + rowH, pFill)
                }
                // Nombre del indicador (izquierda, en negrita)
                pCellB.textAlign = Paint.Align.LEFT
                canvas.drawText(kpi.label, margin + 6f, y + 12f, pCellB)
                // Valor por tratamiento (centrado en su columna)
                pCell.textAlign = Paint.Align.CENTER
                labels.forEachIndexed { i, lbl -> canvas.drawText(kpi.get(metricas[lbl]), colCenter(i), y + 12f, pCell) }
                pCell.textAlign = Paint.Align.LEFT
                pCellB.textAlign = Paint.Align.LEFT
                // Líneas: horizontal inferior + separadores verticales de columnas.
                canvas.drawLine(margin, y + rowH, margin + tableW, y + rowH, pLine)
                canvas.drawLine(margin + labelW, y, margin + labelW, y + rowH, pLine)
                for (i in 1 until labels.size) {
                    val x = margin + labelW + colW * i
                    canvas.drawLine(x, y, x, y + rowH, pLine)
                }
                y += rowH
            }
            y += 16f
        }

        // ── Tabla consolidada: todas las galeras juntas ─────────────────────
        // La galera es el BLOQUE del ensayo: el K1 de G1 y el K1 de G2 son el mismo
        // tratamiento. Esta tabla agrupa por label de tratamiento sumando las jaulas de
        // todas las galeras, y cierra con una columna del lote entero. Con una sola
        // galera no aporta nada sobre su propia tabla, así que se omite.
        if (partida.galeras.size > 1 && semana != null) {
            val corralesTodos = partida.galeras.flatMap { it.corrales }.filter { it.parcelas.isNotEmpty() }
            val labelsCons = corralesTodos
                .map { it.id.substringAfterLast("-") }
                .distinct()
                .sortedBy { lbl -> lbl.filter { it.isDigit() }.toIntOrNull() ?: 0 }

            if (labelsCons.isNotEmpty()) {
                val exclCons = (1..semNum).associateWith { config.refsExcluidas(partida.uid, it) }
                fun metricasDe(parcelas: List<Parcela>) = Calculadora.computeMetricasDeParcelas(
                    parcelas, semNum, semana, state.semanas, state.datosPorParcela, exclCons
                )

                val metricasCons = labelsCons.associateWith { lbl ->
                    metricasDe(corralesTodos.filter { it.id.substringAfterLast("-") == lbl }.flatMap { it.parcelas })
                }
                val metricasLote = metricasDe(corralesTodos.flatMap { it.parcelas })

                val COL_LOTE = "LOTE"
                val colsCons = labelsCons + COL_LOTE
                val colWC = (tableW - labelW) / colsCons.size
                fun colCenterC(i: Int) = margin + labelW + colWC * i + colWC / 2f

                fun drawHeaderCons() {
                    pFill.color = green
                    canvas.drawRect(margin, y, margin + tableW, y + headH, pFill)
                    pHead.textAlign = Paint.Align.LEFT
                    canvas.drawText("INDICADOR", margin + 6f, y + 13f, pHead)
                    pHead.textAlign = Paint.Align.CENTER
                    colsCons.forEachIndexed { i, lbl -> canvas.drawText(lbl, colCenterC(i), y + 13f, pHead) }
                    pHead.textAlign = Paint.Align.LEFT
                    y += headH
                }

                asegurar(18f + headH + rowH)
                canvas.drawText("Consolidado · todas las galeras", margin, y + 10f, pGalera)
                y += 16f
                drawHeaderCons()

                kpis.forEachIndexed { idx, kpi ->
                    if (asegurar(rowH)) drawHeaderCons()
                    if (idx % 2 == 1) {
                        pFill.color = rowAlt
                        canvas.drawRect(margin, y, margin + tableW, y + rowH, pFill)
                    }
                    pCellB.textAlign = Paint.Align.LEFT
                    canvas.drawText(kpi.label, margin + 6f, y + 12f, pCellB)
                    pCell.textAlign = Paint.Align.CENTER
                    pCellB.textAlign = Paint.Align.CENTER
                    colsCons.forEachIndexed { i, lbl ->
                        val m = if (lbl == COL_LOTE) metricasLote else metricasCons[lbl]
                        // La columna del lote en negrita: es la que se lee primero.
                        canvas.drawText(kpi.get(m), colCenterC(i), y + 12f, if (lbl == COL_LOTE) pCellB else pCell)
                    }
                    pCell.textAlign = Paint.Align.LEFT
                    pCellB.textAlign = Paint.Align.LEFT
                    canvas.drawLine(margin, y + rowH, margin + tableW, y + rowH, pLine)
                    canvas.drawLine(margin + labelW, y, margin + labelW, y + rowH, pLine)
                    for (i in 1 until colsCons.size) {
                        val x = margin + labelW + colWC * i
                        canvas.drawLine(x, y, x, y + rowH, pLine)
                    }
                    y += rowH
                }
                canvas.drawText(
                    "Cada tratamiento junta sus jaulas de todas las galeras; los promedios se ponderan por saldo de aves.",
                    margin, y + 10f, pSub
                )
                y += 22f
            }
        }

        // ── Sección: Coeficiente de variación del peso (HOJA APARTE) ────────
        // Mismo cálculo que la planilla (DesvEst muestral / promedio de los pesos
        // promedio de las jaulas) en tres niveles: tratamiento, galera y global.
        // Siempre arranca en una hoja propia.
        nuevaPagina()
        run {
            val cvLabelW = 240f
            val cvCols = listOf("n jaulas", "Peso prom (g)", "Desv. est (g)", "2σ (g)", "CV % peso")
            val cvColW = (tableW - cvLabelW) / cvCols.size
            fun cvColCenter(i: Int) = margin + cvLabelW + cvColW * i + cvColW / 2f

            val subtotFill = AndroidColor.rgb(0xE4, 0xF0, 0xE8)
            val globalFill = AndroidColor.rgb(0x0D, 0x1A, 0x12)
            val pWhite = Paint().apply { color = AndroidColor.WHITE; textSize = 8.5f; typeface = bold; isAntiAlias = true }
            val pLineG = Paint().apply { color = green; strokeWidth = 1f }

            fun drawCvHeader() {
                pFill.color = green
                canvas.drawRect(margin, y, margin + tableW, y + headH, pFill)
                pHead.textAlign = Paint.Align.LEFT
                canvas.drawText("GRUPO", margin + 6f, y + 13f, pHead)
                pHead.textAlign = Paint.Align.CENTER
                cvCols.forEachIndexed { i, lbl -> canvas.drawText(lbl, cvColCenter(i), y + 13f, pHead) }
                pHead.textAlign = Paint.Align.LEFT
                y += headH
            }

            fun cvVals(cv: CvPeso?): List<String> = if (cv == null) List(5) { "—" } else listOf(
                cv.n.toString(),
                String.format(Locale.US, "%.1f", cv.media),
                String.format(Locale.US, "%.1f", cv.desv),
                String.format(Locale.US, "%.1f", cv.dosSigma),
                String.format(Locale.US, "%.1f%%", cv.cv * 100)
            )

            // estilo: 0 = tratamiento (con rayado alterno), 1 = subtotal galera, 2 = global
            fun drawCvRow(label: String, cv: CvPeso?, estilo: Int, parity: Int) {
                val fill = when (estilo) { 1 -> subtotFill; 2 -> globalFill; else -> if (parity % 2 == 1) rowAlt else null }
                if (fill != null) { pFill.color = fill; canvas.drawRect(margin, y, margin + tableW, y + rowH, pFill) }
                val lblPaint = when (estilo) { 2 -> pWhite; else -> pCellB }
                val valPaint = when (estilo) { 2 -> pWhite; 1 -> pCellB; else -> pCell }
                lblPaint.textAlign = Paint.Align.LEFT
                canvas.drawText(label, margin + 6f, y + 12f, lblPaint)
                valPaint.textAlign = Paint.Align.CENTER
                cvVals(cv).forEachIndexed { i, s -> canvas.drawText(s, cvColCenter(i), y + 12f, valPaint) }
                valPaint.textAlign = Paint.Align.LEFT
                lblPaint.textAlign = Paint.Align.LEFT
                // bordes
                if (estilo == 1) canvas.drawLine(margin, y, margin + tableW, y, pLineG)  // borde superior subtotal
                if (estilo != 2) {  // el global va sin separadores internos (fondo oscuro)
                    canvas.drawLine(margin, y + rowH, margin + tableW, y + rowH, pLine)
                    canvas.drawLine(margin + cvLabelW, y, margin + cvLabelW, y + rowH, pLine)
                    for (i in 1 until cvCols.size) {
                        val x = margin + cvLabelW + cvColW * i
                        canvas.drawLine(x, y, x, y + rowH, pLine)
                    }
                }
                y += rowH
            }

            // Encabezado de la hoja (mismo estilo que la primera página).
            canvas.drawText("Coeficiente de variación del peso", margin, y + 14f, pTitle)
            y += 24f
            canvas.drawText(
                "Semana ${semNum.toString().padStart(2, '0')}    ·    Partida ${partida.numero}    ·    Lote ${partida.lote}" +
                    (if (fechaSem.isNotBlank()) "    ·    Inicio de semana: $fechaSem" else ""),
                margin, y + 10f, pSub
            )
            y += 24f
            drawCvHeader()

            var parity = 0
            partida.galeras.forEach { galera ->
                val corrales = galera.corrales.sortedBy { c ->
                    c.id.substringAfterLast("-").filter { it.isDigit() }.toIntOrNull() ?: 0
                }
                corrales.forEach { c ->
                    if (c.parcelas.isEmpty()) return@forEach
                    val cv = Calculadora.cvPesoDeParcelas(c.parcelas, semNum, state.datosPorParcela)
                    if (asegurar(rowH)) drawCvHeader()
                    drawCvRow("${galera.id} · ${c.id.substringAfterLast("-")}", cv, 0, parity++)
                }
                val cvG = Calculadora.cvPesoDeParcelas(galera.corrales.flatMap { it.parcelas }, semNum, state.datosPorParcela)
                if (asegurar(rowH)) drawCvHeader()
                drawCvRow("Subtotal ${galera.nombre}", cvG, 1, parity)
            }
            val cvGlobal = Calculadora.cvPesoDeParcelas(
                partida.galeras.flatMap { it.corrales }.flatMap { it.parcelas }, semNum, state.datosPorParcela
            )
            if (asegurar(rowH)) drawCvHeader()
            drawCvRow("GLOBAL · todo el lote", cvGlobal, 2, parity)
        }

        // ── Sección: Alimento acumulado por tratamiento (HOJA APARTE, 1 sola hoja) ──
        // Por galera, tabla con tratamientos en COLUMNAS y referencias en FILAS:
        // alimento "consumido" (contado) y "excluido" (referencias desechadas), en kg,
        // acumulado a la semana. Cuentan las referencias ACTIVAS de cada semana y, además,
        // las marcadas como "Excluir" aparecen aunque ya no estén activas. El alto de filas
        // se ESCALA para que todo entre en UNA única hoja (no pagina).
        nuevaPagina()
        run {
            val exclMapAll = (1..semNum).associateWith { config.refsExcluidas(partida.uid, it) }
            // Incluye las referencias activas Y las marcadas como "Excluir" (para que una
            // referencia excluida aparezca aunque ya no esté activa).
            val allRefs = (state.semanas.flatMap { it.refsActivas } + exclMapAll.values.flatten())
                .distinct()
                .sortedBy { r -> r.filter { it.isDigit() }.toIntOrNull() ?: 0 }

            // Alimento físico (kg) por referencia de un corral, separado en contado/excluido.
            fun alimentoCorral(corral: Corral): Pair<Map<String, Double>, Map<String, Double>> {
                val cons = mutableMapOf<String, Double>()
                val exc = mutableMapOf<String, Double>()
                for (par in corral.parcelas) {
                    val dby = state.datosPorParcela[par.id] ?: emptyMap()
                    for (sn in 1..semNum) {
                        val s = state.semanas.find { it.numero == sn } ?: continue
                        val exclSn = exclMapAll[sn] ?: emptySet()
                        // Referencias activas + las marcadas como "Excluir" esa semana (una
                        // referencia excluida cuenta/aparece aunque ya no esté activa).
                        for (tipo in (s.refsActivas + exclSn).distinct()) {
                            val prev = if (sn == 1) 0.0 else (dby[sn - 1]?.refs?.get(tipo)?.saldoFin ?: 0.0)
                            val ing = dby[sn]?.refs?.get(tipo)?.ingreso ?: 0.0
                            val sal = dby[sn]?.refs?.get(tipo)?.saldoFin ?: 0.0
                            val kg = (prev + ing - sal).coerceAtLeast(0.0)
                            if (kg <= 0.0) continue
                            if (tipo in exclSn) exc[tipo] = (exc[tipo] ?: 0.0) + kg
                            else cons[tipo] = (cons[tipo] ?: 0.0) + kg
                        }
                    }
                }
                return cons to exc
            }
            fun fmtKg(v: Double) = String.format(Locale.US, "%.1f", v)

            // Datos por galera precalculados (para poder escalar y que entre en una hoja).
            data class GalAlim(
                val nombre: String, val labels: List<String>,
                val cons: List<Map<String, Double>>, val exc: List<Map<String, Double>>,
                val consRefs: List<String>, val excRefs: List<String>
            )
            val galeraData = partida.galeras.mapNotNull { galera ->
                val corrales = galera.corrales.sortedBy { c ->
                    c.id.substringAfterLast("-").filter { it.isDigit() }.toIntOrNull() ?: 0
                }
                if (corrales.isEmpty()) return@mapNotNull null
                val pairs = corrales.map { alimentoCorral(it) }
                val cons = pairs.map { it.first }; val exc = pairs.map { it.second }
                GalAlim(
                    nombre = galera.nombre,
                    labels = corrales.map { it.id.substringAfterLast("-") },
                    cons = cons, exc = exc,
                    consRefs = allRefs.filter { r -> cons.any { (it[r] ?: 0.0) > 0.0 } },
                    excRefs = allRefs.filter { r -> exc.any { (it[r] ?: 0.0) > 0.0 } }
                )
            }

            // Encabezado de la hoja.
            canvas.drawText("Alimento acumulado por tratamiento (kg)", margin, y + 14f, pTitle)
            y += 24f
            canvas.drawText(
                "Semana ${semNum.toString().padStart(2, '0')}    ·    Partida ${partida.numero}    ·    Lote ${partida.lote}" +
                    (if (fechaSem.isNotBlank()) "    ·    Inicio de semana: $fechaSem" else ""),
                margin, y + 10f, pSub
            )
            y += 22f

            // Escalado para FORZAR una sola hoja: calculamos el alto necesario y lo ajustamos
            // al espacio disponible (sin paginar nunca). El texto acompaña la escala.
            val footH = 16f
            val nG = galeraData.size
            val totalRows = galeraData.sumOf { gd ->
                1 + gd.consRefs.size + 1 + (if (gd.excRefs.isNotEmpty()) 2 + gd.excRefs.size else 0)
            }
            var headH2 = 18f; var rowH2 = 15f; var titleH2 = 16f; var gapH2 = 12f
            val available = (pageH - margin - footH) - y
            val needed = nG * headH2 + totalRows * rowH2 + nG * titleH2 + nG * gapH2
            val s = if (needed > available && needed > 0f) (available / needed).coerceIn(0.45f, 1f) else 1f
            headH2 *= s; rowH2 *= s; titleH2 *= s; gapH2 *= s

            val tBody = (8f * s).coerceIn(5f, 8f)
            val tHead = (7.5f * s).coerceIn(5f, 7.5f)
            val tGal = (11f * s).coerceIn(7.5f, 11f)
            val amberCol = AndroidColor.rgb(0xB4, 0x69, 0x0E)
            val pHeadL = Paint().apply { color = AndroidColor.WHITE; textSize = tHead; typeface = bold; isAntiAlias = true }
            val pCellL = Paint().apply { color = ink; textSize = tBody; isAntiAlias = true }
            val pCellBL = Paint().apply { color = ink; textSize = tBody; typeface = bold; isAntiAlias = true }
            val pAmber = Paint().apply { color = amberCol; textSize = tBody; isAntiAlias = true }
            val pAmberB = Paint().apply { color = amberCol; textSize = tBody; typeface = bold; isAntiAlias = true }
            val pGalL = Paint().apply { color = green; textSize = tGal; typeface = bold; isAntiAlias = true }
            val subtotFill = AndroidColor.rgb(0xE4, 0xF0, 0xE8)
            val consBand = AndroidColor.rgb(0xEC, 0xF5, 0xEF)
            val excBand = AndroidColor.rgb(0xFB, 0xF1, 0xE2)

            galeraData.forEach { gd ->
                val labels = gd.labels
                val labelW2 = 96f
                val nCols = labels.size + 1   // tratamientos + columna "Galera"
                val colW2 = (tableW - labelW2) / nCols
                fun cCenter(i: Int) = margin + labelW2 + colW2 * i + colW2 / 2f  // i == labels.size → Galera
                val rowBaseline = { y + rowH2 / 2 + 3f }   // baseline centrado de una fila

                fun drawHead() {
                    pFill.color = green
                    canvas.drawRect(margin, y, margin + tableW, y + headH2, pFill)
                    val hb = y + headH2 / 2 + 3f
                    pHeadL.textAlign = Paint.Align.LEFT
                    canvas.drawText("Referencia", margin + 6f, hb, pHeadL)
                    pHeadL.textAlign = Paint.Align.CENTER
                    labels.forEachIndexed { i, l -> canvas.drawText(l, cCenter(i), hb, pHeadL) }
                    canvas.drawText("Galera", cCenter(labels.size), hb, pHeadL)
                    pHeadL.textAlign = Paint.Align.LEFT
                    y += headH2
                }
                fun drawColLines() {
                    canvas.drawLine(margin + labelW2, y, margin + labelW2, y + rowH2, pLine)
                    for (i in 0 until nCols) {
                        val x = margin + labelW2 + colW2 * (i + 1)
                        canvas.drawLine(x, y, x, y + rowH2, pLine)
                    }
                    canvas.drawLine(margin, y + rowH2, margin + tableW, y + rowH2, pLine)
                }
                fun drawBand(text: String, amber: Boolean) {
                    pFill.color = if (amber) excBand else consBand
                    canvas.drawRect(margin, y, margin + tableW, y + rowH2, pFill)
                    val p = if (amber) pAmberB else pCellBL
                    p.textAlign = Paint.Align.LEFT
                    canvas.drawText(text, margin + 6f, rowBaseline(), p)
                    canvas.drawLine(margin, y + rowH2, margin + tableW, y + rowH2, pLine)
                    y += rowH2
                }
                fun drawDataRow(label: String, cells: List<Double>, galTotal: Double, amber: Boolean, sumRow: Boolean, dashZero: Boolean) {
                    if (sumRow) { pFill.color = if (amber) excBand else subtotFill; canvas.drawRect(margin, y, margin + tableW, y + rowH2, pFill) }
                    val lp = when { amber && sumRow -> pAmberB; amber -> pAmber; else -> pCellBL }
                    val vp = when { amber && sumRow -> pAmberB; amber -> pAmber; sumRow -> pCellBL; else -> pCellL }
                    val bl = rowBaseline()
                    lp.textAlign = Paint.Align.LEFT
                    canvas.drawText(label, margin + 6f, bl, lp)
                    vp.textAlign = Paint.Align.CENTER
                    cells.forEachIndexed { i, v ->
                        canvas.drawText(if (!dashZero || v > 0.0) fmtKg(v) else "—", cCenter(i), bl, vp)
                    }
                    canvas.drawText(fmtKg(galTotal), cCenter(labels.size), bl, vp)
                    lp.textAlign = Paint.Align.LEFT; vp.textAlign = Paint.Align.LEFT
                    if (sumRow) canvas.drawLine(margin, y, margin + tableW, y, pLine)
                    drawColLines()
                    y += rowH2
                }

                canvas.drawText(gd.nombre, margin, y + titleH2 * 0.72f, pGalL)
                y += titleH2
                drawHead()
                drawBand("Consumido", amber = false)
                gd.consRefs.forEach { ref ->
                    val cells = labels.indices.map { gd.cons[it][ref] ?: 0.0 }
                    drawDataRow(ref, cells, cells.sum(), amber = false, sumRow = false, dashZero = true)
                }
                val consSum = labels.indices.map { i -> gd.consRefs.sumOf { gd.cons[i][it] ?: 0.0 } }
                drawDataRow("Σ Consumido", consSum, consSum.sum(), amber = false, sumRow = true, dashZero = false)

                if (gd.excRefs.isNotEmpty()) {
                    drawBand("Excluido", amber = true)
                    gd.excRefs.forEach { ref ->
                        val cells = labels.indices.map { gd.exc[it][ref] ?: 0.0 }
                        drawDataRow(ref, cells, cells.sum(), amber = true, sumRow = false, dashZero = true)
                    }
                    val excSum = labels.indices.map { i -> gd.excRefs.sumOf { gd.exc[i][it] ?: 0.0 } }
                    drawDataRow("Σ Excluido", excSum, excSum.sum(), amber = true, sumRow = true, dashZero = false)
                }
                y += gapH2
            }

            canvas.drawText(
                "Desglose por alimento físico (saldo previo + ingreso − saldo final); incluye las referencias activas y las marcadas como excluidas.",
                margin, y + 8f, pSub
            )
            y += 14f
        }

        canvas.drawText("Flock Tracker", margin, pageH - 14f, pSub)
        pdf.finishPage(page)

        val dir = exportsDir(context)
        val numero = partida.numero.filter { it.isLetterOrDigit() || it == '-' }.ifBlank { "lote" }
        val file = File(dir, "Resumen_Lote_${numero}_S${semNum.toString().padStart(2, '0')}.pdf")
        // Garantizar el cierre del PdfDocument y la liberación del Bitmap del logo
        // aunque la escritura falle (recursos nativos).
        try {
            FileOutputStream(file).use { pdf.writeTo(it) }
        } finally {
            pdf.close()
            logo?.recycle()
        }
        file
    }

    /** Ancho estándar de columnas (autoSizeColumn no funciona en Android). */
    private fun ajustarAnchos(sheet: org.apache.poi.ss.usermodel.Sheet, totalCols: Int) {
        sheet.setColumnWidth(0, 16 * 256)        // primera columna (texto) más ancha
        for (c in 1..totalCols) sheet.setColumnWidth(c, 12 * 256)
    }

    @Suppress("unused")  // deshabilitada: el export ahora es solo la hoja Estadística
    private fun exportGalera(wb: XSSFWorkbook, galera: Galera, state: AppState, styles: Map<String, CellStyle>, opciones: OpcionesExport) {
        val safeName = galera.nombre.filter { it.isLetterOrDigit() || it == ' ' }.take(31).ifBlank { "Galera ${galera.id}" }
        val sheet = wb.createSheet(safeName)
        var rowIdx = 0
        ajustarAnchos(sheet, 30)

        // Referencias desechadas del cálculo, por semana: el Excel debe respetarlas
        // igual que la pantalla.
        val exclPorSem = state.partida?.let { p ->
            state.semanas.associate { it.numero to config.refsExcluidas(p.uid, it.numero) }
        } ?: emptyMap()

        // Serie completa por jaula, calculada UNA vez (ver exportEstadistica).
        val seriePorParcela = galera.corrales.flatMap { it.parcelas }.associate { par ->
            par.id to Calculadora.computeSerieParcela(
                parcela = par,
                semanas = state.semanas,
                datosByParcela = state.datosPorParcela[par.id] ?: emptyMap(),
                refsExcluidasPorSemana = exclPorSem
            )
        }

        // Encabezado
        sheet.createRow(rowIdx++).also { r ->
            r.createCell(0).apply { setCellValue("Galera: ${galera.nombre}"); setCellStyle(styles["header"]) }
        }
        sheet.createRow(rowIdx++) // espacio

        for (corral in galera.corrales) {
            for (semana in state.semanas) {
                val semNum = semana.numero

                // Título tratamiento/semana
                sheet.createRow(rowIdx++).also { r ->
                    r.createCell(0).apply {
                        setCellValue("Tratamiento ${corral.id.substringAfterLast("-")}  ·  Semana ${semNum.toString().padStart(2, '0')}")
                        setCellStyle(styles["subheader"])
                    }
                }

                // Cabeceras columnas
                sheet.createRow(rowIdx++).also { r ->
                    val headers = listOf("Parcela", "Inicio", "Lun", "Mar", "Mié", "Jue", "Vie", "Sáb", "Dom",
                        "Total mort", "Saldo", "Prom g/ave", "Peso Total(g)") +
                            semana.refsActivas.flatMap { listOf("$it ingreso", "$it saldo fin") } +
                            listOf("Consumo(g)", "Cons g/ave", "Cons Acum(g)", "FCR Sem", "FCR Acum", "GDP Sem", "Mort Acum%", "Ratio") +
                            (if (opciones.incluirFcrAjustado) listOf("FCR AJ 2.5") else emptyList())
                    headers.forEachIndexed { i, h ->
                        r.createCell(i).apply { setCellValue(h); setCellStyle(styles["colHeader"]) }
                    }
                }

                for (parcela in corral.parcelas) {
                    val datosByPar = state.datosPorParcela[parcela.id] ?: emptyMap()
                    val dato = datosByPar[semNum] ?: DatoParcela(semNum, parcela.id)

                    // Único motor de cálculo: el mismo que alimenta la pantalla y el PDF.
                    val m = seriePorParcela[parcela.id]?.get(semNum)
                        ?: Calculadora.computeMetricasParcela(
                            parcela = parcela,
                            semNum = semNum,
                            todasSemanas = state.semanas,
                            datosByParcela = datosByPar,
                            refsExcluidasPorSemana = exclPorSem
                        )

                    // La hoja deja la celda vacía cuando el indicador no aplica, y este
                    // escritor usa 0.0 como "vacío"; por eso se aplanan los null.
                    val inicio = m.saldoAnterior
                    val mort = m.mortSem
                    val saldo = m.saldo
                    val prom = m.pesoGave
                    val pesoTotal = m.pesoTotal
                    val cons = m.alimKgSem
                    val consGave = m.consGave
                    val consAcumGave = m.consAcumGave
                    val gain = m.gain
                    val fcrSem = m.fcrSem ?: 0.0
                    val fcrAcum = m.fcrAcum ?: 0.0
                    val mortAcumPct = m.mortAcumPct
                    val ratio = m.ratio ?: 0.0
                    val fcrAdj = m.fcrAjustado(Calculadora.FCR_ADJ_OBJETIVO_2_5) ?: 0.0

                    val sInt = styles["int"]; val sD1 = styles["dec1"]; val sD3 = styles["dec3"]
                    val sPct = styles["pct"]; val sTxt = styles["text"]
                    sheet.createRow(rowIdx++).also { r ->
                        var ci = 0
                        r.put(ci++, parcela.id, sTxt)
                        r.put(ci++, inicio.toDouble(), sInt)
                        dato.mort.forEach { v -> r.put(ci++, (v ?: 0).toDouble(), sInt) }
                        r.put(ci++, mort.toDouble(), sInt)
                        r.put(ci++, saldo.toDouble(), sInt)
                        r.put(ci++, prom, sD1)
                        r.put(ci++, pesoTotal, sD1)
                        for (tipo in semana.refsActivas) {
                            val ref = dato.refs[tipo] ?: RefAlimento(tipo)
                            r.put(ci++, ref.ingreso ?: 0.0, sD1)
                            r.put(ci++, ref.saldoFin ?: 0.0, sD1)
                        }
                        r.put(ci++, cons, sD1)
                        r.put(ci++, consGave, sD1)
                        r.put(ci++, consAcumGave, sD1)
                        r.put(ci++, fcrSem, sD3)
                        r.put(ci++, fcrAcum, sD3)
                        r.put(ci++, gain / 7.0, sD1)
                        r.put(ci++, mortAcumPct, sPct)   // fracción → formato 0.0%
                        if (ratio > 0) r.put(ci++, ratio, sD3) else ci++
                        if (opciones.incluirFcrAjustado) { if (fcrAdj > 0) r.put(ci++, fcrAdj, sD3) else ci++ }
                    }
                }
                sheet.createRow(rowIdx++) // espacio
            }
        }

        // Autosize NO FUNCIONA en Android (requiere AWT)
        // for (i in 0..20) sheet.autoSizeColumn(i)
    }

    @Suppress("unused")  // deshabilitada: el export ahora es solo la hoja Estadística
    private fun exportResumen(wb: XSSFWorkbook, state: AppState, styles: Map<String, CellStyle>, opciones: OpcionesExport) {
        val sheet = wb.createSheet("Resumen")
        var rowIdx = 0
        ajustarAnchos(sheet, 16)

        // Cabeceras dinámicas: las columnas opcionales (CV%, FEP, FCR AJ) sólo aparecen
        // si se piden; el orden coincide con el de escritura de filas más abajo.
        val headers = listOf("Galera", "Tratamiento", "Saldo", "Peso g/ave", "Cons g/ave", "Cons Acum g/ave",
            "FCR sem", "FCR acum", "GDP sem", "GDP lineal", "Mort acum%", "CGR") +
            (if (opciones.incluirCv) listOf("CV%") else emptyList()) +
            (if (opciones.incluirFep) listOf("FEP") else emptyList()) +
            listOf("Ratio") +
            (if (opciones.incluirFcrAjustado) listOf("FCR AJ 2.5") else emptyList())

        // Referencias desechadas del cálculo, por semana: igual que la pantalla.
        val exclPorSem = state.partida?.let { p ->
            state.semanas.associate { it.numero to config.refsExcluidas(p.uid, it.numero) }
        } ?: emptyMap()

        for (semana in state.semanas) {
            sheet.createRow(rowIdx++).also { r ->
                r.createCell(0).apply {
                    setCellValue("Semana ${semana.numero.toString().padStart(2, '0')}")
                    setCellStyle(styles["header"])
                }
            }

            // Cabeceras
            sheet.createRow(rowIdx++).also { r ->
                headers.forEachIndexed { i, h ->
                    r.createCell(i).apply { setCellValue(h); setCellStyle(styles["colHeader"]) }
                }
            }

            state.partida?.galeras?.forEach { galera ->
                galera.corrales.forEach { corral ->
                    val m = Calculadora.computeMetricasCorral(
                        corral          = corral,
                        semNum          = semana.numero,
                        semana          = semana,
                        todasSemanas    = state.semanas,
                        datosPorParcela = state.datosPorParcela,
                        refsExcluidasPorSemana = exclPorSem
                    )
                    val sInt = styles["int"]; val sD1 = styles["dec1"]; val sD3 = styles["dec3"]
                    val sPct = styles["pct"]; val sTxt = styles["text"]
                    sheet.createRow(rowIdx++).also { r ->
                        var c = 0
                        r.put(c++, galera.nombre, sTxt)
                        r.put(c++, corral.id.substringAfterLast("-"), sTxt)
                        r.put(c++, m?.saldo?.toDouble() ?: 0.0, sInt)
                        r.put(c++, m?.promPeso ?: 0.0, sD1)
                        r.put(c++, m?.consumoGave ?: 0.0, sD1)
                        r.put(c++, m?.consumoAcum ?: 0.0, sD1)
                        r.put(c++, m?.fcrSem ?: 0.0, sD3)
                        r.put(c++, m?.fcrAcum ?: 0.0, sD3)
                        r.put(c++, m?.gdpSem ?: 0.0, sD1)
                        r.put(c++, m?.gdpLineal ?: 0.0, sD1)
                        r.put(c++, m?.mortAcumPct ?: 0.0, sPct)   // fracción → 0.0%
                        r.put(c++, m?.cgr ?: 0.0, sD3)
                        if (opciones.incluirCv)  r.put(c++, m?.cvPeso ?: 0.0, sPct)  // fracción → 0.0%
                        if (opciones.incluirFep) r.put(c++, m?.fep ?: 0.0, sD1)
                        if (m?.ratio != null && m.ratio > 0) r.put(c++, m.ratio, sD3) else c++
                        if (opciones.incluirFcrAjustado) {
                            if (m?.fcrAdj != null && m.fcrAdj > 0) r.put(c++, m.fcrAdj, sD3) else c++
                        }
                    }
                }
            }
            sheet.createRow(rowIdx++)
        }

        // Autosize NO FUNCIONA en Android (requiere AWT)
        // for (i in 0..11) sheet.autoSizeColumn(i)
    }

    private fun exportEstadistica(wb: XSSFWorkbook, state: AppState, styles: Map<String, CellStyle>, opciones: OpcionesExport) {
        val sheet = wb.createSheet("Estadística")
        var rowIdx = 0
        ajustarAnchos(sheet, 18)

        // El orden debe coincidir con el de escritura de filas: 2.5 KG, RATIO, 2 KG, 2.7 KG.
        val headers = listOf(
            "BLOQUE", "PARCELA", "SEMANA", "TRATAMIENTO", "REPETICIÓN",
            "PESO (g)", "CONSUMO SEMANAL (g)", "CONSUMO ACUMULADO (g)",
            "FCR SEMANAL", "FCR ACUMULADO", "GDP SEMANAL", "GDP LINEAL",
            "% MORTALIDAD", "% MORTALIDAD ACUMULADA", "RATIO"
        ) +
            // FCR ajustado agrupado y ordenado de menor a mayor: 2.0 → 2.5 → 2.7 kg.
            (if (opciones.incluirFcrAjustado)
                listOf("FCR AJUSTADO 2 KG", "FCR AJUSTADO 2.5 KG", "FCR AJUSTADO 2.7 KG") else emptyList())

        // Header Row
        val headerRow = sheet.createRow(rowIdx++)
        headers.forEachIndexed { i, h ->
            headerRow.createCell(i).apply {
                setCellValue(h)
                setCellStyle(styles["statHeader"])
            }
        }

        val partida = state.partida ?: return
        // Referencias desechadas del cálculo, por semana (uid+sem).
        val exclPorSem = state.semanas.associate { it.numero to config.refsExcluidas(partida.uid, it.numero) }

        // Serie completa por jaula, calculada UNA vez. La hoja escribe una fila por jaula
        // y semana; pedir cada semana por separado rehacía el arrastre desde la semana 1
        // (O(semanas²)). El orden de las filas no cambia: solo cambia de dónde salen.
        val seriePorParcela = partida.galeras
            .flatMap { it.corrales }.flatMap { it.parcelas }
            .associate { par ->
                par.id to Calculadora.computeSerieParcela(
                    parcela = par,
                    semanas = state.semanas,
                    datosByParcela = state.datosPorParcela[par.id] ?: emptyMap(),
                    refsExcluidasPorSemana = exclPorSem
                )
            }

        // Mapa para llevar el conteo de repeticiones por tratamiento
        // key: GaleraId-TratamientoLabel, value: counter
        val repeticiones = mutableMapOf<String, Int>()

        for (semana in state.semanas) {
            val semNum = semana.numero
            
            for (galera in partida.galeras) {
                // El Bloque es el número de la galera
                val bloque = try { galera.id.filter { it.isDigit() }.toInt() } catch(e:Exception) { 0 }
                
                for (corral in galera.corrales) {
                    val tratamiento = corral.id.split("-").last()
                    
                    corral.parcelas.forEachIndexed { pIdx, parcela ->
                        val key = "${galera.id}-${tratamiento}"
                        // La repetición es el índice de la parcela en el corral (1-based)
                        val rep = pIdx + 1

                        val datosByPar = state.datosPorParcela[parcela.id] ?: emptyMap()

                        // Único motor de cálculo: el mismo que alimenta la pantalla y el PDF.
                        val m = seriePorParcela[parcela.id]?.get(semNum)
                            ?: Calculadora.computeMetricasParcela(
                                parcela = parcela,
                                semNum = semNum,
                                todasSemanas = state.semanas,
                                datosByParcela = datosByPar,
                                refsExcluidasPorSemana = exclPorSem
                            )

                        // La tabla deja la celda vacía cuando el indicador no aplica, y
                        // este escritor usa 0.0 como "vacío"; por eso se aplanan los null.
                        val pesoGave = m.pesoGave
                        val consGaveSem = m.consGave
                        val consAcumGave = m.consAcumGave
                        val fcrSem = m.fcrSem ?: 0.0
                        val fcrAcum = m.fcrAcum ?: 0.0
                        val gdpSem = m.gdpSem
                        val gdpLin = m.gdpLineal
                        val mortPct = m.mortPct
                        val mortAcumPct = m.mortAcumPct
                        val ratio = m.ratio ?: 0.0
                        val fcrAdj20 = m.fcrAjustado(Calculadora.FCR_ADJ_OBJETIVO_2_0) ?: 0.0
                        val fcrAdj25 = m.fcrAjustado(Calculadora.FCR_ADJ_OBJETIVO_2_5) ?: 0.0
                        val fcrAdj27 = m.fcrAjustado(Calculadora.FCR_ADJ_OBJETIVO_2_7) ?: 0.0

                        val sInt = styles["int"]; val sD1 = styles["dec1"]; val sD3 = styles["dec3"]
                        val sPct = styles["pct"]; val sTxt = styles["text"]
                        val row = sheet.createRow(rowIdx++)
                        var c = 0
                        row.put(c++, bloque.toDouble(), sInt)
                        row.put(c++, parcela.id, sTxt)
                        row.put(c++, semNum.toDouble(), sInt)
                        row.put(c++, tratamiento, sTxt)
                        row.put(c++, rep.toDouble(), sInt)
                        row.put(c++, pesoGave, sD1)
                        row.put(c++, consGaveSem, sD1)
                        row.put(c++, consAcumGave, sD1)
                        row.put(c++, fcrSem, sD3)
                        row.put(c++, fcrAcum, sD3)
                        row.put(c++, gdpSem, sD1)
                        row.put(c++, gdpLin, sD1)
                        row.put(c++, mortPct, sPct)        // fracción → 0.0%
                        row.put(c++, mortAcumPct, sPct)    // fracción → 0.0%

                        // Celdas condicionadas: si el valor es 0.0 y no corresponde a la semana, se dejan vacías.
                        // Las columnas de FCR Ajustado son opcionales (no existen en la planilla).
                        if (ratio > 0) row.put(c++, ratio, sD3) else c++
                        // FCR ajustado de menor a mayor: 2.0 → 2.5 → 2.7 kg.
                        if (opciones.incluirFcrAjustado) {
                            if (fcrAdj20 > 0) row.put(c++, fcrAdj20, sD3) else c++
                            if (fcrAdj25 > 0) row.put(c++, fcrAdj25, sD3) else c++
                            if (fcrAdj27 > 0) row.put(c++, fcrAdj27, sD3) else c++
                        }
                    }
                }
            }
        }

        // Formatear como Tabla
        try {
            val lastRow = rowIdx - 1
            val lastCol = headers.size - 1
            if (lastRow > 0) {
                // Freezar primera fila
                sheet.createFreezePane(0, 1)
                
                val area = wb.creationHelper.createAreaReference(
                    org.apache.poi.ss.util.CellReference(0, 0),
                    org.apache.poi.ss.util.CellReference(lastRow, lastCol)
                )
                
                val table = (sheet as XSSFSheet).createTable(area)
                table.name = "TablaEstadistica"
                table.displayName = "TablaEstadistica"
                
                val ctTable = table.getCTTable()
                
                // 1. Estilo
                val style = ctTable.addNewTableStyleInfo()
                style.name = "TableStyleMedium2"
                style.showRowStripes = true
                
                // 2. AutoFiltro
                ctTable.addNewAutoFilter().ref = area.formatAsString()
                
                // 3. NO usar addNewTableColumns (ya existen por createTable)
                // En su lugar, configuramos los nombres de las columnas existentes
                val ctColumns = ctTable.tableColumns
                for (i in 0 until ctColumns.count.toInt()) {
                    val col = ctColumns.getTableColumnArray(i)
                    // Usamos el nombre del encabezado pero sanitizado para el XML
                    col.name = headers[i].replace(Regex("[^A-Za-z0-9]"), "_")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Copia el APK instalado de la app a la cache y devuelve el archivo, listo para
     * compartir (WhatsApp, Bluetooth, Drive, etc.).
     */
    suspend fun compartirApk(context: Context): File = withContext(Dispatchers.IO) {
        val origen = File(context.applicationInfo.sourceDir)
        val dir = exportsDir(context)
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) { null }
        val nombre = "FlockTracker" + (version?.let { "_v$it" } ?: "") + ".apk"
        val dest = File(dir, nombre)
        origen.copyTo(dest, overwrite = true)
        dest
    }

    /** Carpeta de exportaciones en cache. La limpia de archivos viejos (>24 h) para no
     *  acumular (incluida la copia del APK), conservando lo recién generado. */
    private fun exportsDir(context: Context): File {
        val dir = File(context.cacheDir, "exports").also { it.mkdirs() }
        val limite = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        dir.listFiles()?.forEach { if (it.isFile && it.lastModified() < limite) it.delete() }
        return dir
    }

    fun shareFile(
        context: Context,
        file: File,
        asunto: String? = null,
        mensaje: String? = null
    ) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        val mime = when {
            file.name.endsWith(".json") -> "application/json"
            // .davi se entrega como octet-stream para que el intent-filter de la app
            // lo capture al abrirlo desde WhatsApp/Archivos.
            file.name.endsWith(".davi") -> "application/octet-stream"
            file.name.endsWith(".apk") -> "application/vnd.android.package-archive"
            file.name.endsWith(".pdf") -> "application/pdf"
            else -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            // Asunto y cuerpo: las apps de correo (Gmail/Outlook) los usan como asunto/mensaje;
            // WhatsApp toma el texto como mensaje; el resto de apps los ignoran sin problema.
            if (!asunto.isNullOrBlank()) putExtra(Intent.EXTRA_SUBJECT, asunto)
            if (!mensaje.isNullOrBlank()) putExtra(Intent.EXTRA_TEXT, mensaje)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // FLAG_ACTIVITY_NEW_TASK por si el contexto no es una Activity; manejar la
        // ausencia de apps capaces de compartir el archivo.
        val chooser = Intent.createChooser(intent, "Compartir").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(chooser)
        } catch (e: android.content.ActivityNotFoundException) {
            android.widget.Toast.makeText(
                context, "No hay una app para compartir este archivo", android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    // ── JSON Export / Import ─────────────────────────────────────

    /**
     * Exporta el lote activo completo (estructura + semanas + datos digitados) como
     * un archivo .davi. Al abrirlo desde WhatsApp/Archivos, la app lo reconoce y
     * restaura el lote entero.
     */
    suspend fun exportarLote(context: Context): File = withContext(Dispatchers.IO) {
        val state = repo.cargarEstado()
        val partida = state.partida ?: error("No hay partida activa")

        val dump = BackupDump(
            version = 1,
            exportedAt = System.currentTimeMillis(),
            partida = partida,
            semanas = state.semanas,
            datosPorParcela = state.datosPorParcela
        )
        val json = com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(dump)

        val dir = exportsDir(context)
        val safe = partida.numero.ifBlank { "partida" }.filter { it.isLetterOrDigit() || it == '-' }
        val file = File(dir, "Lote_${safe}.davi")
        file.writeText(json)
        file
    }

    /** True si el JSON corresponde a un BACKUP COMPLETO de lote (no a una distribución). */
    fun esBackupCompleto(json: String): Boolean = try {
        val root = com.google.gson.JsonParser.parseString(json).asJsonObject
        (root.has("version") && root.has("datosPorParcela")) ||
            (root.has("partida") && root.has("semanas"))
    } catch (e: Exception) {
        false
    }

    /** Lee el UID del lote dentro de un JSON de backup (vacío si no lo tiene). */
    fun uidDeBackup(json: String): String = try {
        com.google.gson.JsonParser.parseString(json).asJsonObject
            .getAsJsonObject("partida")?.get("uid")?.asString ?: ""
    } catch (e: Exception) {
        ""
    }

    /**
     * Importa un backup completo desde su contenido JSON (ya leído). Detecta el
     * formato (interno o experimental) y restaura el lote. Devuelve el id nuevo.
     *
     * @param comoCopia si true, fuerza un UID nuevo y un número con sufijo de copia
     *        (3580 → 3580-C2). Si false, importa con su número salvo que ya exista,
     *        en cuyo caso también se genera una copia para no violar la unicidad.
     */
    suspend fun importarDesdeJson(json: String, comoCopia: Boolean = false): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val jsonRoot = com.google.gson.JsonParser.parseString(json).asJsonObject
            when {
                jsonRoot.has("version") && jsonRoot.has("datosPorParcela") -> importarFormatoBackup(json, comoCopia)
                jsonRoot.has("partida") && jsonRoot.has("semanas")         -> importarFormatoExperimental(json)
                else -> Result.failure(IllegalArgumentException("Formato no reconocido"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun importarFormatoBackup(json: String, comoCopia: Boolean = false): Result<Long> {
        val dump = com.google.gson.Gson().fromJson(json, BackupDump::class.java)
            ?: return Result.failure(IllegalArgumentException("JSON de backup inválido"))

        val original = dump.partida ?: return Result.failure(IllegalArgumentException("No hay partida"))

        // Resolver número único y UID según si es copia o importación directa.
        val numeroFinal = if (comoCopia || repo.existeOtraPartidaConNumero(original.numero, -1L))
            repo.generarNumeroCopia(original.numero)
        else original.numero
        val uidFinal = if (comoCopia) java.util.UUID.randomUUID().toString()
            else original.uid.ifBlank { java.util.UUID.randomUUID().toString() }

        val partida = original.copy(id = 0L, numero = numeroFinal, uid = uidFinal)
        val newId = repo.guardarPartida(partida)

        // Si algo falla a media importación (JSON incompleto, campos nulos de Gson, etc.)
        // borramos el lote recién creado para no dejar un lote fantasma vacío/parcial.
        try {
            dump.semanas.forEach { sem -> repo.upsertSemana(sem) }
            dump.datosPorParcela.values.forEach { porSem ->
                porSem.values.forEach { d ->
                    repo.savePeso(d.semanaNumero, d.parcelaId, d.peso, d.pesos)
                    repo.saveMortalidad(d.semanaNumero, d.parcelaId, d.mort)
                    if (d.consAjust != null) repo.saveConsAjust(d.semanaNumero, d.parcelaId, d.consAjust)
                    d.refs.forEach { (tipo, ref) ->
                        repo.saveRefAlimento(d.semanaNumero, d.parcelaId, tipo, ref.ingreso, ref.saldoFin)
                    }
                }
            }
        } catch (e: Exception) {
            repo.borrarFisicamente(newId)
            return Result.failure(e)
        }
        return Result.success(newId)
    }

    private suspend fun importarFormatoExperimental(json: String): Result<Long> {
        val root = com.google.gson.Gson().fromJson(json, ExpRoot::class.java)
            ?: return Result.failure(IllegalArgumentException("JSON experimental inválido"))

        val p = root.partida
        val domainPartida = Partida(
            numero = p.numero,
            lote = p.lote,
            edad = p.edad,
            fechaInicio = p.fechaInicio,
            galeras = p.galeras.map { g ->
                Galera(
                    id = g.id,
                    nombre = g.nombre,
                    corrales = g.corrales.map { k ->
                        Corral(
                            id = k.id,
                            galeraId = g.id,
                            parcelas = k.parcelas.map { par ->
                                Parcela(par.id, k.id, par.inicio, par.pesoInicio)
                            }
                        )
                    }
                )
            }
        )

        val newId = repo.guardarPartida(domainPartida)

        try {
            root.semanas.forEach { s ->
                repo.upsertSemana(Semana(s.numero, s.fechaInicio, s.fechaFin, s.refs))

                // Recorrer el mapa anidado: Galera -> Corral -> Parcela -> Datos
                s.datos.values.forEach { corralesMap ->
                    corralesMap.values.forEach { parcelasMap ->
                        parcelasMap.forEach { (pId, d) ->
                            repo.savePeso(s.numero, pId, d.peso)
                            repo.saveMortalidad(s.numero, pId, d.mort)
                            if (d.consAjust != null) repo.saveConsAjust(s.numero, pId, d.consAjust)
                            d.refs.forEach { (tipo, r) ->
                                repo.saveRefAlimento(s.numero, pId, tipo, r.ingreso, r.saldoFin)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            repo.borrarFisicamente(newId)
            return Result.failure(e)
        }

        return Result.success(newId)
    }

    // ── DTOs para el formato experimental ────────────────────────

    private data class ExpRoot(val partida: ExpPartida, val semanas: List<ExpSemana>)
    private data class ExpPartida(val numero: String, val lote: String, val edad: String, val fechaInicio: String, val galeras: List<ExpGalera>)
    private data class ExpGalera(val id: String, val nombre: String, val corrales: List<ExpCorral>)
    private data class ExpCorral(val id: String, val parcelas: List<ExpParcela>)
    private data class ExpParcela(val id: String, val inicio: Int, val pesoInicio: Double)
    private data class ExpSemana(val numero: Int, val fechaInicio: String, val fechaFin: String, val refs: List<String>, val datos: Map<String, Map<String, Map<String, ExpDato>>>)
    private data class ExpDato(val mort: List<Int?>, val peso: Double?, val refs: Map<String, ExpRef>, val consAjust: Double?)
    private data class ExpRef(val ingreso: Double?, val saldoFin: Double?)

    private data class BackupDump(
        val version: Int,
        val exportedAt: Long,
        val partida: Partida?,
        val semanas: List<Semana>,
        val datosPorParcela: Map<String, Map<Int, DatoParcela>>
    )

    // Helpers para escribir celdas con estilo aplicado.
    private fun org.apache.poi.ss.usermodel.Row.put(idx: Int, value: Double, style: CellStyle?) {
        createCell(idx).apply { setCellValue(value); if (style != null) cellStyle = style }
    }
    private fun org.apache.poi.ss.usermodel.Row.put(idx: Int, value: String, style: CellStyle?) {
        createCell(idx).apply { setCellValue(value); if (style != null) cellStyle = style }
    }

    private fun createStyles(wb: XSSFWorkbook): Map<String, CellStyle> {
        val fmt = wb.createDataFormat()

        val headerFont = wb.createFont().apply {
            bold = true; fontHeightInPoints = 12.toShort()
        }
        val colFont = wb.createFont().apply { bold = true; color = IndexedColors.WHITE.index }

        val header = wb.createCellStyle().apply { setFont(headerFont) }
        val subheader = wb.createCellStyle().apply {
            setFont(wb.createFont().apply { bold = true; fontHeightInPoints = 11.toShort(); color = IndexedColors.WHITE.index })
            fillForegroundColor = IndexedColors.GREY_50_PERCENT.index
            fillPattern = FillPatternType.SOLID_FOREGROUND
        }
        // Encabezado de columnas: verde de marca, texto blanco, centrado, con borde.
        val colHeader = wb.createCellStyle().apply {
            setFont(colFont)
            fillForegroundColor = IndexedColors.GREEN.index
            fillPattern = FillPatternType.SOLID_FOREGROUND
            alignment = HorizontalAlignment.CENTER
            verticalAlignment = VerticalAlignment.CENTER
            wrapText = true
            borderBottom = BorderStyle.THIN
        }
        val statHeader = wb.createCellStyle().apply {
            setFont(wb.createFont().apply { bold = true; color = IndexedColors.WHITE.index })
            fillForegroundColor = IndexedColors.GREEN.index
            fillPattern = FillPatternType.SOLID_FOREGROUND
            alignment = HorizontalAlignment.CENTER
            verticalAlignment = VerticalAlignment.CENTER
            wrapText = true
        }

        // ── Estilos numéricos por tipo de dato ──
        fun numStyle(pattern: String) = wb.createCellStyle().apply {
            dataFormat = fmt.getFormat(pattern)
            alignment = HorizontalAlignment.CENTER
        }
        val intStyle = numStyle("0")        // enteros (aves, saldo)
        val dec1     = numStyle("0.0")      // pesos, consumo, GDP
        val dec3     = numStyle("0.000")    // FCR
        val pct      = numStyle("0.0%")     // mortalidad / CV (se escribe la fracción)
        val textC    = wb.createCellStyle().apply { alignment = HorizontalAlignment.CENTER }

        return mapOf(
            "header" to header,
            "subheader" to subheader,
            "colHeader" to colHeader,
            "statHeader" to statHeader,
            "int" to intStyle,
            "dec1" to dec1,
            "dec3" to dec3,
            "pct" to pct,
            "text" to textC
        )
    }
}
