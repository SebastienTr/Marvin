# SuperLens (français)

**Une tête de capteurs portable et open source, qui voit une pièce de plusieurs façons à la fois.**
Un lidar 360°, deux radars (24 GHz et 60 GHz) et une caméra envoient leurs données à un ordinateur. Celui-ci les affiche superposées, en direct.

> **État : rév. C conçue, pas encore fabriquée.** La mécanique et le câblage sont terminés et vérifiés en CAO. Le firmware et le visualiseur arrivent ensuite.

## En bref

- **Sans soudure** : ESP32 pré-soudé, prises Dupont et deux connecteurs Wago.
- **Impression sans supports** : 5 pièces en PETG, déjà orientées pour le plateau.
- **Portable ou posé** : une poignée pistolet qui s'emboîte dans un socle, avec un écrou de trépied 1/4" au culot.
- **Un écran couleur 1,69" au dos** : mini-carte lidar vue de dessus et cibles radar, même sans PC.
- **Ta batterie USB-C** dans la poche, avec un câble de 1 m (≥ 1,5 A).
- **Budget** : environ 160 € de pièces sur AliExpress.

## Documentation

La documentation détaillée est en anglais, pour toucher un maximum de monde :

| Étape | Document |
|---|---|
| Acheter | [docs/bom.md](docs/bom.md) |
| Imprimer | [docs/printing.md](docs/printing.md) |
| Câbler (20 fils, guide débutant) | [docs/wiring.md](docs/wiring.md) |
| Assembler | [docs/assembly.md](docs/assembly.md) |
| Comprendre l'architecture | [docs/architecture.md](docs/architecture.md) |

Les trois règles de câblage, en français :

1. Fie-toi au nom imprimé sur la carte (TX, GND, 5V…), pas à la couleur du fil.
2. Le 5 V ne va jamais sur une broche D0–D10.
3. Branche la batterie en dernier, après avoir tout relu.

## Licences

Matériel : CERN-OHL-P-2.0 · Logiciel : MIT · Documentation : CC BY 4.0. Voir [README.md](README.md#licences).
