# SuperLens · Fanou (français)

**Un petit phare qui vit sur ton bureau, et qui voit la pièce de cinq façons à la fois.**

Fanou, c'est la pile de capteurs SuperLens transformée en compagnon de bureau. Sa lampe est un lidar 360° qui tourne au sommet de la galerie. Son visage est un petit écran derrière une fenêtre. Une caméra regarde par un hublot en laiton, et deux radars (24 GHz et 60 GHz) dorment derrière la paroi peinte. Il t'entend grâce à un micro et te répond par un haut-parleur caché dans son île de rochers.

Fanou peut **voir** (caméra, lidar), **ressentir** (qui est dans la pièce, comment on bouge, si on respire), **écouter** (micro) et **parler** (haut-parleur, yeux expressifs). Le gros du calcul tourne sur ton ordinateur, qui fusionne tout en un modèle vivant de la pièce.

<p align="center">
  <img src="docs/images/fanou_front.png" alt="Fanou, un phare rayé rouge et blanc sur un socle rocheux, avec un écran-visage à deux yeux, une caméra-hublot en laiton et une lanterne lidar au sommet" width="280">
  <img src="docs/images/fanou_face.png" alt="Gros plan du visage de Fanou : deux yeux lumineux dans la fenêtre, le hublot caméra au-dessus" width="280">
</p>

Page de présentation : ouvre [`docs/index.html`](docs/index.html) dans un navigateur (FR/EN).

> **État : rév. E (Fanou) conçue, pas encore fabriquée.** La mécanique est terminée et vérifiée sans collision en CAO, le câblage est le même qu'en rév. D. Le firmware et le logiciel PC arrivent ensuite.

## Ce qu'on veut faire

Chaque capteur seul est à moitié aveugle. Un lidar dessine des murs parfaits mais ne distingue pas une personne d'un portemanteau. Un radar sait qui bouge et qui respire, mais ne sait pas dessiner la pièce. Une caméra voit tout mais ne comprend rien aux distances. Fanou les réunit dans un même corps, sur une même horloge, et donne au résultat une personnalité.

1. **Capter : Fanou est bête exprès.** L'ESP32-S3 lit chaque capteur, horodate chaque paquet et envoie tout en Wi-Fi. Il dessine lui-même ses yeux et joue ses sons.
2. **Fusionner : l'ordinateur réfléchit.** Il place chaque mesure dans le même repère 3D et affiche une vue superposée, en direct, dans Rerun.
3. **Vivre : un compagnon qui remarque.** Fanou tourne les yeux vers toi quand tu t'assois, baisse la lumière quand tu pars, te rappelle de respirer quand tu restes figé trop longtemps, et répond aux questions sur la pièce. Pas de cloud, aucune image n'a besoin de sortir de la pièce.

## En bref

- **L'attachement d'abord** : une forme familière, un visage qui réagit, une voix.
- **Presque sans soudure** : prises Dupont et connecteurs Wago partout. Seules deux barrettes de broches sont à souder (XIAO et ampli).
- **Impression sans supports** : 5 pièces + un gabarit, déjà orientées. Les rayures sont de simples changements de filament à des hauteurs fixes.
- **Radars cachés derrière la peinture** : la paroi de 1,6 mm en PETG fait une demi-longueur d'onde à 60 GHz, les radars voient à travers.
- **Alimentation USB-C** : un câble entre à l'arrière de l'île. Un chargeur ou une batterie qui fournit ≥ 2 A suffit.
- **Budget** : environ 240 € de pièces sur AliExpress avec le lidar D800 (environ 200 € avec le D500).

## Documentation

La documentation détaillée est en anglais, pour toucher un maximum de monde :

| Étape | Document |
|---|---|
| Acheter | [docs/bom.md](docs/bom.md) |
| Imprimer | [docs/printing.md](docs/printing.md) |
| Câbler (29 connexions, guide débutant) | [docs/wiring.md](docs/wiring.md) |
| Assembler | [docs/assembly.md](docs/assembly.md) |
| Comprendre l'architecture | [docs/architecture.md](docs/architecture.md) |

Le modèle Blender est dans [`hardware/blender/fanou.blend`](hardware/blender/fanou.blend), la source paramétrique dans [`hardware/cad/fanou.scad`](hardware/cad/fanou.scad).

Les trois règles de câblage, en français :

1. Fie-toi au nom imprimé sur la carte (TX, GND, 5V…), pas à la couleur du fil.
2. Le 5 V ne va jamais sur une broche D0–D10.
3. Branche l'alimentation en dernier, après avoir tout relu.

## Licences

Matériel : CERN-OHL-P-2.0 · Logiciel : MIT · Documentation : CC BY 4.0. Voir [README.md](README.md#licences).
