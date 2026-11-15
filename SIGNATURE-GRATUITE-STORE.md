# Faire signer Maillage gratuitement par Microsoft

## Le principe

Le Microsoft Store peut signer gratuitement une application **MSIX** après sa soumission et sa certification. Pour un téléchargement et une installation **depuis le Store**, cela évite l’avertissement SmartScreen lié à un programme inconnu. Ce n’est pas une signature gratuite de l’EXE téléchargé directement depuis GitHub : le MSIX produit par ce projet reste non signé avant traitement par Microsoft.

L’inscription d’un développeur individuel est gratuite dans les marchés pris en charge, mais nécessite un compte Microsoft et une vérification d’identité. Source officielle : https://learn.microsoft.com/en-us/windows/apps/publish/whats-new-individual-developer

Le Store doit accepter la soumission. Aucune approbation n’est garantie, et Maillage est encore expérimental : vérifie le fonctionnement, la sécurité, les autorisations et les règles du Store avant toute diffusion. Aucune soumission n’est faite automatiquement par le workflow.

## Préparation du compte

1. Va sur https://storedeveloper.microsoft.com et crée un compte développeur individuel. Effectue la vérification d’identité directement auprès de Microsoft, jamais dans le chat.
2. Dans Partner Center, réserve le nom de l’application.
3. Ouvre les **détails d’identité de l’application** et copie les valeurs exactes fournies par Microsoft :
   - `Package/Identity/Name` ;
   - `Package/Identity/Publisher` (commence par `CN=`) ;
   - `Package/Properties/PublisherDisplayName`.

Documentation Microsoft : https://learn.microsoft.com/en-us/windows/apps/publish/view-app-identity-details

## Construire le paquet

1. Dans ton propre dépôt GitHub contenant ce projet, ouvre **Actions > Préparer MSIX Microsoft Store > Run workflow**.
2. Renseigne les trois valeurs ci-dessus, le nom de l’app réservé dans Partner Center et une version de quatre nombres finissant par zéro, par exemple `1.0.0.0`. Ne fabrique pas les valeurs d’identité : elles doivent correspondre au compte et à l’application réservée.
3. Le workflow exécute les tests, construit l’application PC, vérifie son test intégré hors ligne, puis tente de créer et d’ouvrir un MSIX. S’il échoue, vérifie le journal; rien n’est soumis au Store.
4. Si le run est vert, télécharge l’artefact `Maillage-MSIX-Store-non-signe-...`, puis extrais `Maillage-Store-non-signe.msix`. Ce fichier est destiné à préparer la soumission : **il n’est pas signé ni prêt à être installé directement**.
5. Dans Partner Center, crée une soumission MSIX, ajoute ce paquet et complète tous les éléments demandés : description, captures, classement par âge, informations de confidentialité et déclarations d’autorisations. Microsoft examine l’application. Après certification et publication, le Store fournit une version signée par Microsoft.

Le paquet déclare Internet, Bluetooth et la capacité restreinte `runFullTrust` pour l’application de bureau. Ces autorisations et le fonctionnement en environnement MSIX doivent être examinés et testés; Microsoft peut demander des justifications ou refuser la soumission. Ce projet n’a pas encore été construit, testé en MSIX ni certifié sur GitHub ou par Microsoft.

## Autre option gratuite pour un projet open source

La [SignPath Foundation](https://signpath.org/) propose un service de signature gratuit pour des projets open source **éligibles et approuvés**. Les conditions incluent une licence open source admissible, un projet maintenu et déjà publié, entre autres. L’éligibilité de Maillage n’est pas établie. L’acceptation n’est pas automatique et une signature reconnue n’efface pas nécessairement l’avertissement SmartScreen immédiatement. Conditions : https://signpath.org/terms.html
