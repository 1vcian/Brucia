"""Library patches reach dependencies installed after the pre-script, strictly.

PlatformIO installs owner-less registry specs such as ``ESP8266Audio@2.4.1``
during its Library Dependency Finder, after every pre-script has run. These
host tests drive patch_library_conflicts.py with a fake SCons environment and
synthetic sources (no third-party code): a late install must be patched by the
build middleware, and a pattern that no longer matches must fail the build.
"""
from contextlib import redirect_stdout, redirect_stderr
import io
import os
from pathlib import Path
import runpy
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "patch_library_conflicts.py"

AUDIO = {
    "src/libhelix-aac/sbrhuff.c": (
        "void DecodeSBRNoise(BitStreamInfo *bsi, PSInfoSBR *psi, SBRGrid *sbrGrid, "
        "SBRFreq *sbrFreq, SBRChan *sbrChan, int ch) {\n"
        "\tint noiseFloor, band;\n"
        "\tfor (noiseFloor = 0; noiseFloor < sbrGrid->numNoiseFloors; noiseFloor++)\n"
        "\t\tfor (band = 0; band < sbrFreq->numNoiseFloorBands; band++) {}\n"
        "\tsbrGrid->numNoiseFloorsPrev = sbrGrid->numNoiseFloors;\n"
        "}\n"),
    "src/libhelix-aac/sbrfreq.c": (
        "static int CalcFreqNoise(unsigned char *freqNoise, unsigned char *freqLow, "
        "int nLow, int kStart, int k2, int noiseBands) {\n"
        "\tASSERT(nQ <= MAX_NUM_NOISE_FLOOR_BANDS);\n"
        "\treturn nQ;\n"
        "}\n"
        "int CalcFreqTables(SBRHeader *sbrHdr, SBRFreq *sbrFreq, int sampRateIdx) {\n"
        "    sbrFreq->numNoiseFloorBands = CalcFreqNoise(sbrFreq->freqNoise, sbrFreq->freqLow, "
        "sbrFreq->nLow, sbrFreq->kStart, k2, sbrHdr->noiseBands);\n"
        "    return 0;\n"
        "}\n"),
    "src/libhelix-aac/sbr.c": (
        "            CalcFreqTables(&(psi->sbrHdr[chBase + 0]), &(psi->sbrFreq[chBase]), "
        "psi->sampRateIdx);\n"),
    "src/libmad/layer3.c": "void fastsdct(mad_fixed_t const x[9], mad_fixed_t y[18])\n{}\n",
}
SAM = {"src/render.c": "static void yield() {}\nvoid run() { yield(); }\n"}


def write_library(root, name, files):
    for relative, text in files.items():
        path = root / name / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)


class FakeNode:
    def __init__(self, path): self.path = str(path)
    def srcnode(self): return self
    def get_abspath(self): return self.path


class FakeEnv:
    def __init__(self, libdeps, name):
        self.libdeps, self.name, self.middlewares = str(libdeps), name, []
    def subst(self, value):
        assert value == "$PROJECT_LIBDEPS_DIR"
        return self.libdeps
    def get(self, key): return self.name if key == "PIOENV" else None
    def AddBuildMiddleware(self, callback, pattern=None): self.middlewares.append(callback)
    def Exit(self, code): raise SystemExit(code)


def run_build_script(env):
    with redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
        return runpy.run_path(str(SCRIPT), init_globals={"env": env, "Import": lambda *_: None})


class LibraryPatchHookTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.libdeps = Path(self.tmp.name) / "libdeps"
        self.root = self.libdeps / "board"
        self.root.mkdir(parents=True)
        # Never let a standalone run of the script reach the checkout's .pio.
        self.old_cwd = os.getcwd()
        os.chdir(self.tmp.name)

    def tearDown(self):
        os.chdir(self.old_cwd)
        self.tmp.cleanup()

    def audio_text(self):
        return {name: (self.root / "ESP8266Audio" / name).read_text() for name in AUDIO}

    def test_library_installed_after_pre_script_is_patched_before_compiling(self):
        write_library(self.root, "ESP8266SAM", SAM)
        env = FakeEnv(self.libdeps, "board")
        run_build_script(env)
        self.assertIn("sam_yield();", (self.root / "ESP8266SAM/src/render.c").read_text())
        self.assertEqual(len(env.middlewares), 1)
        hook = env.middlewares[0]

        # Late install by the Library Dependency Finder, after the pre-script.
        write_library(self.root, "ESP8266Audio", AUDIO)
        framework = FakeNode(Path(self.tmp.name) / "framework/cores/main.cpp")
        with redirect_stdout(io.StringIO()):
            self.assertIs(hook(framework), framework)
        self.assertEqual(self.audio_text(), {k: v for k, v in AUDIO.items()})

        library_source = FakeNode(self.root / "ESP8266Audio/src/AudioOutput.cpp")
        with redirect_stdout(io.StringIO()):
            self.assertIs(hook(library_source), library_source)
        patched = self.audio_text()
        sbrhuff = patched["src/libhelix-aac/sbrhuff.c"]
        self.assertIn("Brucia: bound SBR noise state before array access.", sbrhuff)
        self.assertIn("Brucia: cached validated noise bounds", sbrhuff)
        self.assertIn("noiseFloor < noiseFloors", sbrhuff)
        self.assertIn("Brucia: invalid frequency state", patched["src/libhelix-aac/sbrfreq.c"])
        self.assertIn("if (nQ > MAX_NUM_NOISE_FLOOR_BANDS) return 0;", patched["src/libhelix-aac/sbrfreq.c"])
        self.assertIn("Brucia: propagate invalid noise table", patched["src/libhelix-aac/sbrfreq.c"])
        self.assertIn("Brucia: require a fresh valid header", patched["src/libhelix-aac/sbr.c"])
        self.assertIn("y[17]", patched["src/libmad/layer3.c"])

        # The hook runs once per build; later sources pass through unchanged.
        (self.root / "ESP8266Audio/src/libmad/layer3.c").write_text("changed upstream\n")
        with redirect_stdout(io.StringIO()):
            hook(library_source)
        self.assertEqual((self.root / "ESP8266Audio/src/libmad/layer3.c").read_text(), "changed upstream\n")

    def test_patches_are_idempotent_including_group_reference_entries(self):
        write_library(self.root, "ESP8266Audio", AUDIO)
        run_build_script(FakeEnv(self.libdeps, "board"))
        first = self.audio_text()
        self.assertNotEqual(first, AUDIO)
        env = FakeEnv(self.libdeps, "board")
        run_build_script(env)
        with redirect_stdout(io.StringIO()):
            env.middlewares[0](FakeNode(self.root / "ESP8266Audio/src/x.cpp"))
        self.assertEqual(self.audio_text(), first)
        self.assertEqual(first["src/libhelix-aac/sbrhuff.c"].count("Brucia: bound SBR noise state"), 1)

    def test_changed_upstream_source_fails_the_build(self):
        files = dict(AUDIO)
        files["src/libhelix-aac/sbr.c"] = "if (CalcFreqTablesRenamed(psi) != 0) return -1;\n"
        write_library(self.root, "ESP8266Audio", files)
        with self.assertRaises(SystemExit) as raised:
            run_build_script(FakeEnv(self.libdeps, "board"))
        self.assertEqual(raised.exception.code, 1)

    def test_changed_late_installed_source_fails_in_the_hook(self):
        env = FakeEnv(self.libdeps, "board")
        run_build_script(env)
        write_library(self.root, "ESP8266SAM", {"src/render.c": "static void renamed() {}\n"})
        with self.assertRaises(SystemExit), redirect_stdout(io.StringIO()), \
                redirect_stderr(io.StringIO()):
            env.middlewares[0](FakeNode(self.root / "ESP8266SAM/src/render.c"))

    def test_missing_file_in_installed_library_fails(self):
        files = dict(AUDIO)
        del files["src/libmad/layer3.c"]
        write_library(self.root, "ESP8266Audio", files)
        with self.assertRaises(SystemExit):
            run_build_script(FakeEnv(self.libdeps, "board"))

    def test_libraries_not_installed_are_skipped_and_other_boards_untouched(self):
        other = self.libdeps / "other"
        write_library(other, "ESP8266Audio", AUDIO)
        run_build_script(FakeEnv(self.libdeps, "board"))
        self.assertEqual((other / "ESP8266Audio/src/libmad/layer3.c").read_text(),
                         AUDIO["src/libmad/layer3.c"])

    def test_group_reference_replacement_requires_marker(self):
        with redirect_stdout(io.StringIO()):
            module = runpy.run_path(str(SCRIPT), run_name="patch_library_conflicts")
        self.assertTrue(all(len(item) in (3, 4) for item in module["conflicts"]))
        module["conflicts"].append((".pio/libdeps/*/X/a.c", r"(a)", r"\1b"))
        try:
            with self.assertRaises(module["PatchError"]):
                module["patch_entries"]()
        finally:
            module["conflicts"].pop()


if __name__ == "__main__":
    unittest.main()
