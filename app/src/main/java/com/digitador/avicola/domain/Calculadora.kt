package com.digitador.avicola.domain

import kotlin.math.sqrt

object Calculadora {

    /** El alimento (ingreso/saldo/ajuste) se digita en KILOGRAMOS; el consumo por
     *  ave se expresa en GRAMOS, por eso se multiplica por este factor al dividir. */
    const val GRAMOS_POR_KG = 1000.0

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

    // Métricas de un corral (SUMAPRODUCTO ponderado por saldo)
    fun computeMetricasCorral(
        corral: Corral,
        semNum: Int,
        semana: Semana,
        todasSemanas: List<Semana>,
        datosPorParcela: Map<String, Map<Int, DatoParcela>>,
        refsExcluidasPorSemana: Map<Int, Set<String>> = emptyMap()
    ): MetricasCorral? {
        if (corral.parcelas.isEmpty()) return null

        val consumoAcumByParcela = mutableMapOf<String, Double>()
        val mortAcumByParcela = mutableMapOf<String, Int>()

        for (par in corral.parcelas) {
            val datosByPar = datosPorParcela[par.id] ?: emptyMap()
            var consumoAcum = 0.0
            var mortAcum = 0
            var runningSaldo = par.inicio
            
            for (sn in 1..semNum) {
                val s = todasSemanas.find { it.numero == sn } ?: continue
                val d = datosByPar[sn]

                val mort = d?.mort?.sumOf { it ?: 0 } ?: 0
                val saldoActual = runningSaldo - mort
                mortAcum += mort

                if (saldoActual > 0) {
                    var tsR = 0.0; var sfR = 0.0
                    val exclSn = refsExcluidasPorSemana[sn] ?: emptySet()
                    for (tipo in s.refsActivas) {
                        if (tipo in exclSn) continue   // referencia desechada del cálculo (esa semana)
                        // Saldo anterior del alimento es el saldo fin del anterior
                        val saldoAntAlim = if (sn == 1) 0.0 else (datosByPar[sn-1]?.refs?.get(tipo)?.saldoFin ?: 0.0)
                        tsR += saldoAntAlim + (d?.refs?.get(tipo)?.ingreso ?: 0.0)
                        sfR += d?.refs?.get(tipo)?.saldoFin ?: 0.0
                    }
                    // consAjust, si está presente y no es negativo, REEMPLAZA el cálculo
                    // por referencias (incluido un ajuste explícito de 0). null = sin ajuste.
                    // Alimento (ING/SAL/ADJ) se digita en KG → consumo por ave en gramos.
                    val alimKg = (d?.consAjust?.takeIf { it >= 0.0 }) ?: (tsR - sfR)
                    if (alimKg > 0) {
                        consumoAcum += alimKg * GRAMOS_POR_KG / saldoActual
                    }
                }
                runningSaldo = saldoActual
            }
            consumoAcumByParcela[par.id] = consumoAcum
            mortAcumByParcela[par.id] = mortAcum
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

        for (par in corral.parcelas) {
            val datosByPar = datosPorParcela[par.id] ?: emptyMap()
            val saldoAnt = getSaldoAnterior(semNum, par.id, par, datosByPar)
            val d = datosByPar[semNum]
            val mort = d?.mort?.sumOf { it ?: 0 } ?: 0
            val saldo = saldoAnt - mort
            
            totInicio += par.inicio
            totMortAcum += (mortAcumByParcela[par.id] ?: 0)

            if (saldo <= 0) continue
            totSaldo += saldo

            val pesoGave = d?.peso ?: 0.0
            if (pesoGave > 0) { 
                spPeso += pesoGave * saldo
                promArr.add(pesoGave) 
            }

            // Ganancia de peso (Gain): 
            // En SEMANA 1 se toma todo el peso (según estadisticas_ejemplo.csv)
            // En SEMANAS 2+ se resta el peso anterior.
            val gain = if (semNum == 1) {
                pesoGave
            } else {
                val prevGave = if (semNum > 1) datosByPar[semNum - 1]?.peso ?: 0.0 else 0.0
                if (pesoGave > 0 && prevGave > 0) pesoGave - prevGave else 0.0
            }
            
            // Peso previo real (solo para el cálculo de spPrevPeso / Ratio)
            val actualPrevGave = if (semNum > 1) {
                datosByPar[semNum - 1]?.peso ?: 0.0
            } else if (par.inicio > 0) {
                par.pesoInicio / par.inicio
            } else 0.0
            
            if (actualPrevGave > 0) spPrevPeso += actualPrevGave * saldo

            // Consumo semanal
            var tsR = 0.0; var sfR = 0.0
            val exclSem = refsExcluidasPorSemana[semNum] ?: emptySet()
            for (tipo in semana.refsActivas) {
                if (tipo in exclSem) continue   // referencia desechada del cálculo (esa semana)
                tsR += getSaldoAlimRef(semNum, par.id, tipo, datosByPar) + (d?.refs?.get(tipo)?.ingreso ?: 0.0)
                sfR += d?.refs?.get(tipo)?.saldoFin ?: 0.0
            }
            // consAjust, si está presente y no es negativo, REEMPLAZA el cálculo por
            // referencias (incluido 0 explícito). null = sin ajuste → se usa el de refs.
            // Alimento (ING/SAL/ADJ) se digita en KG → consumo por ave en gramos.
            val alimKg = (d?.consAjust?.takeIf { it >= 0.0 }) ?: (tsR - sfR)
            val consGave = if (alimKg > 0) alimKg * GRAMOS_POR_KG / saldo else 0.0
            spCons += consGave * saldo

            val cAcum = consumoAcumByParcela[par.id] ?: 0.0
            spConsAcum += cAcum * saldo

            if (gain > 0) {
                spGdp += (gain / 7.0) * saldo
                if (consGave > 0) {
                    spFcr += (consGave / gain) * saldo
                    fcrCount += saldo
                }
            }

            val promInicio = if (par.inicio > 0) par.pesoInicio / par.inicio else 0.0
            if (pesoGave > 0 && promInicio > 0) { spCgr += (pesoGave / promInicio) * saldo; cgrCount += saldo }
        }

        if (totSaldo <= 0) return null

        val promPeso = spPeso / totSaldo
        val consumoGave = spCons / totSaldo
        val consumoAcum = spConsAcum / totSaldo
        
        // FCR Semanal calculado como el promedio ponderado de los FCRs de las parcelas
        val fcrSem = if (fcrCount > 0) spFcr / fcrCount else null
        val fcrAcum = if (promPeso > 0 && consumoAcum > 0) consumoAcum / promPeso else null
        
        val mortAcumPct = if (totInicio > 0) totMortAcum.toDouble() / totInicio else 0.0
        val edadDias = semNum * 7.0
        
        val fep = if (fcrAcum != null && fcrAcum > 0 && edadDias > 0) {
            val viabilidad = 1.0 - mortAcumPct
            ((viabilidad * (promPeso / 1000.0)) / (edadDias * fcrAcum)) * 100.0
        } else null

        val fcrAdj = if (semNum >= 5 && fcrAcum != null && promPeso > 0) fcrAcum + (2500.0 - promPeso) / 3200.0 else null
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
     * Valida que una semana esté COMPLETAMENTE digitada: cada parcela con aves debe
     * tener peso y, para cada alimento activo, ingreso y saldo final. Devuelve
     * cuántas parcelas faltan por cada concepto.
     */
    fun validarSemana(
        semNum: Int,
        partida: Partida,
        semana: Semana?,
        datosPorParcela: Map<String, Map<Int, DatoParcela>>
    ): ValidacionSemana {
        val refsActivas = semana?.refsActivas ?: listOf("BR1")
        var total = 0; var faltanPeso = 0; var faltanAlimento = 0
        for (g in partida.galeras) for (k in g.corrales) for (par in k.parcelas) {
            if (par.inicio == 0) continue
            total++
            val d = datosPorParcela[par.id]?.get(semNum)
            if (d?.peso == null) faltanPeso++
            // El alimento solo exige el INGRESO (ING). El saldo final (SAL) es opcional.
            val alimentoOk = d != null && refsActivas.all { tipo ->
                d.refs[tipo]?.ingreso != null
            }
            if (!alimentoOk) faltanAlimento++
        }
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
