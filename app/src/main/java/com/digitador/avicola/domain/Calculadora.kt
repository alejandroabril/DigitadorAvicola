package com.digitador.avicola.domain

import kotlin.math.sqrt

/**
 * Motor de cálculo del ensayo. **Única** implementación de las fórmulas: la pantalla,
 * el PDF y el Excel consumen todos este objeto, así que no pueden dar números distintos.
 *
 * La unidad básica es la jaula ([computeMetricasParcela]); [computeMetricasCorral] no
 * recalcula nada, solo pondera esas métricas por saldo de aves.
 */
object Calculadora {

    /** El alimento (ingreso/saldo/ajuste) se digita en KILOGRAMOS; el consumo por
     *  ave se expresa en GRAMOS, por eso se multiplica por este factor al dividir. */
    const val GRAMOS_POR_KG = 1000.0

    const val DIAS_POR_SEMANA = 7.0

    /** Antes de esta semana el FCR ajustado no es representativo, así que no se reporta. */
    const val FCR_ADJ_DESDE_SEMANA = 5
    /** Factor estándar del ensayo para corregir el FCR hacia un peso objetivo. */
    const val FCR_ADJ_FACTOR = 3200.0
    /** Pesos objetivo (g) del FCR ajustado que reporta el Excel. */
    const val FCR_ADJ_OBJETIVO_2_0 = 2000.0
    const val FCR_ADJ_OBJETIVO_2_5 = 2500.0
    const val FCR_ADJ_OBJETIVO_2_7 = 2700.0

    // Saldo de aves al inicio de la semana (Optimizado: iterativo)
    fun getSaldoAnterior(
        semNum: Int,
        parcelaId: String,
        parcela: Parcela,
        datosByParcela: Map<Int, DatoParcela>
    ): Int {
        var currentSaldo = parcela.inicio
        for (i in 1 until semNum) {
            val mort = datosByParcela[i]?.mort?.sumOf { it ?: 0 } ?: 0
            currentSaldo -= mort
        }
        // Nunca devolver saldo negativo (mortalidad mal digitada > aves) para no
        // arrastrar negativos a la UI ni a los cálculos.
        return currentSaldo.coerceAtLeast(0)
    }

    // Saldo anterior de referencia de alimento
    fun getSaldoAlimRef(
        semNum: Int,
        parcelaId: String,
        tipo: String,
        datosByParcela: Map<Int, DatoParcela>
    ): Double {
        if (semNum <= 1) return 0.0
        return datosByParcela[semNum - 1]?.refs?.get(tipo)?.saldoFin ?: 0.0
    }

    /**
     * Alimento consumido por una jaula en [semNum], en KG.
     *
     * Si hay ajuste manual (`consAjust`) no negativo, REEMPLAZA el cálculo por
     * referencias — incluido un 0 explícito; `null` significa "sin ajuste". Las
     * referencias marcadas como excluidas esa semana no entran en la cuenta.
     */
    private fun alimentoKgSemana(
        semNum: Int,
        semana: Semana,
        datosByParcela: Map<Int, DatoParcela>,
        refsExcluidas: Set<String>
    ): Double {
        val d = datosByParcela[semNum]
        d?.consAjust?.takeIf { it >= 0.0 }?.let { return it }

        var entradas = 0.0
        var saldoFinal = 0.0
        for (tipo in semana.refsActivas) {
            if (tipo in refsExcluidas) continue
            entradas += getSaldoAlimRef(semNum, "", tipo, datosByParcela) + (d?.refs?.get(tipo)?.ingreso ?: 0.0)
            saldoFinal += d?.refs?.get(tipo)?.saldoFin ?: 0.0
        }
        return entradas - saldoFinal
    }

    /**
     * Arrastre de una jaula semana a semana: saldo de aves, mortalidad acumulada y
     * consumo acumulado. Se avanza una semana por vez con [avanzar] y se fotografía
     * el estado con [snapshot], de modo que calcular N semanas cuesta N pasos y no
     * N recorridos desde la semana 1.
     */
    private class ArrastreParcela(private val parcela: Parcela) {
        /** Saldo de aves en curso. Puede ser negativo si la mortalidad está mal digitada. */
        private var saldoCorriente = parcela.inicio
        private var mortAcum = 0
        private var consAcumGave = 0.0

        // Estado de la última semana procesada.
        private var saldoAnterior = parcela.inicio
        private var mortSem = 0
        private var alimKgSem = 0.0
        private var consGave = 0.0

