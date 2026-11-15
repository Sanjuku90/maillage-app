# Confidentialité de Maillage

## Réseau local

Bluetooth LE et Wi-Fi Aware servent à découvrir les appareils et à transporter les trames du canal. L’app ne lit pas les coordonnées GPS. Android peut toutefois exiger une autorisation de proximité ou, sur des versions plus anciennes, de localisation pour ces API radio.

## Relais Nostr

Quand l’option Internet est activée, Maillage publie les enveloppes chiffrées sur trois relais publics. Le texte des messages n’est pas transmis en clair. Les relais peuvent voir l’adresse publique Nostr générée par l’app, les heures d’activité, l’étiquette du canal et la taille approximative des trames; une expiration de deux heures est demandée, mais ils peuvent conserver les événements plus longtemps. Les relais sont des services tiers, pas des serveurs contrôlés par Maillage.

## Tor

Tor est facultatif. S’il est choisi, le WebSocket Nostr n’est créé qu’après le démarrage d’Arti et passe par son proxy SOCKS. En cas d’échec de Tor, les transports locaux continuent, mais les relais Internet restent coupés; il n’y a pas de repli direct.

## Stockage local

Le mot de passe du canal, l’identité Nostr et l’historique local sont chiffrés avec une clé Android Keystore. Les sauvegardes Android sont désactivées. Les identités peuvent être perdues lors d’une réinstallation ou d’une réinitialisation de l’appareil.

Le cache Android des trames de groupe est également conservé chiffré avec la clé Keystore, avec une limite de 60 trames et une durée de reprise d'une heure. Il peut contenir des trames à relayer, sans garantir leur livraison. Il est purgé lors des vérifications d'expiration lorsque l'application est active ou redémarre, pas nécessairement à l'heure exacte si elle reste fermée. Effacer l'historique d'une conversation ne supprime pas ce cache ; la réinitialisation locale efface les préférences et la clé associée. L'effacement physique du support n'est pas garanti.

Les empreintes des contacts que l'utilisateur marque explicitement comme vérifiés sont conservées chiffrées localement, ainsi que les alertes de changement détectées. Le stockage est limité à 100 contacts ; une réinitialisation locale supprime ces données et la clé associée. Une comparaison manuelle ne garantit pas que le reste du protocole ou l'appareil soit sûr.

## Mesures de prudence

Aucune télémétrie n’est intégrée volontairement. Cette version expérimentale n’a pas encore été auditée ni testée sur une matrice complète d’appareils. Ne l’utilise pas pour des informations dont la confidentialité est critique ou pour une urgence.

## PC expérimental

Le client PC peut utiliser Tor seulement si un proxy Tor local est installé et lancé séparément. Si cette option est choisie, les connexions aux relais sont configurées en SOCKS5h sans repli direct; ce comportement reste à tester sur le réseau réel. La file de messages est conservée en mémoire seulement, pour une heure au plus, et effacée à la fermeture.

Le Bluetooth PC utilise le PC comme client d’un téléphone Maillage Android. Aucun réseau Internet n’est lancé si l’option Nostr est désactivée. La réinitialisation Android supprime les données locales enregistrées et les clés associées, sans supprimer les copies distantes ni garantir un effacement physique des supports.
