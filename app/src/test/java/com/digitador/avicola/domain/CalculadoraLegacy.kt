package com.digitador.avicola.domain

import kotlin.math.sqrt

/**
 * Copia VERBATIM del motor de cálculo tal como estaba antes de unificarlo
 * (commit c796435). Solo existe en los tests: [CalculadoraEquivalenciaTest]
 * compara el motor nuevo contra este para demostrar que el refactor no movió
 * ningún número. No usar en producción.
 */
object CalculadoraLegacy {

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
            cvPeso = if (promArr.size >= 2) stdevLegacy(promArr) / promArr.average() else null,
            cgr = if (cgrCount > 0) spCgr / cgrCount else null,
            fep = fep,
            fcrAdj = fcrAdj,
            ratio = ratio
        )
    }


    private fun stdevLegacy(arr: List<Double>): Double {
        if (arr.size < 2) return 0.0
        val m = arr.average()
        return sqrt(arr.sumOf { (it - m) * (it - m) } / (arr.size - 1))
    }

    /**
     * Copia VERBATIM del cálculo por parcela que vivía duplicado dentro de
     * ExportService.exportEstadistica, para comparar fila a fila el Excel.
     */
    fun filaEstadisticaLegacy(
        parcela: Parcela,
        semNum: Int,
        semana: Semana,
        todasSemanas: List<Semana>,
        datosByPar: Map<Int, DatoParcela>,
        exclPorSem: Map<Int, Set<String>>
    ): Map<String, Double> {
        val d = datosByPar[semNum] ?: DatoParcela(semNum, parcela.id)

        val inicioSem = getSaldoAnterior(semNum, parcela.id, parcela, datosByPar)
        val mortSem = d.mort.sumOf { it ?: 0 }
        val saldoFin = inicioSem - mortSem
        val pesoGave = d.peso ?: 0.0

        var tsR = 0.0; var sfR = 0.0
        val exclSem = exclPorSem[semNum] ?: emptySet()
        for (tipo in semana.refsActivas) {
            if (tipo in exclSem) continue
            tsR += getSaldoAlimRef(semNum, parcela.id, tipo, datosByPar) + (d.refs[tipo]?.ingreso ?: 0.0)
            sfR += d.refs[tipo]?.saldoFin ?: 0.0
        }
        val alimKg = (d.consAjust?.takeIf { it >= 0.0 }) ?: (tsR - sfR)
        val consGaveSem = if (saldoFin > 0 && alimKg > 0) alimKg * GRAMOS_POR_KG / saldoFin else 0.0

        var consAcumGave = 0.0
        var runningSaldo = parcela.inicio
        for (sn in 1..semNum) {
            val ds = datosByPar[sn]
            val s = todasSemanas.find { it.numero == sn } ?: continue
            val ms = ds?.mort?.sumOf { it ?: 0 } ?: 0
            val sf = runningSaldo - ms
            if (sf > 0) {
                var tsRs = 0.0; var sfRs = 0.0
                val exclSn = exclPorSem[sn] ?: emptySet()
                for (tipo in s.refsActivas) {
                    if (tipo in exclSn) continue
                    tsRs += (datosByPar[sn-1]?.refs?.get(tipo)?.saldoFin ?: 0.0) + (ds?.refs?.get(tipo)?.ingreso ?: 0.0)
                    sfRs += ds?.refs?.get(tipo)?.saldoFin ?: 0.0
                }
                val aKg = (ds?.consAjust?.takeIf { it >= 0.0 }) ?: (tsRs - sfRs)
                consAcumGave += if (aKg > 0) aKg * GRAMOS_POR_KG / sf else 0.0
            }
            runningSaldo = sf
        }

        val gainSem = if (semNum == 1) {
            pesoGave
        } else {
            val prevPeso = datosByPar[semNum - 1]?.peso ?: 0.0
            if (pesoGave > 0 && prevPeso > 0) pesoGave - prevPeso else 0.0
        }

        val fcrSem = if (gainSem > 0 && consGaveSem > 0) consGaveSem / gainSem else 0.0
        val fcrAcum = if (pesoGave > 0 && consAcumGave > 0) consAcumGave / pesoGave else 0.0
        val gdpSem = gainSem / 7.0
        val gdpLin = if (pesoGave > 0) pesoGave / (semNum * 7.0) else 0.0
        val mortPct = if (inicioSem > 0) (mortSem.toDouble() / inicioSem) else 0.0

        var totMortAcum = 0
        for (sn in 1..semNum) totMortAcum += (datosByPar[sn]?.mort?.sumOf { it ?: 0 } ?: 0)
        val mortAcumPct = if (parcela.inicio > 0) (totMortAcum.toDouble() / parcela.inicio) else 0.0

        val actualPrevPeso = if (semNum == 1) {
            if (parcela.inicio > 0) parcela.pesoInicio / parcela.inicio else 0.0
        } else {
            datosByPar[semNum - 1]?.peso ?: 0.0
        }
        val ratio = if (semNum == 1 && actualPrevPeso > 0) pesoGave / actualPrevPeso else 0.0

        val fcrAdj25 = if (semNum >= 5 && fcrAcum > 0 && pesoGave > 0) fcrAcum + (2500.0 - pesoGave) / 3200.0 else 0.0
        val fcrAdj20 = if (semNum >= 5 && fcrAcum > 0 && pesoGave > 0) fcrAcum + (2000.0 - pesoGave) / 3200.0 else 0.0
        val fcrAdj27 = if (semNum >= 5 && fcrAcum > 0 && pesoGave > 0) fcrAcum + (2700.0 - pesoGave) / 3200.0 else 0.0

        return mapOf(
            "inicio" to inicioSem.toDouble(),
            "mort" to mortSem.toDouble(),
            "saldo" to saldoFin.toDouble(),
            "peso" to pesoGave,
            "consSem" to consGaveSem,
            "consAcum" to consAcumGave,
            "fcrSem" to fcrSem,
            "fcrAcum" to fcrAcum,
            "gdpSem" to gdpSem,
            "gdpLin" to gdpLin,
            "mortPct" to mortPct,
            "mortAcumPct" to mortAcumPct,
            "ratio" to ratio,
            "fcrAdj20" to fcrAdj20,
            "fcrAdj25" to fcrAdj25,
            "fcrAdj27" to fcrAdj27
        )
    }
}
