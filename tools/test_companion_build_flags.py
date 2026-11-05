"""pre_build_companion_flags.py adds no-exception flags to src/companion/** only.

Host tests with a fake SCons environment: companion sources are rebuilt with the
flags appended after the inherited CCFLAGS; every other project source, library
source and look-alike path keeps its node untouched; the program emitter links the
companion objects after every other object (exception-enabled comdat copies win);
the catch guard is attached as a post action on firmware.elf, and without a toolchain
readelf nothing is changed at all.
"""
from contextlib import redirect_stdout
import io
import os
from pathlib import Path
import runpy
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "pre_build_companion_flags.py"
FLAGS = ["-fno-exceptions", "-fno-asynchronous-unwind-tables", "-fno-unwind-tables"]


class FakeNode:
    def __init__(self, path): self.path = str(path)
    def srcnode(self): return self
    def get_abspath(self): return self.path


class FakeEnv(dict):
    def __init__(self, src_dir, compiler=None):
        super().__init__(CCFLAGS=["-Os", "-fexceptions"], PROGEMITTER=None)
        self.src_dir, self.middlewares, self.objects = str(src_dir), [], []
        self.compiler, self.post_actions = compiler, []
    def subst(self, value):
        return {"$PROJECT_SRC_DIR": self.src_dir, "$PROJECT_DIR": str(ROOT),
                "$CC": "xtensa-esp32-elf-gcc"}[value]
    def WhereIs(self, name):
        return self.compiler
    def VerboseAction(self, action, text):
        self.action_text = text
        return action
    def AddPostAction(self, target, action):
        self.post_actions.append((target, action))
    def AddBuildMiddleware(self, callback, pattern=None):
        self.middlewares.append((callback, pattern))
    def Object(self, node, **overrides):
        self.objects.append((node.path, overrides))
        return [FakeNode(node.path + ".o")]


class CompanionBuildFlagsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.src = Path(self.tmp.name) / "src"
        for relative in ("companion/protocol.cpp", "companion/sub/x.cpp", "core/display.cpp",
                         "companion_extra/a.cpp", "main.cpp"):
            (self.src / relative).parent.mkdir(parents=True, exist_ok=True)
            (self.src / relative).write_text("")
        self.toolchain = Path(self.tmp.name) / "bin"
        self.toolchain.mkdir()
        readelf = self.toolchain / "xtensa-esp32-elf-readelf"
        readelf.write_text("#!/bin/sh\n")
        readelf.chmod(0o755)
        self.env = FakeEnv(self.src, str(self.toolchain / "xtensa-esp32-elf-gcc"))
        with redirect_stdout(io.StringIO()):
            self.module = runpy.run_path(str(SCRIPT), init_globals={"env": self.env,
                                                                    "Import": lambda *_: None})
        self.assertEqual(1, len(self.env.middlewares))
        self.callback, pattern = self.env.middlewares[0]
        self.assertEqual(1, len(self.env["PROGEMITTER"]))
        self.emitter = self.env["PROGEMITTER"][0]
        self.assertIsNone(pattern)
        self.assertEqual(2, self.callback.__code__.co_argcount)  # PlatformIO passes (env, node)
        self.assertEqual(["$BUILD_DIR/${PROGNAME}.elf"], [t for t, _ in self.env.post_actions])
        self.assertEqual("Checking try/catch handlers in $TARGET", self.env.action_text)

    def tearDown(self):
        self.tmp.cleanup()

    def test_companion_sources_get_flags_after_inherited_ccflags(self):
        for relative in ("companion/protocol.cpp", "companion/sub/x.cpp"):
            path = self.src / relative
            objects = self.callback(self.env, FakeNode(path))
            self.assertEqual([str(path) + ".o"], [obj.path for obj in objects])
        self.assertEqual([["-Os", "-fexceptions", *FLAGS]] * 2,
                         [overrides["CCFLAGS"] for _, overrides in self.env.objects])
        self.assertEqual(["-Os", "-fexceptions"], self.env["CCFLAGS"])  # shared env not mutated

    def test_other_sources_are_untouched(self):
        library = Path(self.tmp.name) / "libdeps" / "env" / "companion" / "lib.cpp"
        for path in (self.src / "core/display.cpp", self.src / "companion_extra/a.cpp",
                     self.src / "main.cpp", library, self.src / "companion"):
            node = FakeNode(path)
            self.assertIs(node, self.callback(self.env, node))
        self.assertEqual([], self.env.objects)

    def test_companion_objects_are_linked_last_in_stable_order(self):
        companion = [self.callback(self.env, FakeNode(self.src / name))[0]
                     for name in ("companion/protocol.cpp", "companion/sub/x.cpp")]
        others = [FakeNode(self.src / name) for name in ("core/display.cpp.o", "main.cpp.o")]
        library = FakeNode(Path(self.tmp.name) / "lib226/NimBLE.cpp.o")
        target, source = self.emitter(["firmware.elf"], [library, companion[0], companion[1],
                                                         others[0], others[1]], self.env)
        self.assertEqual(["firmware.elf"], target)
        self.assertEqual([library, *others, *companion], source)

    def test_no_readelf_means_no_flags_and_no_reorder(self):
        env = FakeEnv(self.src, str(Path(self.tmp.name) / "missing" / "xtensa-esp32-elf-gcc"))
        out = io.StringIO()
        with redirect_stdout(out):
            runpy.run_path(str(SCRIPT), init_globals={"env": env, "Import": lambda *_: None})
            callback = env.middlewares[0][0]
            node = FakeNode(self.src / "companion/protocol.cpp")
            self.assertIs(node, callback(env, node))
            library = FakeNode(Path(self.tmp.name) / "lib.cpp.o")
            self.assertEqual(([], [library]), env["PROGEMITTER"][0]([], [library], env))
            self.assertEqual(0, env.post_actions[0][1]([FakeNode("firmware.elf")], [], env))
        self.assertEqual([], env.objects)
        self.assertIn("keep C++ exceptions", out.getvalue())
        self.assertIn("guard] skipped", out.getvalue())

    def test_guard_failure_fails_the_link_and_removes_the_elf(self):
        elf = Path(self.tmp.name) / "firmware.elf"
        elf.write_bytes(b"not an elf")
        action = self.env.post_actions[0][1]
        with redirect_stdout(io.StringIO()) as out:
            self.assertEqual(1, action([FakeNode(elf)], [], self.env))
        self.assertFalse(elf.exists())
        self.assertIn("guard could not run", out.getvalue())

    def test_existing_program_emitters_are_kept(self):
        def first(target, source, env): return target, source
        for existing in (first, [first]):
            env = FakeEnv(self.src, self.env.compiler)
            env["PROGEMITTER"] = existing
            with redirect_stdout(io.StringIO()):
                runpy.run_path(str(SCRIPT), init_globals={"env": env, "Import": lambda *_: None})
            self.assertEqual(2, len(env["PROGEMITTER"]))
            self.assertIs(first, env["PROGEMITTER"][0])

    def test_symlinked_project_dir_still_matches(self):
        link = Path(self.tmp.name) / "linked-src"
        os.symlink(self.src, link)
        self.assertTrue(self.module["is_companion_source"](str(link / "companion/protocol.cpp"),
                                                           str(self.src)))
        self.assertFalse(self.module["is_companion_source"](str(link / "core/display.cpp"),
                                                            str(self.src)))

    def test_flag_set_and_exclusions(self):
        self.assertEqual(tuple(FLAGS), self.module["NO_EXCEPTION_FLAGS"])
        self.assertEqual(frozenset(), self.module["KEEP_EXCEPTIONS"])
        source = str(self.src / "companion/sub/x.cpp")
        self.module["is_companion_source"].__globals__["KEEP_EXCEPTIONS"] = frozenset({"sub/x.cpp"})
        self.assertFalse(self.module["is_companion_source"](source, str(self.src)))

    def test_registered_in_platformio_extra_scripts(self):
        ini = (ROOT / "platformio.ini").read_text()
        # Last middleware: it returns object nodes, the library patch hook expects files.
        self.assertLess(ini.index("pre:patch_library_conflicts.py"),
                        ini.index("pre:pre_build_companion_flags.py"))


if __name__ == "__main__":
    unittest.main()
