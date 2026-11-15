# État du projet : Maillage 3.1 expérimental

## Nouveau dans ce ZIP

- PC : client Bluetooth LE expérimental pour la connexion à un téléphone Maillage Android, avec fragmentation et réassemblage. Bluetooth seul ou Nostr peuvent être activés séparément. Le PC est un client BLE, pas un serveur BLE et pas un relais PC-vers-PC autonome.
- Android : notifications GATT pour les PC abonnés, file bornée et segmentation selon le MTU; effacement local avec confirmation pour les préférences, historique et clés Keystore. Cet effacement ne supprime pas les messages chez d’autres personnes ou sur les relais, et ne garantit pas l’effacement physique de la mémoire flash.
- PC : option Tor via un proxy local séparé et file Nostr temporaire. Android : garde-fou contre certaines collisions d’identités pendant la session et contre les compteurs Noise invalides.
- Tests locaux : 19 tests Python réussis, dont six sur le format et le transport Bluetooth simulé et deux sur les scripts de packaging; quatre tests ignorés à cause de dépendances/JDK manquants. Les tests Android ont été écrits mais pas exécutés. Une simulation n’est pas une validation radio.

## Toujours à faire

1. **Compilation et tests matériels** : compiler Android et PC, installer sur des appareils, vérifier permissions, découvertes, coupures, MTU, fragmentation, reconnexion et consommation. Aucune liaison réelle Android-PC n’a été testée ici.
2. **Sécurité** : audit indépendant; test Noise contre des vecteurs publics, vérification des clés avec l’utilisateur, durcissement du mot de passe de canal et effacement. L’identifiant court est seulement protégé contre certaines collisions déjà observées durant une session, pas contre une première rencontre usurpée. Le sel de canal est fixe.
3. **Vie du mesh Android** : le service foreground actuel n’est qu’un keepalive; le moteur est encore lié à l’Activity. Déplacer réellement sa propriété et ses événements vers le service, et gérer la mort/redémarrage du processus.
4. **Routage fiable** : file durable chiffrée, reçus, quotas et adaptation de la charge pour les grands réseaux; pas de garantie que 16 sauts atteignent 16 téléphones.
5. **Autres fonctionnalités** : messages privés sur PC, médias, notes vocales, canaux géographiques, favoris/vérification, configuration des relais, Wi-Fi Aware haut débit et serveur/relai BLE sur PC.
6. **Interopérabilité Bitchat/iPhone** : Maillage utilise son propre protocole et n’est pas compatible avec Bitchat. Un portage du format et des sessions ainsi que des tests croisés restent nécessaires.
7. **Diffusion** : APK et exécutables signés, tests de charge, audit et validation multi-fabricants avant toute version publique.

Aucun APK ou exécutable n’a été construit dans cet environnement : les dépendances et outils ne sont pas disponibles, et la sandbox n’a pas d’accès Internet. Les workflows sont fournis pour tenter les builds sur GitHub Actions, dont le résultat reste à vérifier.


## Préparation GitHub Actions

Les workflows `Build Android` et `Build PC` sont préparés. Ils n’ont pas été lancés dans un dépôt GitHub ici; leurs résultats et les exécutables ne sont donc pas encore vérifiés. Le guide `BUILD-GITHUB-ACTIONS.md` explique le dépôt, les artefacts et la signature release Android facultative.


## Signature Windows

Le workflow manuel `Build Windows signé` est préparé pour un certificat Authenticode PFX compatible et des secrets GitHub définis par le propriétaire. Il n’a pas été exécuté : aucun certificat ni exécutable signé n’est fourni. La signature et la réputation SmartScreen doivent être vérifiées séparément. Voir `SIGNATURE-WINDOWS.md`.


## Option gratuite Microsoft Store

Le workflow manuel `Préparer MSIX Microsoft Store` accepte les identités attribuées par Partner Center et tente de construire un MSIX non signé. Il n’a pas été exécuté ici; ce n’est ni une signature ni une publication. Microsoft peut signer après soumission et certification. Les limites, les tests et la procédure sont dans `SIGNATURE-GRATUITE-STORE.md`.
