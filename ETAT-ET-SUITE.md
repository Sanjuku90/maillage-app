# État du projet : Maillage 3.1 expérimental

## Quatrième lot : tests Noise et écritures Bluetooth

- Un test Kotlin reprend le vecteur public Noise-C `Noise_XX_25519_ChaChaPoly_SHA256` : trois messages de handshake et trois messages de transport. Le prologue et les clés éphémères fixes ne servent qu'au test ; la production conserve son prologue vide et génère une clé éphémère aléatoire. La partie transport de Maillage ajoute son propre compteur de 8 octets avant le texte chiffré : le test compare le texte chiffré sans ce préfixe.
- Le vecteur a été recoupé séparément avec une implémentation Python, mais **le code Kotlin n'a pas été exécuté ni compilé**. Un test qui passe ne prouvera pas à lui seul la sécurité ou la compatibilité avec Bitchat, qui a son propre protocole de messages.
- Les écritures Bluetooth côté client ont désormais une limite d'attente de 10 secondes. Une écriture non acceptée, trop grande, échouée ou sans réponse interrompt les envois à ce pair, vide sa file temporaire et demande la déconnexion ; les callbacks tardifs ne réactivent pas la connexion. Des tests de la politique d'attente ont été écrits, mais pas exécutés sur Android. Tester sur de vrais appareils les pertes, reconnexions, callbacks tardifs et files saturées.
- Source du vecteur : `https://docs.rs/crate/minip2p-noise/latest/source/tests/fixtures/noise-c-basic-xx.txt`, extrait de Noise-C à la révision indiquée dans le fichier. Aucune clé du vecteur ne doit servir en production.

## Troisième lot : vérification manuelle persistante des contacts

- Un appui long sur un contact affiche l'empreinte complète de ses clés publiques. Après comparaison de tous les caractères avec le contact en personne ou par un autre canal fiable, l'utilisateur peut choisir « J’ai comparé » pour enregistrer la vérification. Rien n'est marqué vérifié automatiquement. L'empreinte doit encore correspondre à celle affichée quand l'utilisateur confirme.
- Les empreintes vérifiées sont conservées chiffrées avec `Vault` et une clé Android Keystore, pour 100 contacts maximum. Lorsqu'une empreinte vérifiée change, l'application avertit et bloque les messages privés entrants et sortants vers ce contact. L'alerte reste enregistrée même si l'ancienne empreinte réapparaît, jusqu'à retrait explicite de la vérification. Un problème de lecture ou d'enregistrement des vérifications bloque les messages privés plutôt que de supposer une identité sûre.
- Le retrait d'une vérification exige une confirmation et autorise de nouveau les messages vers un contact non vérifié : il faut comparer à nouveau l'empreinte. Les contacts non vérifiés peuvent toujours échanger en privé, sans garantie contre une usurpation de la première rencontre. Les messages de groupe ne sont pas bloqués par ce mécanisme.
- Une nouvelle clé Noise annoncée ferme la session privée précédente et abandonne ses messages privés en attente. La réinitialisation locale supprime les vérifications avec les autres préférences et les clés.
- Des tests de politique ont été écrits, mais le code et les tests Android n'ont pas été compilés ou exécutés. La persistance chiffrée, les alertes et le blocage doivent être testés sur appareils ; le code n'a pas été audité. Aucun QR code ni compatibilité avec Bitchat n'a été ajouté.

## Deuxième lot : conservation locale des trames de groupe

- Le cache des trames de groupe, déjà chiffrées par le canal, est à présent conservé entre lancements dans les préférences privées, chiffré à nouveau par `Vault` et une clé Android Keystore. Il est borné à 60 trames et une heure, séparé du canal actif et relu pour les connexions Bluetooth clientes et les abonnés Bluetooth. L'ancienne conservation uniquement en RAM a été remplacée.
- Les trames expirées ou dont l'horodatage est dans le futur ne sont pas reprises. L'expiration est vérifiée au chargement, à la relecture et régulièrement lorsque le maillage est actif. Si l'application n'est pas ouverte, il n'y a pas de tâche garantie d'effacement à l'heure exacte, ni de garantie d'effacement physique du support. Une réinitialisation locale supprime les préférences et la clé associée.
- Le cache est une **retransmission au mieux**, pas une file d'attente avec accusés : les trames peuvent être perdues, répétées ou ne pas arriver. La reprise concerne les trames de groupe Bluetooth, pas une garantie de reprise Nostr ou des messages privés. Aucun état « reçu » n'est inventé.
- L'écriture du cache est synchrone et bornée ; mesurer sa latence et la consommation sur de vrais appareils. Tester la reprise après arrêt et redémarrage, la corruption ou perte de clé, les changements d'horloge et de canal, la saturation, la réinitialisation et les permissions. Des tests supplémentaires ont été écrits pour l'expiration, mais les tests Android et la compilation restent non exécutés.

