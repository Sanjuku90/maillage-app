# Maillage 3.1 expérimental: état et suite

## Maintenant dans le code

- **Routeur commun**: un paquet chiffré peut circuler par BLE, Wi-Fi Aware ou Nostr; la déduplication et le TTL évitent les boucles.
- **BLE**: fragmentation selon le MTU, assemblage avec taille et durée bornées, TTL plafonné à 16.
- **Wi-Fi Aware**: découverte bilatérale via publier/s’abonner, messages ≤ 255 octets découpés et réassemblés, tentatives bornées. Fonctionne seulement sur les appareils compatibles et autorisés.
- **Nostr**: NIP-01, événements kind 1 chiffrés à l’intérieur de l’enveloppe de canal, tag de canal, signature et vérification Schnorr BIP-340, trois relais, reprise des connexions, rétention demandée de deux heures.
- **Tor**: Arti Mobile optionnel pour le WebSocket Nostr. Pas de retour silencieux au réseau direct.
- **Android**: permission Internet et permissions Nearby nécessaires; service foreground avec notification pour le maillage actif.

## Pas encore équivalent à Bitchat

- Pas de compatibilité du protocole ou de l’identité avec Bitchat, ni de canaux Nostr géographiques.
- Pas de socket Wi-Fi Aware haut débit, de transfert de fichiers, de chiffrement Noise interopérable vérifié, ni de redondance de relai/ack hors du transport actuel.
- Les relais Nostr configurés sont fixes dans le code. L’étiquette de canal et les métadonnées de connexion restent visibles aux relais; l’option Tor réduit l’exposition réseau mais ne supprime pas ces métadonnées.
- Le service foreground maintient le processus prioritaire mais ne restaure pas encore automatiquement une session si Android tue le processus.

## Avant diffusion grand public

1. Compiler sur CI avec les dépendances verrouillées, vérifier l’APK minifié et tester les versions Android 8 à 15.
2. Tester deux à huit téléphones de fabricants différents: autorisations, MTU, perte et réordonnancement de fragments, redémarrage Bluetooth/Wi-Fi, passage en arrière-plan, batterie et relais Tor.
3. Ajouter tests de fuzzing et vecteurs publics pour le protocole Noise, ainsi que tests d’interopérabilité Nostr et tests réseau simulés.
4. Faire auditer le code de chiffrement et les chemins de stockage/effacement avant tout usage sensible.


## Ajouts expérimentaux 3.1

Un PC peut être client Bluetooth LE d’un téléphone Android Maillage par écriture et notification. Le code prend en charge la fragmentation avec un MTU minimal, mais la liaison réelle reste à tester; le PC n’héberge pas son propre service ni ne relaie entre plusieurs PC. Android propose aussi une réinitialisation locale avec confirmation, sans prétendre supprimer les copies distantes ni garantir l’effacement physique.

L’état précis des tests et ce qui manque encore sont décrits dans `ETAT-ET-SUITE.md`.
