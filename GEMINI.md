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
- **Date :** 28 Novembre 2026
- **État :**
  - Remotes : `yuzono/main` = `54556b1406` (**48 commits** depuis le 24/11), `cursed/main` = `728635a04a` (inchangé).
  - **Périmètre réel du fork :** `src/` ne contient que `fr` (40), `all` (23) et `en` (4) — les 45 modules touchés upstream comprennent donc beaucoup de sources absentes ici (`ar`, `es`, `id`, `ja`, `ko`, `pt`, `tr`, `vi`, `zh`). **Ne restaurer que les modules déjà suivis** : un `git restore` sur un chemin absent ressuscite une extension partielle (dossier sans `build.gradle.kts`) et casse `loadAllIndividualExtensions()`.
  - **`core`** : `TurnstileHelper.kt` (overlay de check superposé à l'écran, #21415).
  - **`lib-multisrc`** : `inkstory` (UA fixe retiré + Anubis, #21478), `libgroup` (parsing `site_ids` #21449, domaines `api.mangalib.me` / `api.cdnrulib.link` #21395), `madara` (`chapterAjaxPaginatedParam` ouvrable, #21413), `mangabox` (domaine `saiyanmanga.com`, #21453), `mmlook` (chapitres via le reader desktop, #21509), `mmrcms` (domaine, #21441).
  - **Extensions resynchronisées :** `src/all/mangafire` (`versionCode = 41`, titres sans liens : `links` devient `JsonElement`, #21439), `src/en/manganelo` (`Manganato` déplacé sur `saiyanmanga.com`, `natomanga.com` passé en legacy, #21453).
  - **Non importés (choix du fork) :** les 2 sources ajoutées upstream (`en/dorotoons`, `vi/hentaibox`) et les 7 retirées pour cause de site mort (`ar/orcamanga`, `en/scansgg`, `en/topmanhuanet`, `en/yaoihot`, `id/lianscans`, `id/manhwalistorg`, `id/tooncubus`) — aucune n'existait localement.
  - **`src/fr/japscan` : exclue du sync.** Elle est désormais **100 % indépendante de son équivalent upstream** (voir la section *Extensions Custom*) : 48 commits upstream n'ont donc donné **aucune** modification FR.
  - Validation : `./gradlew compileDebugKotlin` **BUILD SUCCESSFUL** + `assembleDebug` OK sur `mangafire` et `manganelo`.

#### Maintenance Précédente (24 Novembre 2026)
- **État :**
  - Remotes : `yuzono/main` = `dc4572c0fa` (**207 commits** depuis le 16/11), `cursed/main` = `728635a04a` (inchangé). `origin` en SSH → préfixer `PATH=/run/current-system/sw/bin:$PATH git ...`.
  - **Supprimées (sites morts, retirées upstream et vérifiées) :** `src/fr/bananascan` (`harmony-scan.fr`, DNS KO), `src/fr/histoiredhentai` (`hhentai.fr`, DNS KO), `src/fr/sushiscanfr` (`sushiscan.fr`, connexion refusée).
  - `core`, `lib`, `lib-multisrc`, `compiler` : resynchronisés depuis `yuzono/main`.
  - **Nouvelle API `keiyoushi.source.SMangaUpdate`** (callback `{ manga, chapters }`, core #21272) : 6 extensions adaptées — `hitomi` (v44), `ehentai` (v2), `nhentai` (v2), `pururin` (v3) — qui viennent de `cursed` et utilisent encore l'ancienne classe de `extensions-lib` — plus `japscan` (v75) et `hentaipaw` (v11).
  - `lib/webviewpatch` (nouvelle lib upstream) **retirée** : inutilisée ici et son DSL `keiyoushiRuntime` n'existe pas dans notre build-logic.
  - **33 extensions resynchronisées** : 23 FR (`animesama`, `manganova`, `scanr`, `scanmanga`, `sushiscan`, `aniverse`, `bdplus`, `poseidonscans`, `rimuscans`, `ortegascans`…), 3 EN (`mangago`, `mangakakalot`, `manganelo`), 7 ALL (`akuma`, `hentai3`, `mangafire`, `mangaplus`, `mangamillion`, `pandachaika`, `hennojin`).
  - **Customs mis à jour :**
    - `src/all/niadd` : import upstream #21381 (listing original works) + #21347 (chapitre via le reader du site) ; **réapplication** de `CHAPTER_NUMBER_REGEX`, sélecteurs de repli et tri descendant. `versionCode = 8`.
    - `src/all/mangadex` : blocs BLC `hasAvailableChapters` réappliqués (préférence + filtre). `versionCode = 6`.
    - `src/all/mangaball` : préférence BLC branchée sur `TranslatedLanguageFilter` upstream. `versionCode = 8`.
    - `src/fr/japscan` : `@Source` conservé, `SMangaUpdate` migré, fetch API inchangée. `versionCode = 75`.
    - `src/all/hentaipaw` : `SMangaUpdate` + `Fetch.XHR`. `versionCode = 11`.
  - Validation : `./gradlew compileDebugKotlin` **BUILD SUCCESSFUL** + `assembleDebug` OK sur les 5 customs.
  - **Attention :** après un `git rm` d'extension, supprimer aussi le dossier résiduel (`rm -rf src/<lang>/<ext>`) — sinon `loadAllIndividualExtensions()` le charge et KSP échoue.
  - **Reste à importer (25 nouvelles FR upstream)** : `banchanscan`, `blossomscans`, `chaostrad`, `comicstracker`, `crunchyscanunoriginal`, `dassouscan`, `epsilonscan`, `hanabook`, `hotmanga`, `japscanst`, `kanakonohast`, `komiko`, `mangadraftst`, `mangahubfr`, `mangaplexe`, `mangasoriginesfr`, `mangasoriginesst`, `onlyyaoiscantrad`, `onlyyuriscantrad`, `punkrecords`, `softepsilonscan`, `sushiscanst`, `twatt`, `universotakust`, `yureiscan`.

#### Historique (31 Octobre 2026)

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
- **`src/fr/japscan`** : **100 % indépendante de l'upstream — ne JAMAIS la resynchroniser.**
  - L'upstream (`yuzono/main`) contient encore un `src/fr/japscan`, mais notre version a divergé volontairement de ~1 600 lignes (3 fichiers locaux contre 4 upstream : `SearchResultDto.kt` supprimé, `ImageUtils.kt` ajouté).
  - `versionCode = 77` avec le commentaire `// upstream: none` (numérotation fork, découplée de l'upstream).
  - Capture des pages en **une passe via socket loopback** (`ServerSocket(0, 64, 127.0.0.1)`) plutôt que par le bridge JS — le bridge reste en repli.
  - `ImageUtils.kt` : résolution automatique du challenge d'images Japscan (`orderUuidsByImageVerticality`, `trySolveCustomChallenge`).
  - `ReaderScripts.kt` : scripts de lecture fork (déchiffrement, pilote WebView, timings) — 1 085 lignes contre 646 upstream ; `Japscan.kt` : 1 230 lignes contre 490 upstream.
  - `lib:twocaptcha` (fork-only) pour la résolution Cloudflare Turnstile via `createCloudflareInterceptor`.
  - Déduplication SHA-256 et cache local des pages.
  - **Aucun `git restore --source=yuzono/main -- src/fr/japscan` : toute évolution Japscan se fait à la main, côté fork.**
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
- Ne jamais écraser ou réinitialiser `src/all/niadd`, `src/all/mangadex`, `src/all/mangaball` ou `src/all/hentaipaw` avec l'upstream sans préserver leurs composants et correctifs custom (blocs `BLC`, tri/parsing Niadd, filtres HentaiPaw).
- **`src/fr/japscan` est 100 % fork : sortir du périmètre de toute synchro upstream.** Ne jamais lancer `git restore`/`git checkout yuzono/main` dessus, ne pas la compter comme « en retard », et ne pas la modifier pendant un sync — ses évolutions sont manuelles et propres au fork.
- `src/all/hentaipaw` est une extension 100 % fork : ne jamais la considérer comme « en retard » sur l'upstream ni la supprimer lors d'un nettoyage.
- Maintenir la compatibilité `repo.json` pour Komikku lors de chaque modification du workflow de build.
