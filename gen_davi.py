import json, random, datetime

random.seed(42)

UID = "ejemplo-1galpon-4trat-s6-0001"
FECHA_INICIO = datetime.date(2026, 4, 20)

# 1 galpón (G1) → líneas físicas A y B. 4 tratamientos, 5 parcelas c/u.
trat_parcelas = {
    "T1": [f"G1A0{i}" for i in range(1, 6)],   # G1A01..G1A05
    "T2": [f"G1A0{i}" for i in range(6, 10)] + ["G1A10"],  # G1A06..G1A10
    "T3": [f"G1B0{i}" for i in range(1, 6)],   # G1B01..G1B05
    "T4": [f"G1B0{i}" for i in range(6, 10)] + ["G1B10"],  # G1B06..G1B10
}

AVES_INI = 120          # aves por parcela
PESO_INI_AVE = 45.0     # g/ave a la recepción

# ── Partida (estructura) ──
corrales = []
for t, pids in trat_parcelas.items():
    parcelas = [
        {"id": pid, "corralId": f"G1-{t}", "inicio": AVES_INI, "pesoInicio": PESO_INI_AVE * AVES_INI}
        for pid in pids
    ]
    corrales.append({"id": f"G1-{t}", "galeraId": "G1", "parcelas": parcelas})

partida = {
    "id": 0,
    "numero": "0001",
    "lote": "Ejemplo S6",
    "edad": "0",
    "fechaInicio": FECHA_INICIO.isoformat(),
    "usarGuia": True,
    "finalizada": False,
    "uid": UID,
    "galeras": [{"id": "G1", "nombre": "Galera 1", "corrales": corrales}],
}

# ── Semanas 1..6 ──
semanas = []
for n in range(1, 7):
    ini = FECHA_INICIO + datetime.timedelta(days=(n - 1) * 7)
    fin = ini + datetime.timedelta(days=6)
    semanas.append({
        "numero": n,
        "fechaInicio": ini.isoformat(),
        "fechaFin": fin.isoformat(),
        "refsActivas": ["BR1"],
    })

# Perfil de peso (g/ave) por semana — curva de engorde aproximada
peso_sem = {1: 180, 2: 460, 3: 900, 4: 1450, 5: 2050, 6: 2650}
# Consumo objetivo en GRAMOS por ave por semana (FCR acumulado final ~1.6)
cons_gave_sem = {1: 150, 2: 400, 3: 620, 4: 850, 5: 1050, 6: 1250}

# ── Datos por parcela / semana ──
datos = {}
todas_parcelas = [pid for pids in trat_parcelas.values() for pid in pids]
for pid in todas_parcelas:
    por_sem = {}
    for n in range(1, 7):
        # Mortalidad: 7 días, mayormente 0, alguna baja ocasional
        mort = [random.choice([0, 0, 0, 0, 0, 1]) if random.random() < 0.5 else 0 for _ in range(7)]
        # Peso promedio g/ave con pequeña variación por parcela
        peso = round(peso_sem[n] * random.uniform(0.96, 1.04), 1)
        # Alimento en GRAMOS: ingreso = consumo_g/ave × aves (con leve variación).
        # Saldo final = 0 → la parcela consume todo lo ingresado en la semana.
        ingreso = float(round(cons_gave_sem[n] * AVES_INI * random.uniform(0.97, 1.03)))
        saldo = 0.0
        por_sem[str(n)] = {
            "semanaNumero": n,
            "parcelaId": pid,
            "mort": mort,
            "peso": peso,
            "pesos": [peso],
            "consAjust": None,
            "refs": {
                "BR1": {"tipo": "BR1", "ingreso": ingreso, "saldoFin": saldo}
            },
        }
    datos[pid] = por_sem

dump = {
    "version": 1,
    "exportedAt": 1745000000000,
    "partida": partida,
    "semanas": semanas,
    "datosPorParcela": datos,
}

out = "Lote_Ejemplo_S6.davi"
with open(out, "w", encoding="utf-8") as f:
    json.dump(dump, f, ensure_ascii=False, indent=2)

print(f"OK -> {out}")
print(f"parcelas={len(todas_parcelas)} aves_total={len(todas_parcelas)*AVES_INI} semanas=6 tratamientos=4")
