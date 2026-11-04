# Inherited from Bruce (https://github.com/pr3y/Bruce); Brucia change: reproducible year.
# CURRENT_YEAR comes from SOURCE_DATE_EPOCH, else the HEAD commit time of this checkout
# (tools/firmware_provenance.py), else the wall-clock year. Only that last fallback (no
# SOURCE_DATE_EPOCH and no own Git metadata, e.g. a bare tarball) makes builds of the same
# source differ by build date. The header is rewritten only when the year changes.
import os
import sys


def generate_build_header(root):
    sys.path.insert(0, os.path.join(root, "tools"))
    try:
        import firmware_provenance as fp
    finally:
        sys.path.pop(0)
    year, source = fp.build_year(os.environ, fp.head_commit_epoch(root))
    output_path = os.path.join(root, "include", "current_year.h")
    fp.write_if_changed(output_path, fp.render_year_header(year))
    print("Brucia CURRENT_YEAR:", year, "(" + source + ")")


def before_build(source, target, env):
    generate_build_header(env.get("PROJECT_DIR") or os.getcwd())


# Hook for PlatformIO
def on_pre_build(env):
    before_build(None, None, env)


# Register the pre-build hook
Import("env")
on_pre_build(env)
