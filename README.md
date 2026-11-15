# Maillage 3.1 expérimental (Android + PC)

Messagerie de groupe chiffrée avec trois voies de transport: Bluetooth LE mesh, Wi-Fi Aware local optionnel, et relais Nostr sur Internet. Tor est optionnel pour le trafic Nostr.

## Fonctionnalités incluses

- **BLE mesh**: découverte et relais multi-sauts, plafond de 16 sauts (ce n’est pas une garantie de portée), fragmentation/réassemblage quand le MTU BLE est petit, déduplication et files bornées.
- **Wi-Fi Aware**: découverte locale et transport de trames chiffrées par petits fragments, seulement si le téléphone le prend en charge. Le BLE reste actif en secours.
- **Nostr**: publication et réception de trames déjà chiffrées sur trois relais publics. Les événements sont signés BIP-340 via secp256k1 et filtrés par empreinte de canal. Tor peut forcer le trafic Nostr à travers Arti; en cas d’échec de Tor, l’app ne revient pas en connexion directe.
- **Chiffrement**: clé de canal Argon2id puis AES-256-GCM; identité d’appareil ECDSA P-256 dans Android Keystore; sessions privées Noise XX (X25519 / ChaCha20-Poly1305).
- **En arrière-plan**: notification persistante pendant que le maillage est actif. « Quitter » arrête les transports.

## Confidentialité à connaître

Le texte des messages n’est pas envoyé en clair aux relais Nostr. Les relais voient cependant l’heure, l’adresse Nostr publique de l’appareil et une étiquette correspondant au canal. Une expiration de deux heures est demandée, mais un relais peut conserver les événements plus longtemps. Les autres membres du canal qui connaissent le mot de passe peuvent lire le canal.

Sur une nouvelle installation Android, le mode Internet est désactivé par défaut; active-le pour utiliser Nostr. Un choix déjà enregistré est conservé. Tor est désactivé par défaut et peut ralentir ou empêcher la connexion.

## Installation / build

Le dépôt contient un workflow GitHub Actions `Build APK`. Pousse les fichiers du projet sur GitHub, lance le workflow et récupère l’APK dans les artefacts du run.

## Limites importantes

Cette version **n’est pas encore un clone compatible avec Bitchat**: elle utilise ses propres enveloppes de messages et son propre tag Nostr. Elle ne rejoint pas leurs canaux géographiques ni leurs messages Nostr. Wi-Fi Aware utilise les messages de découverte fragmentés, pas encore une liaison socket haut débit. Le Noise maison, les trois transports et leur comportement sur les appareils de différents fabricants doivent encore passer des tests multi-appareils et un audit indépendant.

N’utilise pas cette version pour des urgences ou des informations dont la sécurité est critique. Le code source est livré, mais l’environnement de travail ici ne dispose pas de Gradle ni d’accès réseau pour télécharger les dépendances et compiler l’APK.

## Prototype PC

Un client PC expérimental pour le canal texte se trouve dans `desktop/`, avec Bluetooth local et Internet Nostr optionnel. Le Bluetooth PC est un client qui se connecte à un téléphone Android; il ne crée pas de maillage PC-à-PC autonome. Aucun échange Android-PC n’a encore été validé sur des appareils réels. Pas de Wi-Fi Aware, de messages privés ni de médias sur PC à ce stade. L’option Tor sur PC nécessite un service Tor local séparé.

Le code Android et PC reste expérimental: le build Android/PC et les tests de relais n’ont pas été exécutés dans l’environnement ici, faute d’outils et d’accès réseau. Les fonctions manquantes et validations requises sont dans `ETAT-ET-SUITE.md`.

## Effacement local Android

L’app propose une réinitialisation avec confirmation, qui supprime les préférences, l’historique et les clés Android Keystore de cette installation. Elle n’efface pas les copies sur d’autres appareils ou des relais, et ne garantit pas l’effacement physique des supports.

## Construire sur GitHub Actions

Les workflows et les instructions complètes se trouvent dans `BUILD-GITHUB-ACTIONS.md`. Ils préparent un APK Android de test, une release non signée et des programmes Windows/Linux. Une release Android signée peut être demandée manuellement après configuration des secrets. Aucun build n’a été exécuté ici; attendre un run réussi avant de télécharger un fichier.

## Signature Windows facultative

Un workflow manuel `Build Windows signé` et le guide `SIGNATURE-WINDOWS.md` sont préparés pour un certificat Authenticode PFX compatible fourni par l’utilisateur. Aucun certificat ni programme signé n’est inclus. Une signature valide ne garantit pas la disparition de l’avertissement SmartScreen.

## Option gratuite Microsoft Store

Le workflow manuel `Préparer MSIX Microsoft Store` et `SIGNATURE-GRATUITE-STORE.md` préparent une soumission MSIX. Le paquet reste non signé avant certification par Microsoft; le workflow ne publie rien et n’a pas été exécuté.
