# Single source of truth for the firmware version: read the repo-root VERSION file and inject it as
# BRUCE_VERSION, so bumping VERSION updates the on-device version string (and the companion CAPS fw)
# without editing platformio.ini. Paired with android/app/build.gradle.kts which reads the same file.
import os
Import("env")
root = env.get("PROJECT_DIR")
ver = "dev"
try:
    with open(os.path.join(root, "VERSION")) as f:
        ver = f.read().strip() or "dev"
except Exception:
    pass
env.Append(CPPDEFINES=[("BRUCE_VERSION", env.StringifyMacro(ver))])
print("BRUCE_VERSION from VERSION file:", ver)
