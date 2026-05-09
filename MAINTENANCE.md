# Guide de Synchronisation des Extensions

Ce document explique comment maintenir les extensions à jour en utilisant les dépôts "parents" comme sources distantes.

## Configuration des Remotes

Les dépôts suivants sont configurés comme sources distantes (remotes) :
- **yuzono** : `https://github.com/yuzono/tachiyomi-extensions.git` (Branche : `master`)
- **cursed** : `https://github.com/yuzono/cursed-manga-extensions.git` (Branche : `master`)

---

## Commandes Utiles

### 1. Récupérer les dernières nouveautés
Avant toute chose, mettez à jour les informations des dépôts distants :
```bash
git fetch --all
```

### 2. Comparer les changements
Pour voir ce qui a été modifié sur une extension précise avant de l'importer :
```bash
# Exemple pour Mangadex (sur yuzono)
git diff main yuzono/master -- src/all/mangadex

# Exemple pour nhentai (sur cursed)
git diff main cursed/master -- src/all/nhentai
```

### 3. Mettre à jour une extension (Surgical Update)
Pour écraser votre version locale par la version la plus récente du parent :
```bash
# Depuis yuzono
git checkout yuzono/master -- src/fr/mangakawaii

# Depuis cursed
git checkout cursed/master -- src/all/nhentai
```

### 4. Ajouter une nouvelle extension depuis un parent
```bash
# Exemple pour ajouter une extension qui n'est pas encore chez vous
git checkout yuzono/master -- src/fr/nouvelle_extension
```

---

## Liste des Sources par Extension (Aide-mémoire)

| Extension | Source Remote | Chemin Source |
| :--- | :--- | :--- |
| **La majorité (FR/EN/ALL)** | `yuzono` | `src/...` |
| **nhentai** | `cursed` | `src/all/nhentai` |
| **ehentai** | `cursed` | `src/all/ehentai` |
| **hitomi** | `cursed` | `src/all/hitomi` |
| **pururin** | `cursed` | `src/all/pururin` |
|**crunchyscan**|`standalone`|`src/all/crunchyscan`|

---

## Pourquoi utiliser cette méthode ?
- **Pas de conflits d'historique** : Git traite cela comme une simple copie de fichiers.
- **Visibilité** : Vous voyez exactement le code qui change via `git diff`.
- **Légèreté** : Plus besoin de cloner manuellement les dépôts dans des dossiers temporaires.