        fun avanzar(
            sn: Int,
            semana: Semana?,
            datosByParcela: Map<Int, DatoParcela>,
            refsExcluidas: Set<String>
        ) {
            val d = datosByParcela[sn]
            val mort = d?.mort?.sumOf { it ?: 0 } ?: 0
            mortAcum += mort
            val saldoAlCerrar = saldoCorriente - mort

            // Sin registro de la semana no se sabe qué alimentos estaban activos.
            val alimKg = if (semana == null) 0.0
                else alimentoKgSemana(sn, semana, datosByParcela, refsExcluidas)

            if (saldoAlCerrar > 0 && alimKg > 0) {
                consAcumGave += alimKg * GRAMOS_POR_KG / saldoAlCerrar
            }

            // El saldo REPORTADO nunca es negativo, aunque el arrastre interno sí
            // pueda serlo con mortalidad mal digitada.
            saldoAnterior = saldoCorriente.coerceAtLeast(0)
            mortSem = mort
            alimKgSem = alimKg
            val saldoFin = saldoAnterior - mort
            consGave = if (saldoFin > 0 && alimKg > 0) alimKg * GRAMOS_POR_KG / saldoFin else 0.0

            saldoCorriente = saldoAlCerrar
        }

        fun snapshot(semNum: Int, datosByParcela: Map<Int, DatoParcela>): MetricasParcela {
            val pesoGave = datosByParcela[semNum]?.peso ?: 0.0
            val pesoRecepcion = if (parcela.inicio > 0) parcela.pesoInicio / parcela.inicio else 0.0
            val pesoPrevio = if (semNum > 1) datosByParcela[semNum - 1]?.peso ?: 0.0 else pesoRecepcion

            // Ganancia de peso: en la SEMANA 1 se toma todo el peso (según la planilla
            // del ensayo); desde la 2 se resta el peso de la semana anterior.
            val gain = if (semNum == 1) pesoGave
                else if (pesoGave > 0 && pesoPrevio > 0) pesoGave - pesoPrevio
                else 0.0

            return MetricasParcela(
                semNum = semNum,
                inicioLote = parcela.inicio,
                saldoAnterior = saldoAnterior,
                mortSem = mortSem,
                saldo = saldoAnterior - mortSem,
                mortAcum = mortAcum,
                pesoGave = pesoGave,
                pesoPrevio = pesoPrevio,
                pesoRecepcion = pesoRecepcion,
                gain = gain,
                alimKgSem = alimKgSem,
                consGave = consGave,
                consAcumGave = consAcumGave
            )
        }
    }

    /**
     * Métricas de UNA jaula en [semNum]. Recorre el lote desde la semana 1 para
     * arrastrar saldo de aves, mortalidad acumulada y consumo acumulado.
     *
     * Si necesitás varias semanas de la misma jaula, usá [computeSerieParcela]: repetir
     * esta llamada por semana vuelve el cálculo cuadrático.
     */
    fun computeMetricasParcela(
        parcela: Parcela,
        semNum: Int,
        todasSemanas: List<Semana>,
        datosByParcela: Map<Int, DatoParcela>,
        refsExcluidasPorSemana: Map<Int, Set<String>> = emptyMap()
    ): MetricasParcela {
        val arrastre = ArrastreParcela(parcela)
        for (sn in 1..semNum) {
            arrastre.avanzar(
                sn,
                todasSemanas.find { it.numero == sn },
                datosByParcela,
                refsExcluidasPorSemana[sn] ?: emptySet()
            )
        }
        return arrastre.snapshot(semNum, datosByParcela)
    }

    /**
     * Métricas de UNA jaula en TODAS las semanas, en un solo recorrido.
     *
     * Devuelve lo mismo que llamar [computeMetricasParcela] para cada semana, pero sin
     * rehacer el arrastre desde la semana 1 en cada una: pasa de O(semanas²) a
     * O(semanas). El Excel escribe una fila por jaula y semana, así que es ahí donde
     * más pesa.
     */
    fun computeSerieParcela(
        parcela: Parcela,
        semanas: List<Semana>,
        datosByParcela: Map<Int, DatoParcela>,
        refsExcluidasPorSemana: Map<Int, Set<String>> = emptyMap()
    ): Map<Int, MetricasParcela> {
        val maxSem = semanas.maxOfOrNull { it.numero } ?: return emptyMap()
        val porNumero = semanas.associateBy { it.numero }

        val arrastre = ArrastreParcela(parcela)
        val salida = LinkedHashMap<Int, MetricasParcela>(semanas.size)
        for (sn in 1..maxSem) {
            arrastre.avanzar(sn, porNumero[sn], datosByParcela, refsExcluidasPorSemana[sn] ?: emptySet())
            if (sn in porNumero) salida[sn] = arrastre.snapshot(sn, datosByParcela)
        }
        return salida
    }

