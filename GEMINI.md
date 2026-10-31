# Guide de Gestion du Repo (Fork bluecxt)

Ce fichier définit les règles et la structure spécifique de ce dépôt pour aider Gemini à assister l'utilisateur efficacement.

## Structure du Dépôt

- **`src/fr/`** : Extensions uniquement en français (liste triée par l'utilisateur).
- **`src/en/`** : Sélection de 4 sites anglais (`mangago`, `mangakakalot`, `manganelo`, `readcomicsonline`). `mangapark` n'est pas présent dans l'upstream actuel.
- **`src/all/`** : Extensions multilingues sélectionnées (incluant MangaDex, Hitomi, nHentai, E-Hentai, etc.).
- **`exclude_build.json`** : Fichier JSON à la racine permettant d'exclure temporairement des extensions du build GitHub Actions.
- **`keystore/`** : (Ignoré par Git) Contient la clé de signature Android `signingkey.jks`.

## GitHub Actions & CI/CD

- **Workflow :** `.github/workflows/build_push.yml`.
- **Fonctionnement :**
  1. Compile les extensions en APK.
  2. Signe les APK avec les secrets `SIGNING_KEY`, `ALIAS`, `KEY_STORE_PASSWORD`, `KEY_PASSWORD`.
  3. Génère `repo.json` (indispensable pour Komikku) avec l'empreinte SHA256 de la clé et l'icône du dépôt.
  4. Déploie tout sur la branche **`repo`**.
- **Triggers :** Se lance à chaque push sur `main` qui modifie le code source ou les scripts dans `.github/`.

## Maintenance & Évolutions

### Ajouter une extension
1. Placer le dossier dans `src/<lang>/`.
2. S'assurer qu'elle n'est pas listée dans `exclude_build.json`.
3. Pousser sur `main` pour déclencher le build.

### Exclure une extension du build
Ajouter son identifiant au format `lang.nom` (ex: `fr.mangakawaii`) ou un pattern (ex: `en.*`) dans le fichier `exclude_build.json`.

### Gestion du Catalogue
L'URL du catalogue pour Mihon/Tachiyomi/Komikku est :
`https://raw.githubusercontent.com/bluecxt/manga-extensions-repo-french/repo/index.min.json`

### Synchronisation depuis les Remotes Upstream

Les dépôts parents configurés :
- **yuzono** : `https://github.com/yuzono/tachiyomi-extensions.git` (Branche par défaut : `main` ou `keiyoushi`)
- **cursed** : `https://github.com/yuzono/cursed-manga-extensions.git` (Branche : `main` — l'ancienne branche `master` a été supprimée upstream et fusionnée dans `main`)

#### Dernière Maintenance
- **Date :** 31 Octobre 2026
- **État :**
  - Remotes synchronisés via `git fetch --all --prune`. `yuzono/main` = `853268fc7d`, `cursed/main` = `5dad5cf65d`.
  - **Note environnement :** `origin` est en SSH (`git@cle-perso`). Dans un shell sans `ssh` au `PATH`, préfixer : `PATH=/run/current-system/sw/bin:$PATH git fetch origin`.
  - `core` : Synchronisé avec l'upstream — `Crypto.kt` étendu (+119 lignes, +46 dans `CryptoTest.kt`), `NextJs.kt` mis à jour (assemblage des chunks Next.js flight, + `NextJsTest.kt`), suppression de `reactFlight/ReactFlightDate.kt`.
  - `lib/` : Bibliothèques partagées déjà à jour (aucun diff). Bibliothèques fork-only conservées : `twocaptcha` (Japscan), `cryptoaes`, `seedrandom`, `zipinterceptor`, `cookieinterceptor`.
  - `lib-multisrc/` : Synchronisé intégralement avec l'upstream. Themes mis à jour : `hiper`, `iken`, `mangahub`, `moonlighttl`, `natsuid`, `vinetheme`, `aurora`, `comiciviewer`, `eromuse`, `initmanga`, `madara`, `mangabox`, `mangak`, `pam`. Ajout du thème `imperiomanhwa` (renommage upstream de `madaralegacy`). Thèmes orphelins supprimés : `clipstudioreader` (déplacé dans `lib/` upstream, inutilisé ici), `madaralegacy`, `gigaviewer/GigaViewerDto.kt`.
  - **Extensions resynchronisées depuis `yuzono/main` (surgical restore) :**
    - `src/fr/` : `astralmanga` (`versionCode = 1`), `fmteam` (ouverture des séries sous `/manga/` en WebView), `ortegascans` (`versionCode = 4`), `scanhentaimenu` (refonte X-Manga : `Dto.kt` + `Filters.kt` ajoutés), `toonfr` (parsing des dates de chapitres).
    - `src/en/` : `mangakakalot` (domaine `www.kakamanga.com`, `versionCode = 10`).
    - `src/all/` : `hentai3` (retrait des sources `fi`/`mo`, `versionCode = 6`), `mangafire` (filtrage par langue de la source), `pandachaika` (fix crash uploader null, retrait des langues vides).
  - **Extensions FR ajoutées depuis `yuzono/main` :** `scanreader` (thème `scanreader`, `https://scanreader.net`), `hentaiscanreader` (même thème, NSFW, `https://hentai.scanreader.net`), `aniverse` (`https://aniverse.fr`), `bdplus` (`https://bdplus.cc`), `mabrute` (thème `madara`, NSFW, `https://mabrute.com`), `scanmanga` (`https://m.scan-manga.com`, retirée de `exclude_build.json`). Validation : `compileDebugKotlin` OK sur les 6 modules.
  - **Nettoyage :**
    - `src/all/manga18me` : suppression du `Filters.kt` orphelin (l'extension est passée au thème `yiimadara`, qui fournit ses propres filtres) — aligne l'arbre sur l'upstream.
    - `src/fr/scansfr` et `src/fr/kiwiyascans` : **supprimées** (déjà retirées des upstreams, sites morts — `scansfr.com` refondu en Next.js sans l'API `api.scansfr.com/api/v1`, `kiwiyascans.com` en erreur Cloudflare 526). Ne pas les restaurer.
  - **Outillage de build :** Gradle wrapper `9.7.1 → 9.8.2`, AGP `9.3.2 → 9.4.1` (`gradle/libs.versions.toml`). `gradle-daemon-jvm.properties` (toolchain 21) et le build-logic Kotlin DSL locaux conservés.
  - **Extensions custom préservées (non écrasées) :** `src/fr/japscan`, `src/all/niadd`, `src/all/mangadex` (BLC), `src/all/mangaball` (BLC), `src/all/hentaipaw`.
  - `cursed` (`main`) : `src/all/nhentai`, `ehentai`, `hitomi`, `pururin` déjà conformes.
  - Validation du build : `PATH=/run/current-system/sw/bin:$PATH ./gradlew compileDebugKotlin` → `BUILD SUCCESSFUL` (exit 0, 2137 tâches, ~3 min) avec Gradle 9.8.2 + AGP 9.4.1. Seul avertissement : `SimpleDateFormat.tryParse` déprécié dans `scansfr` (préexistant).

#### Commandes Utiles
```bash
# 1. Mettre à jour les informations des dépôts distants
git fetch --all

# 2. Comparer les changements avant import
git diff main yuzono/main -- src/all/mangadex
git diff main cursed/main -- src/all/nhentai

# 3. Mettre à jour une extension standard (Surgical Update)
git restore --source=yuzono/main -- src/fr/mangakawaii
git restore --source=cursed/main -- src/all/nhentai

# 4. Ajouter une nouvelle extension depuis un parent
git restore --source=yuzono/main -- src/fr/nouvelle_extension
```

#### Aide-mémoire des Sources par Extension
| Extension | Source Remote | Chemin Source |
| :--- | :--- | :--- |
| **La majorité (FR/EN/ALL)** | `yuzono` | `src/...` |
| **nhentai** | `cursed` | `src/all/nhentai` |
| **ehentai** | `cursed` | `src/all/ehentai` |
| **hitomi** | `cursed` | `src/all/hitomi` |
| **pururin** | `cursed` | `src/all/pururin` |

### Extensions Custom / Modifiées (Ne pas écraser depuis l'upstream)
- **`src/fr/japscan`** : Personnalisée avec intégration hybride :
  - Migrée vers `libVersion = 1.6` (`KeiSource`, `ReaderScripts.kt`, `SearchResultDto.kt`, `warmupWebViewSession`).
  - Conserve l'algorithme custom de résolution automatique de challenge d'images Japscan (`ImageUtils.kt` avec `orderUuidsByImageVerticality` et `trySolveCustomChallenge`).
  - Intègre la bibliothèque locale `lib:twocaptcha` (résolution automatique Cloudflare Turnstile via `createCloudflareInterceptor`).
  - Déchiffrement et chargement des pages via WebView dédiée (mangas paginés et manhwas/webtoons), déduplication SHA-256 et cache local.
  - **Ne JAMAIS écraser Japscan depuis l'upstream sans préserver ces composants custom (`ImageUtils.kt`, challenge solver, `lib:twocaptcha`, intercepteur et préférences).**
- **`src/all/niadd`** : Modifiée pour corriger le formatage et l'ordre des chapitres :
  - Parsing corrigé du nom de chapitre ciblant `span.chp-title` (évite de concaténer vues et date dans le titre).
  - Parsing étendu du numéro de chapitre (`CHAPTER_NUMBER_REGEX` gérant Vol/Volume, Ch/Chapitre, Capitulo).
  - Tri automatique descendant par `chapter_number` dans `chapterListParse` pour corriger les chapitres mal ordonnés sur le site.
  - **Ne pas écraser aveuglément depuis `upstream`** sans préserver ces correctifs de tri et parsing.
- **`src/all/hentaipaw`** : Extension custom du fork, **absente de l'upstream** (`yuzono`/`cursed`) — elle n'existe qu'ici, ne jamais la supprimer lors d'une synchro
- **`src/all/mangadex`** : Correctifs fork marqués `// BLC -->` / `// BLC <--` :
  - Ajout d'une préférence `hasAvailableChapters` par langue (`MDConstants.getHasAvailableChaptersPrefKey`) et du `HasAvailableChaptersFilter` correspondant (état initial repris des préférences).
  - `versionCode = 1` (`// upstream: 0`).
  - **Conserver les blocs BLC lors de toute resynchro upstream.**
- **`src/all/mangaball`** : Correctifs fork marqués `// BLC -->` / `// BLC <--` :
  - Filtre « has available chapters » (`Filters.kt`) et logique associée dans `MangaBall.kt`.
  - **Conserver les blocs BLC lors de toute resynchro upstream.**

## Mandats Spécifiques pour Gemini
- Toujours vérifier `exclude_build.json` avant de se plaindre d'un build manquant.
- Ne jamais restaurer les extensions supprimées (Dynasty, etc.) sans confirmation explicite.
- Ne jamais écraser ou réinitialiser `src/fr/japscan`, `src/all/niadd`, `src/all/mangadex`, `src/all/mangaball` ou `src/all/hentaipaw` avec l'upstream sans préserver leurs composants et correctifs custom (blocs `BLC`, solveur Japscan, tri/parsing Niadd, filtres HentaiPaw).
- `src/all/hentaipaw` est une extension 100 % fork : ne jamais la considérer comme « en retard » sur l'upstream ni la supprimer lors d'un nettoyage.
- Maintenir la compatibilité `repo.json` pour Komikku lors de chaque modification du workflow de build.
