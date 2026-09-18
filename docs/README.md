# Documentación — DigitadorAvicola / Flock Tracker

Documentación detallada del proyecto, organizada por tema. Los diagramas están en
**Mermaid** y se renderizan directo en GitHub.

> Para una visión rápida (resumen ejecutivo + traspaso), ver
> [`../DOCUMENTACION.md`](../DOCUMENTACION.md). Esta carpeta es el **detalle**.

## Técnica (para quien mantiene/programa la app)

| Documento | Contenido |
|---|---|
| [arquitectura.md](arquitectura.md) | Visión general, stack y versiones, patrón MVVM + StateFlow, estructura de paquetes, inyección de dependencias (Hilt), arranque y recepción de `.davi`. Diagramas de capas y de flujo de datos. |
| [modelo-datos.md](modelo-datos.md) | Modelo Room: cada entidad y campo, DAOs, repositorios, migraciones (v7→v11), `ConfigRepository` (PIN, prefs). Diagrama entidad-relación. Unidades kg/g. |
| [calculos.md](calculos.md) | La `Calculadora` indicador por indicador (peso, consumo, FCR, GDP, CV, FEP, ratio…), con fórmulas, código, ejemplos numéricos y casos borde. Validación de "Terminar semana". |
| [pantallas.md](pantallas.md) | Cada pantalla (estado, ViewModel, flujos) y la navegación type-safe. Diagrama de navegación. |
| [davi-y-exportacion.md](davi-y-exportacion.md) | Formato `.davi` (3 variantes con ejemplos JSON), importación/duplicados, exportación a Excel (POI) y PDF (3 hojas). Diagrama del flujo de importación. |

## Usuario (para quien opera la app en campo)

| Documento | Contenido |
|---|---|
| [manual-usuario.md](manual-usuario.md) | Manual paso a paso, sin tecnicismos: cargar un lote, digitar la semana, terminar/cerrar semana, ver resultados, exportar/compartir, respaldar, PIN y papelera, y preguntas frecuentes. |

---

> ⚠️ **Unidades (recordatorio transversal):** el **alimento** se maneja en **kilogramos**
> y los **pesos de las aves** en **gramos**. Ver [modelo-datos.md §8](modelo-datos.md#8-nota-de-unidades).