    /**
     * Métricas de un corral (tratamiento): promedio ponderado por saldo de aves de las
     * métricas de sus jaulas — el SUMAPRODUCTO de la planilla. Las jaulas sin aves vivas
     * no pesan en los promedios, pero sí cuentan para la mortalidad acumulada.
     */
    fun computeMetricasCorral(
        corral: Corral,
        semNum: Int,
        semana: Semana,
        todasSemanas: List<Semana>,
        datosPorParcela: Map<String, Map<Int, DatoParcela>>,
        refsExcluidasPorSemana: Map<Int, Set<String>> = emptyMap()
    ): MetricasCorral? = computeMetricasDeParcelas(
        corral.parcelas, semNum, semana, todasSemanas, datosPorParcela, refsExcluidasPorSemana
    )

    /**
     * Lo mismo que [computeMetricasCorral] para un grupo CUALQUIERA de jaulas: las de un
     * tratamiento, las del mismo tratamiento en varias galeras (la galera es el bloque
     * del ensayo, así que el K1 de cada una es el mismo tratamiento) o las del lote
     * entero. El promedio siempre se pondera por saldo de aves, así que juntar jaulas de
     * distintas galeras da la media del conjunto, no la media de las medias.
     */
    fun computeMetricasDeParcelas(
        parcelas: List<Parcela>,
        semNum: Int,
        semana: Semana,
        todasSemanas: List<Semana>,
        datosPorParcela: Map<String, Map<Int, DatoParcela>>,
        refsExcluidasPorSemana: Map<Int, Set<String>> = emptyMap()
    ): MetricasCorral? {
        if (parcelas.isEmpty()) return null

        // La semana que se está viendo manda, aunque no esté en la lista recibida.
        val semanas = if (todasSemanas.any { it.numero == semNum }) todasSemanas else todasSemanas + semana

        val metricas = parcelas.map { par ->
            computeMetricasParcela(par, semNum, semanas, datosPorParcela[par.id] ?: emptyMap(), refsExcluidasPorSemana)
        }

        var totSaldo = 0
        var totInicio = 0
        var totMortAcum = 0
        var spPeso = 0.0
        var spCons = 0.0
        var spConsAcum = 0.0
        var spPrevPeso = 0.0
        var spFcr = 0.0
        var fcrCount = 0
        var spGdp = 0.0
        var spCgr = 0.0
        var cgrCount = 0
        val promArr = mutableListOf<Double>()

        for (m in metricas) {
            totInicio += m.inicioLote
            totMortAcum += m.mortAcum

            val saldo = m.saldo
            if (saldo <= 0) continue
            totSaldo += saldo

            if (m.pesoGave > 0) {
                spPeso += m.pesoGave * saldo
                promArr.add(m.pesoGave)
            }
            if (m.pesoPrevio > 0) spPrevPeso += m.pesoPrevio * saldo

            spCons += m.consGave * saldo
            spConsAcum += m.consAcumGave * saldo

            if (m.gain > 0) {
                spGdp += m.gdpSem * saldo
                if (m.consGave > 0) {
                    spFcr += (m.consGave / m.gain) * saldo
                    fcrCount += saldo
                }
            }

            m.cgr?.let { spCgr += it * saldo; cgrCount += saldo }
        }

        if (totSaldo <= 0) return null

        val promPeso = spPeso / totSaldo
        val consumoGave = spCons / totSaldo
        val consumoAcum = spConsAcum / totSaldo

        // FCR semanal = promedio ponderado de los FCR de las jaulas.
        val fcrSem = if (fcrCount > 0) spFcr / fcrCount else null
        val fcrAcum = if (promPeso > 0 && consumoAcum > 0) consumoAcum / promPeso else null

        val mortAcumPct = if (totInicio > 0) totMortAcum.toDouble() / totInicio else 0.0
        val edadDias = semNum * DIAS_POR_SEMANA

        val fep = if (fcrAcum != null && fcrAcum > 0 && edadDias > 0) {
            val viabilidad = 1.0 - mortAcumPct
            ((viabilidad * (promPeso / 1000.0)) / (edadDias * fcrAcum)) * 100.0
        } else null

        val fcrAdj = if (semNum >= FCR_ADJ_DESDE_SEMANA && fcrAcum != null && promPeso > 0)
            fcrAcum + (FCR_ADJ_OBJETIVO_2_5 - promPeso) / FCR_ADJ_FACTOR else null

        // El ratio del corral se calcula sobre los promedios ponderados, no promediando
        // los ratios de cada jaula.
        val ratio = if (semNum == 1 && spPrevPeso > 0) promPeso / (spPrevPeso / totSaldo) else null

        return MetricasCorral(
            saldo = totSaldo,
            promPeso = promPeso,
            consumoGave = consumoGave,
            consumoAcum = consumoAcum,
            fcrSem = fcrSem,
            fcrAcum = fcrAcum,
            mortAcumPct = mortAcumPct,
            gdpSem = spGdp / totSaldo,
            gdpLineal = if (promPeso > 0) promPeso / edadDias else 0.0,
            cvPeso = if (promArr.size >= 2) stdev(promArr) / promArr.average() else null,
            cgr = if (cgrCount > 0) spCgr / cgrCount else null,
            fep = fep,
            fcrAdj = fcrAdj,
            ratio = ratio
        )
    }

