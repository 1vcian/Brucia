#ifndef __BRUCIA_MENU_H__
#define __BRUCIA_MENU_H__

#include <MenuItemInterface.h>

// Brucia: a top-level home entry that launches the companion/offload mode
// directly (the ESP streams to the Android app over USB-CDC).
class BruciaMenu : public MenuItemInterface {
public:
    BruciaMenu() : MenuItemInterface("Brucia App") {}

    void optionsMenu(void);
    void drawIcon(float scale);
    bool hasTheme() { return false; }
    const String &themePath() override {
        static const String empty = "";
        return empty;
    }
};

#endif
