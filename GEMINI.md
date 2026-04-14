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

## Mandats Spécifiques pour Gemini
- Toujours vérifier `exclude_build.json` avant de se plaindre d'un build manquant.
- Ne jamais restaurer les extensions supprimées (Dynasty, etc.) sans confirmation explicite.
- Maintenir la compatibilité `repo.json` pour Komikku lors de chaque modification du workflow de build.
