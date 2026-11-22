# Tariki (طريقي) : carte hors ligne, GPS, alertes, chat et musique (Algérie)

## Les trois workflows GitHub Actions

1. **Build Algeria offline map** (zone `algerie-complete`) : fabrique la carte `algeria-map.mbtiles` (routes, noms de lieux, côte, limites) et le fond `algeria-base.geojson.gz` (mer, pays voisins avec leurs noms, terres algériennes précises), puis les publie dans la Release `map-latest`.
2. **Build Algeria routing data** : fabrique le fichier d'itinéraires `algeria-routing.bin.gz` (routes et villes, communes et villages avec leur numéro de wilaya) et le publie dans la Release `routes-latest`.
3. **Build Android APK** : compile l'APK (environ 25 Mo). La carte, le fond et les itinéraires ne sont pas dedans : l'appli les télécharge depuis les deux Releases au premier lancement. Le workflow prévient si un de ces fichiers manque.

Ordre : 1 et 2 (en même temps si tu veux), puis 3. Il suffit de relancer 1 ou 2 pour mettre à jour la carte ou les itinéraires, mais les téléphones qui les ont déjà téléchargés gardent leur version.

L'adresse du serveur est fixée dans `.github/workflows/build-apk.yml` (`ALERTS_URL`). Les adresses de téléchargement sont calculées à partir du nom du dépôt.

## L'appli

- **Premier lancement** : un écran télécharge la carte, le fond et les itinéraires (environ 150 Mo, Wi-Fi conseillé, reprise si la connexion coupe). Ensuite tout marche sans internet. Il n'y a pas de bouton pour supprimer ces fichiers.
- **Carte** : mer en bleu, pays voisins en gris avec leur nom en arabe ou en français, Algérie en beige ; la carte ne s'éloigne pas de la région.
- **GPS** : vitesse en km/h, suivi en arrière-plan avec une notification, écran allumé tant que l'appli est ouverte. Au démarrage, une fenêtre demande les autorisations : le GPS est obligatoire, les notifications et la batterie sont facultatives.
- **Signalement** (bouton +) : un seul type, danger sur la route (خطر على الطريق), avec une description et une photo facultatives, puis une confirmation. Le signalement apparaît tout de suite sur la carte ; la description et la photo apparaissent seulement après validation dans le plugin. Sans réseau, le signalement attend et part dès le retour de la connexion.
- **Près d'un danger** : double bip et voyant rouge à 200 m puis 100 m, puis la question هل التبليغ صحيح؟ après le passage. Toucher une alerte affiche le nombre de signalements, les descriptions et les miniatures des photos (toucher une photo l'agrandit).
- **Menu ☰** : loupe (itinéraire), 💬 chat, 🎵 musique, ? aide, AR/FR. ◎ en bas à gauche recentre la carte.
- **Itinéraire** : la loupe ouvre la recherche d'une ville ou d'une commune, affichée ainsi : 16 - Bouira - البويرة (noms sans accents). Le départ est toujours la position GPS. Le tracé s'efface derrière la voiture ; le cercle bleu sous la vitesse affiche la distance et la durée restantes, le ✕ bleu annule l'itinéraire. Hors du tracé pendant 20 secondes, l'itinéraire est recalculé tout seul.
- **Chat entre voyageurs** : messages texte par Bluetooth, de téléphone à téléphone (environ 30 m), gardés 24 heures, avec répondre, masquer et signaler. Le Bluetooth ne tourne que pendant que l'appli est ouverte. Dès qu'un téléphone a internet, il envoie les messages au plugin pour la modération, et récupère la liste des messages masqués.
- **Musique** : genres et morceaux ajoutés dans le plugin ; chaque morceau se télécharge une fois puis s'écoute sans internet, même écran éteint. Une petite barre de lecture s'affiche sur la carte.
- **Aide** (bouton ?) : explique chaque élément de l'écran, avec en bas le bouton Nous contacter (téléphone ou e-mail facultatif, message envoyé au plugin, ou mis en attente sans réseau).

## Itinéraires : détails techniques

- Le fichier est lu directement dans le stockage du téléphone (rien n'est chargé en mémoire). Le calcul est un A* : les petites rues ne sont utilisées que près du départ et de l'arrivée ; si rien n'est trouvé, le rayon est élargi puis supprimé.
- Les sens uniques sont respectés ; les interdictions de tourner ne le sont pas. Les durées sont estimées à partir d'une vitesse moyenne par type de route (ou de `maxspeed`), sans circulation.
- Le numéro de wilaya vient des limites des wilayas dans OpenStreetMap (`tools/admin_areas.py`). Le fichier passe en version 2 ; l'appli lit encore la version 1 (sans numéros).
- Le format du fichier est décrit en tête de `tools/build_routing_graph.py` ; un test vérifie que le lecteur Kotlin et le fichier Python sont d'accord.

## Serveur

Le plugin WordPress DZ Trafic Alerts 2.0 (livré à part) reçoit les signalements, les photos, les messages du chat et les messages de contact, et fournit la liste de musique. Toute la modération se fait dans son menu Tariki.

## Contenu technique

- `tools/` : carte (`prepare_layers.py`), fond de carte (`build_base_layer.py`), itinéraires (`build_routing_graph.py`), wilayas (`admin_areas.py`), contrôle (`inspect_mbtiles.py`), polices (`fetch_glyphs.py`).
- `android/app/src/main/assets/style.json` : style de la carte.
- `android/app/src/main/java/com/dztrafic/carte/` : l'appli. `SetupActivity` (premier lancement), `MainActivity` (carte), `ChatActivity` et `ChatHub` (chat Bluetooth, liaison `TransportBle` reprise de Jirani), `MusicActivity`, `MusicLibrary` et `MusicPlayer`, `OfflineData` (téléchargements), `AlertsApi` et `AlertsRepository` (serveur).
- `tests/` : tests automatiques, lancés à chaque exécution des workflows.

Données : © OpenStreetMap contributors (ODbL) ; Natural Earth (domaine public).