    /**
     * KPIs del lote a partir de las métricas YA calculadas de sus tratamientos:
     * mismo criterio que dentro del corral, promedio ponderado por saldo de aves.
     * Recibe las métricas en vez de recalcularlas para no recorrer el lote dos veces.
     */
    fun agregarKpiGlobal(metricas: Collection<MetricasCorral>): KpiGlobal {
        var totSaldo = 0
        var spPeso = 0.0
        var spFcr = 0.0
        var fcrCount = 0

        for (m in metricas) {
            totSaldo += m.saldo
            spPeso += m.promPeso * m.saldo
            m.fcrSem?.let { fcr ->
                spFcr += fcr * m.saldo
                fcrCount += m.saldo
            }
        }

        return KpiGlobal(
            saldo = totSaldo,
            pesoProm = if (totSaldo > 0) spPeso / totSaldo else 0.0,
            fcrSem = if (fcrCount > 0) spFcr / fcrCount else 0.0
        )
    }

    /**
     * Valida que una semana esté COMPLETAMENTE digitada: cada parcela con aves debe
     * tener peso y, para cada alimento activo, ingreso y saldo final. Devuelve
     * cuántas parcelas faltan por cada concepto.
     */
    fun validarSemana(
        semNum: Int,
        partida: Partida,
        semana: Semana?,
        datosPorParcela: Map<String, Map<Int, DatoParcela>>,
        refsExcluidas: Set<String> = emptySet()
    ): ValidacionSemana {
        val refsActivas = semana?.refsActivas ?: listOf("BR1")
        // Referencias EXIGIDAS = activas y NO marcadas como "Excluir".
        val refsReq = refsActivas.filter { it !in refsExcluidas }
        val parcelas = partida.galeras
            .flatMap { it.corrales }.flatMap { it.parcelas }
            .filter { it.inicio != 0 }
        val total = parcelas.size

        var faltanPeso = 0
        for (par in parcelas) {
            if (datosPorParcela[par.id]?.get(semNum)?.peso == null) faltanPeso++
        }

        fun tieneIng(par: Parcela, tipo: String) =
            datosPorParcela[par.id]?.get(semNum)?.refs?.get(tipo)?.ingreso != null

        // El alimento se da por COMPLETO (no bloquea) si:
        //  · no hay referencias exigidas (todas excluidas), o
        //  · alguna referencia exigida tiene TODA su columna de INGRESO llena
        //    (todas las parcelas con aves), o
        //  · cada parcela tiene el ingreso de todas las referencias exigidas.
        // Si no, se reporta cuántas parcelas no tienen completo el ingreso exigido.
        val algunaColumnaCompleta = refsReq.any { tipo -> parcelas.all { tieneIng(it, tipo) } }
        val faltanAlimento = if (refsReq.isEmpty() || algunaColumnaCompleta) 0
            else parcelas.count { par -> !refsReq.all { tieneIng(par, it) } }

        return ValidacionSemana(total, faltanPeso, faltanAlimento)
    }

