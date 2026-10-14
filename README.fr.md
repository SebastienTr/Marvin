# Marvin (français)

**Un petit compagnon de bureau qui perçoit la pièce de quatre façons à la fois, et qui sait comment tu vas.**

Marvin est un robot open source pour ton bureau. Une caméra et un visage vivent derrière une vitre noire dans sa tête, et deux radars (24 GHz et 60 GHz) se cachent derrière la bande qui fait le tour de son corps. Il t'entend grâce à un micro et te répond par un haut-parleur sur le côté.

Il peut **voir** (caméra), **ressentir** (qui est dans la pièce, comment on bouge, et ta respiration et ton rythme cardiaque quand tu es assis devant), **écouter** (micro) et **parler** (haut-parleur, yeux expressifs). Le gros du calcul tourne sur ton ordinateur, qui fusionne tout en un modèle vivant de la pièce.

<p align="center">
  <img src="docs/images/robot_threequarter.png" alt="Le robot Marvin : un corps gris chaud avec une bande anthracite, une tête un peu plus large avec un visage en vitre noire et une molette orange" width="260">
  <img src="docs/images/robot_front.png" alt="Le robot de face : le visage en vitre noire avec ses yeux, la caméra au-dessus, la bande anthracite en dessous" width="280">
</p>

Page de présentation : ouvre [`docs/index.html`](docs/index.html) dans un navigateur (FR/EN).

> **État : rév. F, pièces du premier exemplaire commandées (livraison début octobre), rien n'est encore monté.** La moitié basse de la CAO est dessinée et imprimable ([`hardware/cad/marvin.scad`](hardware/cad/marvin.scad)) : corps, socle, support des radars et cou en TPU. La tête suivra une fois l'écran mesuré. Le firmware est écrit et testé sur l'ordinateur et en simulation, et une Wemos D1 mini le fait déjà tourner avec des capteurs simulés ; son code pour les vrais capteurs, l'écran, l'audio et la caméra n'a pas encore tourné sur le vrai matériel. Le logiciel côté ordinateur (fusion, événements de présence, l'appli, la voix locale) tourne déjà. Le phare de la rév. E est archivé dans [`hardware/archive/rev-e/`](hardware/archive/rev-e/). Le câblage est celui de la rév. D sans le lidar : depuis le 14 octobre 2026, Marvin n'en a plus (le D800 part dans un projet séparé de scanner 3D de pièce ; le logiciel sait toujours lire un lidar pour le banc et les outils USB).

## Pensé autour des capteurs

Le robot se tient droit ; **ce sont les capteurs qui sont inclinés à l'intérieur**, pas le corps.

- **Le cœur, au bureau** : le radar 60 GHz est sur un support incliné de 20° vers le haut. Son faisceau tombe sur la poitrine d'une personne assise à 0,65–1,1 m, à travers une paroi plate de 1,6 mm en PETG.
- **La pièce** : le radar 24 GHz, juste en dessous, incliné de 10°, couvre ±60°. En dessous et pas au-dessus, pour ne jamais masquer le faisceau du 60 GHz.
- **Visage et caméra** : l'écran reste vertical derrière une vitre en acrylique fumé ; la caméra est inclinée de 20° pour cadrer ton visage.
- **Silence pour le radar** : un amortisseur en TPU entre la tête et le corps évite qu'un choc sur la tête ou un tour de molette ne perturbe la mesure du cœur.
- **Compact** : environ 88 × 80 mm au sol, environ 12,5 cm de haut.

La mesure du cœur par radar est de niveau grand public : quelques battements d'erreur, et seulement si tu restes à peu près immobile. Ce n'est pas un appareil médical.

## En bref

- **Style sobre** : gris chaud, bande et visage anthracite, une seule molette orange. La personnalité passe par les yeux et le comportement.
- **Presque sans soudure** : prises Dupont et connecteurs Wago. Seules deux barrettes de broches sont à souder (XIAO et ampli).
- **Impression sans supports**, la bande anthracite par simple changement de filament.
- **Alimentation USB-C** : un chargeur ou une batterie qui fournit ≥ 2 A.
- **Budget** : environ 115 € de pièces.

## Documentation

La documentation détaillée est en anglais :

| Étape | Document |
|---|---|
| Acheter | [docs/bom.md](docs/bom.md) |
| Imprimer | [docs/printing.md](docs/printing.md) |
| Câbler (25 connexions, guide débutant) | [docs/wiring.md](docs/wiring.md) |
| Essayer l'électronique sur breadboard, avec un auto-test au démarrage | [docs/xiao-bench.md](docs/xiao-bench.md) |
| Assembler | [docs/assembly.md](docs/assembly.md) |
| Comprendre l'architecture | [docs/architecture.md](docs/architecture.md) |

Les trois règles de câblage, en français :

1. Fie-toi au nom imprimé sur la carte (TX, GND, 5V…), pas à la couleur du fil.
2. Le 5 V ne va jamais sur une broche D0–D10.
3. Branche l'alimentation en dernier, après avoir tout relu.

## Licences

Matériel : CERN-OHL-P-2.0 · Logiciel : MIT · Documentation : CC BY 4.0. Voir [README.md](README.md#licences).
