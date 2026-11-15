# Maillage pour PC, expérimental

Le client PC contient un transport Bluetooth LE local et un transport Nostr optionnel. La communication Bluetooth est conçue pour joindre un téléphone Android utilisant cette version de Maillage; le PC agit comme client BLE et n’annonce pas son propre service. Deux PC ne communiquent donc pas directement par Bluetooth sans téléphone relais.

**Pas encore validé sur de vrais appareils.** Windows et Linux ont un workflow de build expérimental. Le code n’est pas compatible avec le protocole Bitchat et n’est pas adapté aux urgences ni aux informations sensibles.

## Lancer depuis le code

```bash
python -m venv .venv
# Windows : .venv\Scripts\activate
# macOS / Linux : source .venv/bin/activate
python -m pip install -r requirements.txt
python main.py
```

Linux peut nécessiter `python3-tk`, BlueZ et une permission d’accès au Bluetooth. Sur Windows, activer Bluetooth et autoriser Maillage à l’utiliser. Pour le mode local, cocher « Bluetooth local » sur le PC, lancer Maillage Android près du PC avec Bluetooth activé et choisir le même mot de passe. Ce parcours n’a pas été testé sur du matériel ici.

Le workflow GitHub Actions `Build Maillage Desktop` tente de créer des exécutables Windows et Linux. Vérifier le résultat du run : ces fichiers ne sont pas signés ni audités.

## Transports, sécurité et limites

- Bluetooth : découverte du téléphone, abonnement aux notifications et écriture des trames chiffrées avec fragmentation. La livraison n’est pas confirmée de bout en bout. Pas de Wi-Fi Aware sur PC.
- Nostr : à activer explicitement sur PC et Android si l’on souhaite passer par Internet. Les relais voient les métadonnées, l’identité publique et le tag de canal.
- Tor : démarrer séparément un service Tor local, cocher Tor et entrer le port SOCKS (9050 par défaut). Le code utilise SOCKS5h sans repli direct prévu; vérifier les fuites réseau et DNS sur des appareils réels.
- Pas encore de messages privés, médias, géolocalisation ou compatibilité Bitchat sur PC.
- Identités de session temporaires, pas d’historique local ni d’accusé de réception. La file Nostr en mémoire expire après une heure et disparaît à la fermeture. La file Bluetooth est également temporaire.
