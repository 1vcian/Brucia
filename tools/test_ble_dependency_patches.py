"""Exercise real dependency patches and virtual callback dispatch on NimBLE 1/2."""
from contextlib import redirect_stdout
import io
import os
from pathlib import Path
import runpy
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class BleDependencyPatchesTest(unittest.TestCase):
    def test_build_patches_only_the_active_environment(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            paths = []
            for board in ("active", "other"):
                path = tmp / board / "ESP PN32BLE/src/pn532_ble.cpp"
                path.parent.mkdir(parents=True)
                path.write_text("class scanCallbacks {}; new scanCallbacks();")
                (path.parent / "pn532_ble.h").write_text("#include <NimBLEDevice.h>\n")
                paths.append(path)

            class Env:
                def subst(self, value): return str(tmp)
                def get(self, key): return "active"
                def AddBuildMiddleware(self, callback, pattern=None): pass
                def Exit(self, code): raise SystemExit(code)

            with redirect_stdout(io.StringIO()):
                runpy.run_path(str(ROOT / "patch_library_conflicts.py"),
                               init_globals={"env": Env(), "Import": lambda *_: None})
            self.assertIn("BruciaPn532ScanCallbacks", paths[0].read_text())
            self.assertIn("class scanCallbacks", paths[1].read_text())

    def test_unique_types_and_virtual_dispatch_are_idempotent(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            sources = []
            for folder, name, target in [
                ("ESP Chameleon Ultra", "chameleonUltra.cpp", "ChameleonUltra"),
                ("ESP Amiibolink", "amiibolink.cpp", "amiibolink"),
            ]:
                path = tmp / ".pio/libdeps/test" / folder / "src" / name
                path.parent.mkdir(parents=True)
                # Installed libraries must contain every patched file.
                path.with_suffix(".h").write_text("#include <NimBLEDevice.h>\n")
                path.write_text('''
#ifdef NIMBLE_V2_PLUS
#define NimBLEAdvertisedDeviceCallbacks NimBLEScanCallbacks
#endif
class scanCallbacks : public NimBLEAdvertisedDeviceCallbacks {
    void onResult(NimBLEAdvertisedDevice* advertisedDevice) {
        if (advertisedDevice->getName() == "TARGET") NimBLEDevice::getScan()->stop();
    }
};
'''.replace("TARGET", target))
                sources.append(path)
            old_dir = Path.cwd()
            try:
                os.chdir(tmp)
                with redirect_stdout(io.StringIO()):
                    runpy.run_path(str(ROOT / "patch_library_conflicts.py"))
                    first = [p.read_text() for p in sources]
                    runpy.run_path(str(ROOT / "patch_library_conflicts.py"))
                self.assertEqual(first, [p.read_text() for p in sources])
            finally:
                os.chdir(old_dir)
            shim = r'''
#include <cassert>
#include <string>
struct NimBLEAdvertisedDevice { std::string name; std::string getName() const { return name; } };
struct NimBLEScanCallbacks { virtual void onResult(const NimBLEAdvertisedDevice*) {} };
#ifndef NIMBLE_V2_PLUS
struct NimBLEAdvertisedDeviceCallbacks { virtual void onResult(NimBLEAdvertisedDevice*) {} };
#endif
static bool stopped = false;
struct Scan { void stop() { stopped = true; } };
struct NimBLEDevice { static Scan* getScan() { static Scan scan; return &scan; } };
'''
            main = r'''
int main() {
    BruciaChameleonScanCallbacks chameleon;
    BruciaAmiibolinkScanCallbacks amiibo;
    NimBLEAdvertisedDevice a{"ChameleonUltra"}, b{"amiibolink"};
    NimBLEAdvertisedDeviceCallbacks* first = &chameleon;
    NimBLEAdvertisedDeviceCallbacks* second = &amiibo;
    first->onResult(&a); assert(stopped); stopped = false;
    second->onResult(&b); assert(stopped);
}
'''
            cpp = tmp / "callbacks.cpp"
            cpp.write_text(shim + "\n".join(first) + main)
            for flags in ([], ["-DNIMBLE_V2_PLUS=1"]):
                exe = tmp / ("callbacks-v2" if flags else "callbacks-v1")
                subprocess.run([os.environ.get("CXX", "c++"), "-std=c++17", *flags,
                                str(cpp), "-o", str(exe)], check=True)
                subprocess.run([str(exe)], check=True)


if __name__ == "__main__":
    unittest.main()
