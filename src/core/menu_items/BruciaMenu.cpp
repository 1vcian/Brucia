#include "BruciaMenu.h"
#include "brucia_flame.h"
#include "companion/companion.h"
#include "core/display.h"

// Selecting "Brucia" from the home screen enters companion mode straight away.
void BruciaMenu::optionsMenu() { companionModeUSB(); }

// The SVG flame, two layers in the theme's primary (ember) + secondary (amber) shades.
void BruciaMenu::drawIcon(float scale) {
    clearIconArea();
    int x = iconCenterX - BRUCIA_FLAME_OUTER_W / 2;
    int y = iconCenterY - BRUCIA_FLAME_OUTER_H / 2;
    tft.drawXBitmap(
        x, y, brucia_flame_outer, BRUCIA_FLAME_OUTER_W, BRUCIA_FLAME_OUTER_H, bruceConfig.priColor,
        bruceConfig.bgColor
    );
    tft.drawXBitmap(
        x, y, brucia_flame_inner, BRUCIA_FLAME_INNER_W, BRUCIA_FLAME_INNER_H, bruceConfig.secColor
    );
}
