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
- **Date :** 12 Septembre 2026
- **État :**
  - Remotes `yuzono` et `cursed` synchronisés via `git fetch --all`.
  - `cursed` (`master`) : Aucune mise à jour en attente pour nhentai, ehentai, hitomi, pururin (à jour).
  - `yuzono` (`main`) : Mises à jour upstream identifiées disponibles :
    - `src/fr/hentaiscantrad` : Changement d'URL vers `https://hentai-scantrad.org` (`versionCode = 2`).
    - `src/fr/sushiscan`, `kiwiyascans`, `lelmanga`, `sushiscanfr` : Migration du multisrc MangaThemesia vers `libVersion = 1.6`.
    - `src/all/mangadex` : Migration vers `libVersion = 1.6` (commit upstream `27de046353`).
    - `src/all/mangaball` : Refactoring et nettoyage code.
    - `src/fr/japscan` : Protégé et non écrasé (fork custom avec `lib:twocaptcha`, intercepteur Cloudflare et lecteur déchiffré).

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
- **`src/fr/japscan`** : Fortement personnalisée et divergente de l'upstream Keiyoushi :
  - Intègre la bibliothèque locale `lib:twocaptcha` (résolution automatique Cloudflare Turnstile).
  - Intercepteur OkHttp global avec résolution headless 2Captcha et rejeu automatique (`createCloudflareInterceptor`).
  - Déchiffrement et chargement des pages via WebView dédiée (mangas paginés et manhwas/webtoons), déduplication SHA-256 et cache local.
  - **Ne JAMAIS écraser ou synchroniser aveuglément Japscan depuis `upstream`** sans préserver explicitement ces modifications custom.
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
