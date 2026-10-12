# Third-party notices

## AstolfoVisuals

Source: https://github.com/SRS-0/AstolfoVisuals
Commit: 13ca7da2a789c45a45b9c5c3eea7bb200b00ea2f

The sidebar, layout, palette and easing in ModelMirageScreen.java and
gui/MirageUi.java are adapted from
xyz.angames.astolfoclient.client.gui.ClickGuiScreen and clickgui.GuiUtils.
The HUD panel composition and hotkey/settings controls are adapted from its
Interface/HUD managers. The BoyKisser mascot frames under assets/modelmirage/mascot and MSDF font
atlases (including bold, the Astolfo logo, watermark and interface icon
atlases) under assets/modelmirage/ui/fonts are copied from that repository;
shaders/core/msdf.fsh adapts its MSDF fragment shader. assets/modelmirage/ui/
mob_icon.png is copied from the same source. HUD geometry,
shadows, animated digits, health bars, effect cards, transparent inventory
and armor, and keybind/module transitions adapt its HUD managers for the
Minecraft 26.2 extraction renderer.

The upstream repository describes recovered/decompiled source and does not
contain a LICENSE file at this commit. The project owner confirmed permission
to use and include this work. ModelMirage's MIT license does not grant new
rights to this third-party material, including the adapted HUD code and copied
BoyKisser frames. This notice is included in release JARs.

## Blood Axe Mace

The model and texture in the separate ModelMirage catalog come from the
resource pack `§4Blood Axe Mace` supplied for this project. Its pack.mcmeta
credits `keywysa+`. The project owner confirmed permission to include and
publish both the model and its dependent texture. These assets are excluded
from ModelMirage's MIT license and are not bundled in its JAR.

Catalog: https://github.com/hhhhhffd/ModelMirage-catolog

## LWJGL Assimp bindings and Assimp

ModelMirage release JARs embed `org.lwjgl:lwjgl-assimp:3.4.1` and its
natives for Linux x64, Windows x64 and macOS (x64, arm64) to import OBJ,
FBX, glTF, Blender and other 3D formats.

- LWJGL — BSD-3-Clause, https://www.lwjgl.org/license
- Open Asset Import Library (Assimp) 6.0 — BSD-3-Clause,
  https://github.com/assimp/assimp/blob/master/LICENSE
- Draco (bundled with the Assimp natives) — Apache-2.0,
  https://github.com/google/draco/blob/main/LICENSE

The approach of shipping LWJGL Assimp inside a Fabric 26.2 mod follows
"Lumina's Customs: 3D Models" (fbx-moffler, MIT,
https://modrinth.com/mod/lc3dm). No code from that mod is included.

## Development tools

The Fabric example mod template is published under CC0-1.0:
https://github.com/FabricMC/fabric-example-mod/tree/26.2

Minecraft Mod MCP is a development tool, not bundled in the released JAR:
https://github.com/langyo/minecraft-mod-mcp
