from typing import TYPE_CHECKING, Any

if TYPE_CHECKING:
    Import: Any = None
    env: Any = {}

from os import remove, rename
from os.path import isfile, join

Import("env")  # type: ignore

FRAMEWORK_DIR = env.PioPlatform().get_package_dir("framework-arduinoespressif32-libs")
board_mcu = env.BoardConfig()
mcu = board_mcu.get("build.mcu", "")
patchflag_path = join(FRAMEWORK_DIR,mcu, "lib", ".patched")

# patch file only if we didn't do it befored
if not isfile(join(FRAMEWORK_DIR,mcu, "lib", ".patched")):
    original_file = join(FRAMEWORK_DIR,mcu, "lib", "libnet80211.a")
    patched_file = join(
        FRAMEWORK_DIR, mcu, "lib", "libnet80211.a.patched"
    )

    if mcu=="esp32c5" or mcu=="esp32c6" :
        env.Execute(
            "pio pkg exec -p toolchain-riscv32-esp -- riscv32-esp-elf-objcopy  --weaken-symbol=ieee80211_raw_frame_sanity_check %s %s"
            % (original_file, patched_file)
        )
    elif mcu=="esp32p4":
        """Do nothing"""
    else:
        env.Execute(
            "pio pkg exec -p toolchain-xtensa-%s -- xtensa-%s-elf-objcopy  --weaken-symbol=ieee80211_raw_frame_sanity_check %s %s"
            % (mcu, mcu, original_file, patched_file)
        )

    if isfile("%s.old" % (original_file)):
        remove("%s.old" % (original_file))

    if isfile(original_file):
        rename(original_file, "%s.old" % (original_file))
    else:
        print("Patch: Original file not found")

    if isfile(patched_file):
        rename(patched_file, original_file)
    else:
        print("Patch: Patched file not found")


    def _touch(path):
        with open(path, "w") as fp:
            fp.write("")

    env.Execute(lambda *args, **kwargs: _touch(patchflag_path))


# Web assets are generated entirely locally. No build-time source uploads or
# dependence on the uptime/behavior of a remote minification service.
from pathlib import Path
import sys
sys.path.insert(0, env.get("PROJECT_DIR"))
from tools.web_resources import embed_web

project_dir = Path(env.get("PROJECT_DIR"))
changed = embed_web(
    project_dir / "embedded_resources/web_interface",
    project_dir / "include/webFiles.h",
    project_dir / "embedded_resources/web_interface/checksum.sha256",
)
print("[WEB RESOURCES] " + ("Generated local gzip assets" if changed else "Up to date"))