    fun calcProgreso(
        semNum: Int,
        partida: Partida,
        semana: Semana?,
        datosPorParcela: Map<String, Map<Int, DatoParcela>>
    ): ProgresoSemana {
        var total = 0; var filled = 0; var mtotal = 0; var mfilled = 0
        val refsActivas = semana?.refsActivas ?: listOf("BR1")
        for (g in partida.galeras) for (k in g.corrales) for (par in k.parcelas) {
            if (par.inicio == 0) continue
            val d = datosPorParcela[par.id]?.get(semNum)
            total += 2
            if (d?.peso != null) filled++
            if (d != null && refsActivas.any { d.refs[it]?.ingreso != null }) filled++
            mtotal += 7
            mfilled += d?.mort?.count { it != null } ?: 0
        }
        return ProgresoSemana(semNum, filled, total, mfilled, mtotal)
    }

    /**
     * Progreso de TODAS las semanas en un solo recorrido del lote.
     *
     * Equivale a llamar [calcProgreso] semana por semana, pero recorre el árbol
     * galera→corral→jaula una sola vez en lugar de una vez por semana: la barra de
     * navegación pinta un porcentaje por cada semana y antes eso costaba N recorridos
     * completos en cada recomposición.
     */
    fun calcProgresoTodas(
        partida: Partida,
        semanas: List<Semana>,
        datosPorParcela: Map<String, Map<Int, DatoParcela>>
    ): Map<Int, ProgresoSemana> {
        if (semanas.isEmpty()) return emptyMap()

        // Por semana: [filled, total, mfilled, mtotal]
        val acum = semanas.associate { it.numero to IntArray(4) }

        for (g in partida.galeras) for (k in g.corrales) for (par in k.parcelas) {
            if (par.inicio == 0) continue
            val datos = datosPorParcela[par.id]
            for (s in semanas) {
                val a = acum[s.numero] ?: continue
                val d = datos?.get(s.numero)
                a[1] += 2
                if (d?.peso != null) a[0]++
                if (d != null && s.refsActivas.any { d.refs[it]?.ingreso != null }) a[0]++
                a[3] += 7
                a[2] += d?.mort?.count { it != null } ?: 0
            }
        }

        return acum.mapValues { (num, a) -> ProgresoSemana(num, a[0], a[1], a[2], a[3]) }
    }

    fun calcProgresoGalera(
        galera: Galera,
        semana: Semana?,
        semNum: Int,
        datosPorParcela: Map<String, Map<Int, DatoParcela>>
    ): ProgresoSemana {
        val refsActivas = semana?.refsActivas ?: listOf("BR1")
        var total = 0; var filled = 0
        for (k in galera.corrales) for (par in k.parcelas) {
            if (par.inicio == 0) continue
            val d = datosPorParcela[par.id]?.get(semNum)
            total += 2
            if (d?.peso != null) filled++
            if (d != null && refsActivas.any { d.refs[it]?.ingreso != null }) filled++
        }
        return ProgresoSemana(semNum, filled, total, 0, 0)
    }

    private fun stdev(arr: List<Double>): Double {
        if (arr.size < 2) return 0.0
        val m = arr.average()
        return sqrt(arr.sumOf { (it - m) * (it - m) } / (arr.size - 1))
    }

    /**
     * Coeficiente de variación del peso de un grupo cualquiera de parcelas (un
     * tratamiento, una galera o todo el lote). Igual que la planilla: toma el peso
     * promedio (g/ave) de cada jaula con aves vivas y peso digitado en [semNum], y
     * calcula DesvEst muestral / promedio. Devuelve null si hay menos de 2 jaulas.
     */
    fun cvPesoDeParcelas(
        parcelas: List<Parcela>,
        semNum: Int,
        datosPorParcela: Map<String, Map<Int, DatoParcela>>
    ): CvPeso? {
        val pesos = mutableListOf<Double>()
        for (par in parcelas) {
            val datosByPar = datosPorParcela[par.id] ?: emptyMap()
            val saldoAnt = getSaldoAnterior(semNum, par.id, par, datosByPar)
            val mort = datosByPar[semNum]?.mort?.sumOf { it ?: 0 } ?: 0
            val saldo = saldoAnt - mort
            val peso = datosByPar[semNum]?.peso ?: 0.0
            if (saldo > 0 && peso > 0) pesos.add(peso)
        }
        if (pesos.size < 2) return null
        return CvPeso(n = pesos.size, media = pesos.average(), desv = stdev(pesos))
    }
}