### Vérification manuelle après un build réussi

1. Installer l'APK de test sur deux appareils Android et, sans saisir d'informations sensibles, rejoindre le même canal avec un mot de passe de test long. Désactiver Nostr sur les deux appareils pour isoler le Bluetooth.
2. Tester un message à proximité, déconnecter les appareils, envoyer un message de groupe, arrêter puis rouvrir l'application émettrice et rapprocher les appareils avant l'expiration. Observer l'éventuelle reprise et d'éventuels doublons : la livraison reste non confirmée.
3. Répéter avec une absence de plus d'une heure, un changement de canal, des permissions refusées, le Bluetooth désactivé et une réinitialisation locale. Vérifier qu'aucune trame de l'ancien canal n'apparaît dans le nouveau.
4. Comparer l'empreinte complète d'un contact en personne, puis mesurer la réactivité, le passage en arrière-plan et la batterie. Tester Tor/Nostr séparément, y compris l'absence de repli direct si Tor échoue.
5. Après avoir comparé les empreintes en personne, confirmer la vérification, relancer l'application et vérifier qu'elle reste enregistrée. Sur des données de test, changer l'identité du contact et vérifier le blocage des messages privés, la persistance de l'alerte après relance et la confirmation nécessaire pour retirer la vérification. Vérifier le comportement lorsque le stockage ou la clé de chiffrement est inaccessible.

## Premier lot : fiabilité et sécurité Android

- Build : le workflow Android évite désormais de demander l'ancien paquet SDK `tools`. Le correctif n'a pas été exécuté sur GitHub depuis cette modification.
- Bluetooth : un échec d'écriture ou une saturation de file affiche un avertissement ; la file du correspondant est vidée lors d'un échec d'écriture et la connexion est interrompue. Il n'y a pas encore de reprise garantie ni de reçu de livraison. Les messages locaux indiquent « livraison non confirmée ».
- Trames de groupe : le cache était initialement limité en mémoire à 60 trames et une heure ; le deuxième lot ci-dessus ajoute sa persistance chiffrée, sans en faire une file avec accusés.
- Identité : appuyer sur son identifiant affiche l'empreinte SHA-256 des clés publiques d'identité et Noise ; un appui long sur un contact affiche la sienne si elle est disponible. Le troisième lot ci-dessus ajoute la sauvegarde d'une vérification manuelle ; il n'y a pas de QR code.
- Tests unitaires ajoutés pour le cache, l'empreinte et la copie des clés d'identité. La valeur de référence SHA-256 a été calculée séparément, mais les tests Android n'ont pas été exécutés faute de Gradle et de SDK Android.

Il reste notamment à tester sur appareils, placer le moteur dans le service, concevoir une file chiffrée durable avec accusés authentifiés, exécuter les tests Noise ajoutés contre le vecteur public et faire réaliser un audit indépendant. Ne pas utiliser pour des urgences ou des informations critiques.

## Nouveau dans ce ZIP

- PC : client Bluetooth LE expérimental pour la connexion à un téléphone Maillage Android, avec fragmentation et réassemblage. Bluetooth seul ou Nostr peuvent être activés séparément. Le PC est un client BLE, pas un serveur BLE et pas un relais PC-vers-PC autonome.
- Android : notifications GATT pour les PC abonnés, file bornée et segmentation selon le MTU; effacement local avec confirmation pour les préférences, historique et clés Keystore. Cet effacement ne supprime pas les messages chez d’autres personnes ou sur les relais, et ne garantit pas l’effacement physique de la mémoire flash.
- PC : option Tor via un proxy local séparé et file Nostr temporaire. Android : garde-fou contre certaines collisions d’identités pendant la session et contre les compteurs Noise invalides.
- Tests locaux : 19 tests Python réussis, dont six sur le format et le transport Bluetooth simulé et deux sur les scripts de packaging; quatre tests ignorés à cause de dépendances/JDK manquants. Les tests Android ont été écrits mais pas exécutés. Une simulation n’est pas une validation radio.

## Toujours à faire

1. **Compilation et tests matériels** : compiler Android et PC, installer sur des appareils, vérifier permissions, découvertes, coupures, MTU, fragmentation, reconnexion et consommation. Aucune liaison réelle Android-PC n’a été testée ici.
2. **Sécurité** : audit indépendant; exécution des tests Noise avec vecteur public, tests matériels de la vérification persistante, durcissement du mot de passe de canal et effacement. L’identifiant court est seulement protégé contre certaines collisions déjà observées durant une session, pas contre une première rencontre usurpée. La vérification manuelle persistante n'est pas encore testée sur appareil. Le sel de canal est fixe.
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
