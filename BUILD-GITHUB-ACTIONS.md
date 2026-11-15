# Construire Maillage avec GitHub Actions

Ce dépôt prépare des builds expérimentaux Android, Windows et Linux. Les workflows n’ont pas encore été lancés ni validés sur GitHub. Ils ne publient rien automatiquement dans une Release, ne créent pas de dépôt et ne remplacent pas les tests sur appareils.

## 1. Mettre le projet sur GitHub

1. Créer son propre dépôt GitHub, de préférence privé pendant le développement. Ne pas utiliser le dépôt Bitchat comme destination.
2. Décompresser le ZIP et envoyer **tout le contenu du projet à la racine du dépôt**, y compris le dossier caché `.github/`, `app/`, `desktop/` et `scripts/`. Un ZIP téléversé tel quel ne sera pas extrait automatiquement par GitHub.
3. Vérifier que GitHub montre `.github/workflows/build.yml` et `.github/workflows/desktop.yml` dans le dépôt. S’ils manquent, aucun build ne démarrera.
4. Dans l’onglet **Actions**, autoriser les workflows si GitHub le demande. Un push lance normalement les deux workflows. Pour les lancer manuellement, choisir **Build Android** ou **Build PC**, puis **Run workflow**.
5. Ouvrir le run et attendre que les tâches soient terminées. Si une tâche est rouge, lire son journal : aucun APK ou programme opérationnel ne peut être garanti avant un run réussi.

## 2. Télécharger Android

Dans le run **Build Android**, l’artefact `Maillage-Android-...` contient :

- `Maillage-Android-test.apk` : APK installable pour tests, signé avec la clé de debug générée par le runner. **Il est débogable et n’est pas un produit de diffusion.**
- `Maillage-Android-release-non-signe.apk` : build release non signé, **non installable tel quel**. Il sert à vérifier la compilation et la réduction R8.
- `SHA256SUMS.txt` et `BUILD-INFO.txt` : empreintes, commit et numéro du run.

La clé de debug d’un runner n’est pas une identité de signature stable. Deux builds de test successifs peuvent refuser la mise à jour l’un de l’autre; désinstaller l’ancien APK supprime normalement ses données. Pour garder une identité et permettre les mises à jour, utiliser une clé release stable. Le rapport lint et les résultats de tests sont dans l’artefact `Maillage-Android-rapports-...`; le lint est informatif et ne bloque pas le build. Corriger les problèmes signalés avant diffusion.

## 3. Télécharger PC

Dans le run **Build PC**, télécharger l’artefact de sa plateforme :

- Windows 64 bits : `Maillage-PC-Windows.zip`. Décompresser toute l’archive, puis lancer `Maillage-PC/Maillage-PC.exe`. Garder tout le dossier ensemble.
- Linux x86-64 : `Maillage-PC-Linux.tar.gz`. Extraire avec `tar -xzf Maillage-PC-Linux.tar.gz`, puis lancer `./Maillage-PC/Maillage-PC`. L’archive tar conserve les permissions d’exécution.

Les fichiers sont accompagnés d’une empreinte SHA-256, des versions de dépendances effectivement installées et du commit. Ceux du workflow « Build PC » sont des programmes expérimentaux **non signés**, pas des installateurs. Un workflow séparé « Build Windows signé » est préparé si tu possèdes un certificat compatible : voir `SIGNATURE-WINDOWS.md`. Une signature ne garantit pas la disparition de SmartScreen. Windows peut afficher une alerte SmartScreen. Linux a besoin d’un environnement graphique et de BlueZ pour Bluetooth; un programme construit sur Ubuntu 24.04 peut ne pas fonctionner sur des distributions plus anciennes. Aucun build macOS n’est généré.

Les workflows exécutent les tests Python et un test intégré **hors ligne du programme empaqueté** (imports, dérivation de clé, chiffrement, signatures et fragments BLE). Ils ne prouvent pas que Bluetooth, Tor, les relais Nostr ou les messages Android-PC fonctionnent sur des appareils réels.

## 4. Option : APK release signé

À faire seulement lorsque tu veux une clé de signature stable. Ne jamais envoyer le keystore ou ses mots de passe dans le chat ni les ajouter au dépôt. Garde une sauvegarde sécurisée de la clé : la perdre peut empêcher les mises à jour des versions déjà installées. Ne génère pas une nouvelle clé à chaque build.

Sur un ordinateur avec un JDK, créer **une seule fois** la clé, en répondant aux questions dans le terminal :

```bash
keytool -genkeypair -v -keystore maillage-release.jks -alias maillage -keyalg RSA -keysize 3072 -validity 10000
```

Copier le contenu du fichier keystore encodé en base64 **sans retour à la ligne**. Par exemple, sous Linux :

```bash
base64 -w 0 maillage-release.jks
```

Ou dans PowerShell sous Windows, pour copier directement dans le presse-papiers :

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes((Resolve-Path "maillage-release.jks"))) | Set-Clipboard
```

Dans ton dépôt, ouvrir **Settings > Secrets and variables > Actions > New repository secret** et créer :

| Secret | Valeur |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Contenu base64 du fichier keystore |
| `ANDROID_STORE_PASSWORD` | Mot de passe du keystore |
| `ANDROID_KEY_ALIAS` | Alias de la clé, par exemple `maillage` |
| `ANDROID_KEY_PASSWORD` | Mot de passe de la clé |

Ensuite : **Actions > Build Android > Run workflow**, cocher `sign_release` et lancer le workflow depuis une branche de confiance. La tâche signée démarre seulement si le build Android initial réussit. Elle vérifie la présence des secrets, crée le keystore dans le dossier temporaire du runner, compile la release, contrôle la signature avec `apksigner`, puis supprime le fichier temporaire. L’APK signé et son SHA-256 apparaissent dans l’artefact `Maillage-Android-release-signe-...`.

Les secrets ne sont pas exportés dans les artefacts, mais un workflow ou un code de build modifié pourrait les exposer. Limiter les droits d’édition du dépôt et ne déclencher une signature que depuis du code revu. Les builds de pull request ne déclenchent pas la tâche de signature.

## Limites avant diffusion

Un build vert ne constitue ni un audit de sécurité ni une validation d’interopérabilité. Il reste à tester BLE, Wi-Fi Aware, Nostr et Tor sur plusieurs téléphones et PC, vérifier les clés et les migrations, examiner le rapport lint, faire auditer le chiffrement et signer séparément les programmes Windows/Linux si nécessaire. L’app n’est pas compatible Bitchat.
