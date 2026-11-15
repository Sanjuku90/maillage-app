# Préparer un programme Windows signé

Le workflow **Build Windows signé** construit `Maillage-PC.exe`, demande une signature Authenticode SHA-256 avec horodatage, vérifie la signature, lance le test intégré hors ligne puis crée l’archive. **Ce ZIP source ne contient pas de programme signé ni de certificat. Le workflow n’a pas été exécuté ici.**

## Important : ce que la signature change

Une signature issue d’un certificat de signature de code reconnu permet à Windows d’identifier l’éditeur et de vérifier l’intégrité du fichier. Elle peut réduire les avertissements, mais **elle ne garantit pas leur disparition** : SmartScreen tient aussi compte de la réputation de l’éditeur et du fichier. Un certificat autosigné ne donne pas la même confiance publique. Voir la documentation Microsoft : https://learn.microsoft.com/en-us/windows/apps/package-and-deploy/smartscreen-reputation

Ce workflow signe uniquement `Maillage-PC.exe`. Il ne signe pas automatiquement tous les composants tiers livrés à côté, ni l’archive ZIP. L’app reste expérimentale; signer n’est pas un audit de sécurité ni une preuve que les fonctionnalités fonctionnent.

## Ce qu’il faut avant de commencer

1. Ton propre dépôt GitHub contenant tout ce projet, notamment `.github/workflows/windows-signed.yml`, `scripts/sign-windows.ps1`, `desktop/` et `scripts/`. Ne pas utiliser le dépôt Bitchat comme destination.
2. Un certificat valide de **signature de code Windows Authenticode**, émis par une autorité reconnue et associé à ton identité d’éditeur. L’obtention peut coûter de l’argent et demander une vérification d’identité. Ne pas partager le certificat, sa clé privée ou son mot de passe dans la conversation.
3. Cette version du workflow prend en charge **un fichier PFX contenant la clé privée, uniquement si ton fournisseur autorise son utilisation et son export**. Beaucoup de certificats modernes gardent la clé dans un matériel ou service cloud non exportable : ce workflow PFX ne les gère pas. Dans ce cas, il faudra adapter la signature à ton fournisseur ou à un service de signature géré, sans essayer de contourner sa protection.

## Configurer les secrets GitHub

Sur un ordinateur de confiance et seulement si l’utilisation d’un fichier PFX est autorisée, encoder le fichier dans le presse-papiers avec PowerShell :

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes((Resolve-Path "C:\chemin\vers\certificat.pfx"))) | Set-Clipboard
```

Dans ton dépôt : **Settings > Secrets and variables > Actions > New repository secret**. Créer :

| Secret | Valeur |
| --- | --- |
| `WINDOWS_CODE_SIGNING_PFX_BASE64` | Contenu base64 du fichier PFX |
| `WINDOWS_CODE_SIGNING_PASSWORD` | Mot de passe de ce fichier PFX |

Un secret GitHub est limité à 48 Ko. Ne mets jamais le PFX, sa version base64 ni son mot de passe dans le dépôt ou les journaux. Les utilisateurs ayant des droits d’écriture sur le dépôt peuvent modifier un workflow pour exposer ces secrets : limite strictement ces droits et lance la signature seulement depuis une branche revue et de confiance. Les secrets ne sont fournis qu’à l’étape de signature du workflow. Le fichier PFX temporaire est supprimé à la fin de cette étape, sans garantie d’effacement physique. SignTool reçoit le mot de passe en argument de processus : utilise un runner GitHub hébergé et éphémère, pas une machine partagée ou un runner auto-hébergé non fiable.

## Lancer et récupérer le build

1. Ouvre **Actions > Build Windows signé > Run workflow**, sélectionne ta branche de confiance, puis lance le workflow.
2. Le workflow vérifie les tests, construit le programme, signe et vérifie l’exécutable avec SignTool, exécute son test hors ligne puis prépare le ZIP. **Si les secrets, la signature, l’horodatage ou la vérification manquent ou échouent, il échoue au lieu de publier un fichier non signé sous le nom signé.**
3. Une fois le job vert, télécharge l’artefact `Maillage-PC-Windows-signe-...`. Décompresse le ZIP téléchargé puis `Maillage-PC-Windows.zip`; conserve tout le dossier ensemble et ouvre `Maillage-PC/Maillage-PC.exe`.
4. Sur Windows, pour contrôler l’éditeur : clic droit sur l’exécutable > **Propriétés > Signatures numériques**. L’artefact inclut aussi `SHA256SUMS.txt`, `BUILD-INFO.txt` et la liste des dépendances.

L’étape d’horodatage contacte le service RFC 3161 de DigiCert à `http://timestamp.digicert.com`; une indisponibilité réseau fait échouer la signature. Le workflow ne crée pas de GitHub Release et ne téléverse pas le certificat dans l’artefact. `Build PC` reste distinct et produit des builds non signés.

## Toujours à vérifier

Le workflow n’a pas encore tourné sur GitHub. Son premier résultat doit être contrôlé avec ton certificat et sur un vrai PC, puis la connexion Android-PC doit être testée. Ni une signature ni un résultat CI vert ne valident la confidentialité ou la sécurité du programme.
