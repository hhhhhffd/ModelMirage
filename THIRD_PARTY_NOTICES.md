# Third-party notices

## AstolfoVisuals

Source: https://github.com/SRS-0/AstolfoVisuals
Commit: 13ca7da2a789c45a45b9c5c3eea7bb200b00ea2f

The sidebar, layout, palette and easing in ModelMirageScreen.java,
gui/AstolfoUi.java and gui/AstolfoButton.java are adapted from
xyz.angames.astolfoclient.client.gui.ClickGuiScreen and clickgui.GuiUtils.
The HUD panel composition and hotkey/settings controls are adapted from its
Interface/HUD managers. The BoyKisser frames under assets/modelmirage/astolfo/
boykisser and MSDF font atlases (including bold, the Astolfo logo, watermark
and interface icon atlases) under assets/modelmirage/astolfo/fonts are copied
from that repository; shaders/core/msdf.fsh adapts its MSDF fragment shader.
The mob_icon.png asset is copied from the same source. HUD geometry,
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

## Development tools

The Fabric example mod template is published under CC0-1.0:
https://github.com/FabricMC/fabric-example-mod/tree/26.2

Minecraft Mod MCP is a development tool, not bundled in the released JAR:
https://github.com/langyo/minecraft-mod-mcp
