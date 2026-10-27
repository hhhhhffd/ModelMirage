<div align="center">

<h1>ModelMirage</h1>
<hr>

**Client-side Fabric mod: swap item and player models from an online catalog, plus a configurable HUD**

![Minecraft](https://img.shields.io/badge/MINECRAFT-26.2-62B47A?style=flat-square&logo=minecraft&logoColor=white)
![Fabric](https://img.shields.io/badge/FABRIC_LOADER-0.19.5-DBD0B4?style=flat-square)
![Java](https://img.shields.io/badge/JAVA-25-ED8B00?style=flat-square&logo=openjdk&logoColor=white)
![License](https://img.shields.io/github/license/hhhhhffd/ModelMirage?style=flat-square)

[Build](#build) · [Usage](#usage) · [Catalog](#model-catalog) · [License](#license)

</div>

> [!NOTE]
> Archived — no longer maintained.

![ModelMirage HUD menu](docs/screenshots/menu-hud-en.png)

## What it is

ModelMirage replaces item models, the player model and the HUD character with models downloaded from an online catalog, and adds a configurable HUD. Everything is client-side: the server sees vanilla items and other players see vanilla models. Models are fetched in the background, verified by SHA-256 and cached, so they work offline after the first download.

No release was published; build the JAR from source (see [Build](#build)).

## Features

- Item models: Minecraft JSON, OBJ, FBX, glTF/GLB, Blender and other 3D formats (decoded through LWJGL Assimp). A replacement applies to every item of the chosen type.
- Player model: your own and, separately, other players (static).
- HUD characters: animated PNG sequences.
- HUD panels: Watermark (ping, FPS, TPS, CPU/GPU load, K/D/A counters and more), coordinates, target, effects, inventory, armor and keys. Panels are positioned in the open chat with edge and grid snapping.
- Russian and English UI, chosen from the Minecraft language.

![3D models in the world](docs/screenshots/world-3d-models.png)

## Stack

| Part | Version |
| --- | --- |
| Minecraft | 26.2 |
| Fabric Loader | >= 0.19.5 |
| Fabric API | 0.161.0+26.2 |
| Java | 25 |
| Mod version | 0.4.2 |
| Model decoding | LWJGL Assimp 3.4.1 |
| Tests | JUnit 5 (`fabric-loader-junit`), see [docs/TESTING.md](docs/TESTING.md) |

## Usage

1. Put `ModelMirage-0.4.2.jar` and Fabric API for 26.2 into `mods/`.
2. In a world, press **Right Shift** (rebindable under Controls, ModelMirage).
3. Open **Items**, pick an item (or type its name, for example `diamond_sword`, and press Enter), then pick a model.
4. Drag the preview to rotate it. **Apply** enables the model, **Reset** restores the vanilla one.

Config files live in `config/modelmirage/`; the catalog cache is in `config/modelmirage/cache/`.

## Model catalog

The official catalog is [ModelMirage-catolog](https://github.com/hhhhhffd/ModelMirage-catolog). Models are not bundled in the JAR. A custom catalog is set with the JVM argument `-Dmodelmirage.catalogUrl=https://example.com/index.json` (plain HTTP is allowed only for `localhost`, `127.0.0.1` and `[::1]`). The index format is described by [docs/catalog.schema.json](docs/catalog.schema.json).

## Build

```bash
./gradlew build       # build and run tests (JDK 25)
./gradlew runClient   # development client
```

The JAR is written to `build/libs/ModelMirage-0.4.2.jar`.

## License

MIT, see [LICENSE](LICENSE). The GUI/HUD code, MSDF fonts and HUD character frames adapted from AstolfoVisuals are excluded from that license; details are in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
