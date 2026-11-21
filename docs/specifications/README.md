# CataNav — dossier de spécifications

| N° | Document | Contenu |
|---|---|---|
| 00 | [Architecture générale](00_architecture_generale.md) | Objet, découpage en modules, repères et conventions, modèle d'exécution, exigences |
| 01 | [IMU](01_IMU.md) | Acquisition accéléromètre / gyromètre, synchronisation, repère FRD |
| 02 | [MSU](02_MSU.md) | Magnétomètre, fer dur / fer doux, ajustement d'ellipsoïde, cap, perturbations |
| 03 | [GPS](03_GPS.md) | Validité des positions GNSS, origine, plan tangent local |
| 04 | [Aides piéton](04_aides_pieton.md) | Détection de pas (Weinberg), PDR, ZUPT, ZARU |
| 05 | [KAL](05_KAL.md) | Mécanisation, filtre de Kalman à erreur d'état 15 états, mesures, χ² |
| 06 | [Carte](06_Carte.md) | Extraction du réseau, tuiles du plan, appariement, recalage 1D |
| 07 | [LOC](07_LOC.md) | Publication de la position (Play Services, fournisseurs de test) |
| 08 | [IHM](08_IHM.md) | Écrans, représentations, enveloppes ±3σ, boussole et verticale |
| 09 | [Moteur et service](09_Moteur_et_service.md) | Orchestration, thread, cadences, service au premier plan |

Chaque document suit le même plan : rôle, description détaillée, interfaces, schéma architectural, schéma de flux, équations, justifications, limites, vérification.

**Lecture** : les schémas sont en [Mermaid](https://mermaid.js.org/) et les équations en LaTeX. Ils s'affichent directement sur GitHub / GitLab, dans VS Code (aperçu Markdown + extension Mermaid) et dans Android Studio (extension Mermaid). Pour un PDF : `pandoc --filter mermaid-filter -o specs.pdf *.md` ou l'export PDF de VS Code (extension *Markdown PDF*).

**Valeurs numériques** : celles des documents correspondent à `app/src/main/java/fr/inertialnav/iau/config/Params.kt` à la date de rédaction (2026-11-21). En cas d'écart, ce fichier fait foi.
