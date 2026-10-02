# Guide de Gestion du Repo (Fork bluecxt)

Ce fichier définit les règles et la structure spécifique de ce dépôt pour aider Gemini à assister l'utilisateur efficacement.

## Structure du Dépôt

- **`src/fr/`** : Extensions uniquement en français (liste triée par l'utilisateur).
- **`src/en/`** : Sélection des 5 sites anglais les plus réputés (`mangasee`, `mangakakalot`, `manganelo`, `mangapark`, `readcomicsonline`).
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
- **cursed** : `https://github.com/yuzono/cursed-manga-extensions.git` (Branche : `master`)

#### Dernière Maintenance
- **Date :** 2 Octobre 2026
- **État :**
  - Remotes `yuzono` et `cursed` synchronisés via `git fetch --all`.
  - `core` : Synchronisé avec l'upstream (`keiyoushi.zip.coroutines`, `ProtobufDecoder/Encoder`).
  - `lib/` : Bibliothèques `publus`, `speedbinb`, `e4p` mises à jour avec les optimisations upstream.
  - `lib-multisrc/` : Thèmes `mangathemesia`, `fuzzydoodle`, `mmrcms`, `galleryadults`, `madara` migrés vers `libVersion = 1.6`.
  - **Extensions mises à jour (libVersion = 1.6) :**
    - `src/fr/` : `animesama`, `aralosbd`, `bigsolo`, `furyosquad`, `hentaiscantrad` (nouvelle URL `https://hentai-scantrad.org`), `kiwiyascans`, `lanortrad` (nouvelle structure), `lelmanga`, `lelscanvf`, `lesporoiniens`, `mangakawaii` (V5), `mangamoins`, `scanr`, `scantradunion`, `scanvf`, `sushiscan`, `sushiscanfr`.
    - `src/en/` : `readcomicsonline` (MMRCMS 1.6 + recherche étendue).
    - `src/all/` : `akuma`, `hennojin`, `hentaienvy`, `hentaizap`, `mangaball`, `mangadex`, `mangamillion` (réécriture protobuf et 100+ langues), `mangaplus`, `pandachaika`.
  - **Extensions spécifiques :**
    - `src/all/niadd` : Migrée vers `libVersion = 1.6` tout en préservant les correctifs custom (tri descendant des chapitres, `CHAPTER_NUMBER_REGEX` multi-langues, parsing `span.chp-title`), incrémentée à `versionCode = 4`.
    - `src/fr/perfscan` : Supprimée et consignée dans `exclude_build.json` (site mort / nom de domaine inexistant upstream #19555).
    - `src/fr/japscan` : Migrée vers `libVersion = 1.6` (`KeiSource`, coroutines, `ReaderScripts.kt`, `SearchResultDto.kt`, `warmupWebViewSession`) tout en conservant le solveur custom de challenge d'images (`ImageUtils.kt` / `orderUuidsByImageVerticality`), le solveur Cloudflare Turnstile `lib:twocaptcha`, et incrémentée à `versionCode = 72`.
    - `cursed` (`master`) : `src/all/nhentai`, `ehentai`, `hitomi`, `pururin` synchronisés et conformes à `cursed/master`.
  - Validation du build : `compileDebugKotlin` validé avec succès (exit code 0 sur tous les modules).

#### Commandes Utiles
```bash
# 1. Mettre à jour les informations des dépôts distants
git fetch --all

# 2. Comparer les changements avant import
git diff main yuzono/main -- src/all/mangadex
git diff main cursed/master -- src/all/nhentai

# 3. Mettre à jour une extension standard (Surgical Update)
git restore --source=yuzono/main -- src/fr/mangakawaii
git restore --source=cursed/master -- src/all/nhentai

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

## Mandats Spécifiques pour Gemini
- Toujours vérifier `exclude_build.json` avant de se plaindre d'un build manquant.
- Ne jamais restaurer les extensions supprimées (Dynasty, etc.) sans confirmation explicite.
- Ne jamais écraser ou réinitialiser `src/fr/japscan` ou `src/all/niadd` avec l'upstream sans préserver leurs composants et correctifs custom.
- Maintenir la compatibilité `repo.json` pour Komikku lors de chaque modification du workflow de build.
