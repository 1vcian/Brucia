import datetime
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import unittest.mock

import firmware_provenance as fp

HAS_GIT = shutil.which("git") is not None


def git(root, *args):
    env = dict(os.environ, GIT_AUTHOR_DATE="1700000000 +0000", GIT_COMMITTER_DATE="1700000000 +0000",
               GIT_CONFIG_GLOBAL=os.devnull, GIT_CONFIG_NOSYSTEM="1")
    subprocess.run(["git", "-C", str(root), "-c", "user.name=t", "-c", "user.email=t@example.invalid",
                    "-c", "commit.gpgsign=false", *args], check=True, capture_output=True, env=env)


@unittest.skipUnless(HAS_GIT, "git is not installed")
class GitPresentTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name).resolve() / "src"
        self.root.mkdir()
        (self.root / "VERSION").write_text("0.1.3\n")
        git(self.root, "init", "-q")
        git(self.root, "add", "VERSION")
        git(self.root, "commit", "-q", "-m", "init")

    def tearDown(self):
        self.tmp.cleanup()

    def test_clean_checkout_reports_commit_and_commit_time(self):
        info = fp.collect(str(self.root), fp.read_version(str(self.root)), "board-env", environ={})
        self.assertRegex(info["commit"], r"^[0-9a-f]{12}$")
        self.assertIs(info["dirty"], False)
        self.assertEqual(("2023-11-14T22:13:20Z", "commit"), (info["time"], info["time_src"]))
        self.assertEqual(f"0.1.3+board-env.{info['commit']}", info["id"])
        self.assertEqual("board-env", info["env"])

    def test_modified_tree_is_dirty_and_marked_in_id(self):
        (self.root / "VERSION").write_text("0.1.4\n")
        info = fp.collect(str(self.root), "0.1.4", "board-env", environ={})
        self.assertIs(info["dirty"], True)
        self.assertTrue(info["id"].endswith(".dirty"))

    def test_source_date_epoch_overrides_commit_time(self):
        info = fp.collect(str(self.root), "0.1.3", "e", environ={"SOURCE_DATE_EPOCH": "0"})
        self.assertEqual(("1970-01-01T00:00:00Z", "source_date_epoch"), (info["time"], info["time_src"]))

    def test_same_commit_renders_identical_header(self):
        a = fp.render_header(fp.collect(str(self.root), "0.1.3", "e", environ={}))
        b = fp.render_header(fp.collect(str(self.root), "0.1.3", "e", environ={}))
        self.assertEqual(a, b)

    def test_tree_nested_in_another_checkout_does_not_borrow_its_commit(self):
        nested = self.root / "unpacked-tarball"
        nested.mkdir()
        (self.root / ".gitignore").write_text("unpacked-tarball/\n")
        commit, dirty, epoch = fp.git_info(str(nested))
        self.assertEqual((fp.UNKNOWN, None, None), (commit, dirty, epoch))
        self.assertIsNone(fp.head_commit_epoch(str(nested)))

    def test_year_comes_from_commit_time_not_wall_clock(self):
        epoch = fp.head_commit_epoch(str(self.root))
        self.assertEqual(1700000000, epoch)
        later = datetime.datetime(2031, 1, 1, tzinfo=datetime.timezone.utc)
        self.assertEqual((2025, "commit"), fp.build_year({}, epoch, now=later))  # 2023 clamped
        env = dict(os.environ, GIT_AUTHOR_DATE="1800000000 +0000", GIT_COMMITTER_DATE="1800000000 +0000",
                   GIT_CONFIG_GLOBAL=os.devnull, GIT_CONFIG_NOSYSTEM="1")
        subprocess.run(["git", "-C", str(self.root), "-c", "user.name=t", "-c", "user.email=t@example.invalid",
                        "-c", "commit.gpgsign=false", "commit", "-q", "--allow-empty", "-m", "next"],
                       check=True, capture_output=True, env=env)
        self.assertEqual((2027, "commit"), fp.build_year({}, fp.head_commit_epoch(str(self.root)), now=later))

    def test_source_date_epoch_overrides_commit_year(self):
        year = fp.build_year({"SOURCE_DATE_EPOCH": "1800000000"}, fp.head_commit_epoch(str(self.root)))
        self.assertEqual((2027, "source_date_epoch"), year)


class GitAbsentTest(unittest.TestCase):
    def test_tarball_without_git_metadata_falls_back_to_unknown(self):
        with tempfile.TemporaryDirectory() as tmp:
            info = fp.collect(tmp, fp.read_version(tmp), "board-env", environ={})
        self.assertEqual(fp.UNKNOWN, info["commit"])
        self.assertIsNone(info["dirty"])
        self.assertEqual((fp.UNKNOWN, fp.UNKNOWN), (info["time"], info["time_src"]))
        self.assertEqual("dev+board-env.unknown", info["id"])
        header = fp.render_header(info)
        self.assertIn('#define BRUCIA_BUILD_COMMIT "unknown"', header)
        self.assertIn("#define BRUCIA_BUILD_DIRTY -1", header)

    def test_missing_git_binary_is_not_a_build_failure(self):
        def runner(*_args, **_kwargs):
            raise FileNotFoundError("git")
        self.assertEqual((fp.UNKNOWN, None, None), fp.git_info("/nonexistent", runner))

    def test_failing_or_malformed_git_output_is_unknown(self):
        def runner(args, **_kwargs):
            if "--show-toplevel" in args:
                return subprocess.CompletedProcess(args, 0, "/repo\n")
            return subprocess.CompletedProcess(args, 0, "not-a-sha\n")
        with unittest.mock.patch("firmware_provenance.os.path.realpath", side_effect=lambda p: "/repo"):
            self.assertEqual((fp.UNKNOWN, None, None), fp.git_info("/repo", runner))

    def test_source_date_epoch_still_applies_without_git(self):
        with tempfile.TemporaryDirectory() as tmp:
            info = fp.collect(tmp, "0.1.3", "e", environ={"SOURCE_DATE_EPOCH": "1700000000"})
        self.assertEqual(("2023-11-14T22:13:20Z", "source_date_epoch"), (info["time"], info["time_src"]))

    def test_build_id_override_and_values_are_sanitized_and_bounded(self):
        with tempfile.TemporaryDirectory() as tmp:
            info = fp.collect(tmp, '1"x\\y', 'env"; evil', environ={"BRUCIA_BUILD_ID": "ci 42/\"run\"" + "z" * 100})
        header = fp.render_header(info)
        self.assertNotIn("\\", header)
        for line in header.splitlines():
            if line.startswith("#define") and '"' in line:
                self.assertEqual(2, line.count('"'), line)
        for key in ("id", "env"):
            self.assertLessEqual(len(info[key]), fp.MAX_FIELD)
            self.assertNotRegex(info[key], r'["\\ ;/]')
        self.assertTrue(info["id"].startswith("ci-42--run-"))

    def test_year_without_git_or_sde_is_the_only_wall_clock_case(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertIsNone(fp.head_commit_epoch(tmp))
        now = datetime.datetime(2030, 6, 1, tzinfo=datetime.timezone.utc)
        self.assertEqual((2030, "wall_clock"), fp.build_year({}, None, now=now))
        self.assertEqual((2030, "wall_clock"), fp.build_year({"SOURCE_DATE_EPOCH": "soon"}, None, now=now))

    def test_year_from_source_date_epoch_without_git_and_floor(self):
        self.assertEqual((2027, "source_date_epoch"), fp.build_year({"SOURCE_DATE_EPOCH": " 1800000000 "}, None))
        # Pre-floor epochs (0, 1980) would break the firmware GPS-date window; clamp keeps it usable.
        self.assertEqual((fp.MIN_YEAR, "source_date_epoch"), fp.build_year({"SOURCE_DATE_EPOCH": "0"}, None))
        self.assertEqual((fp.MIN_YEAR, "source_date_epoch"), fp.build_year({"SOURCE_DATE_EPOCH": "315532800"}, None))

    def test_missing_git_binary_gives_no_commit_epoch(self):
        def runner(*_args, **_kwargs):
            raise FileNotFoundError("git")
        self.assertIsNone(fp.head_commit_epoch("/nonexistent", runner))

    def test_year_header_format_matches_bruce_header(self):
        self.assertEqual("#pragma once\n#define CURRENT_YEAR 2027\n", fp.render_year_header(2027))

    def test_header_is_written_only_when_changed(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "include", fp.HEADER_NAME)
            self.assertTrue(fp.write_if_changed(path, "a\n"))
            self.assertFalse(fp.write_if_changed(path, "a\n"))
            self.assertTrue(fp.write_if_changed(path, "b\n"))


class ReproducibleCompilerInputsTest(unittest.TestCase):
    def test_commit_time_is_exported_only_when_no_valid_source_date_epoch(self):
        self.assertEqual("1700000000", fp.source_date_epoch_export({}, 1700000000))
        self.assertIsNone(fp.source_date_epoch_export({"SOURCE_DATE_EPOCH": "0"}, 1700000000))
        self.assertIsNone(fp.source_date_epoch_export({"SOURCE_DATE_EPOCH": " 1800000000 "}, 1700000000))
        # Blank/malformed values are ignored by GCC and by build_time; use the commit time for both.
        self.assertEqual("1700000000", fp.source_date_epoch_export({"SOURCE_DATE_EPOCH": ""}, 1700000000))
        self.assertEqual("1700000000", fp.source_date_epoch_export({"SOURCE_DATE_EPOCH": "soon"}, 1700000000))

    def test_without_git_nothing_is_exported(self):
        self.assertIsNone(fp.source_date_epoch_export({}, None))
        with tempfile.TemporaryDirectory() as tmp:
            self.assertIsNone(fp.source_date_epoch_export({}, fp.head_commit_epoch(tmp)))

    def test_exported_epoch_keeps_provenance_time_source_and_value(self):
        exported = fp.source_date_epoch_export({}, 1700000000)
        before = fp.build_time({}, 1700000000)
        after = fp.build_time({"SOURCE_DATE_EPOCH": exported}, 1700000000)
        self.assertEqual(("2023-11-14T22:13:20Z", "commit"), before)
        self.assertEqual(before[0], after[0])  # same instant whichever source GCC/provenance read

    def test_prefix_map_flags_order_dedupe_and_symlinks(self):
        with tempfile.TemporaryDirectory() as tmp:
            real = Path(tmp).resolve()
            project = real / "checkout"
            (project / ".pio" / "libdeps").mkdir(parents=True)
            link = real / "link"
            link.symlink_to(project)
            flags = fp.prefix_map_flags([
                (str(real / "core"), "/pio/core"),
                (str(real / "core" / "packages"), "/pio/packages"),
                (str(link) + os.sep, "."),
                (str(project / ".pio" / "libdeps"), "./.pio/libdeps"),
                ("", "/ignored"),
                (str(real / "core"), "/duplicate"),
            ])
        self.assertTrue(all(f.startswith("-ffile-prefix-map=") for f in flags))
        olds = [f[len("-ffile-prefix-map="):].rsplit("=", 1)[0] for f in flags]
        self.assertEqual(sorted(olds, key=len), olds)  # nested dirs last: GCC uses the last match
        self.assertIn(f"-ffile-prefix-map={link}=.", flags)
        self.assertIn(f"-ffile-prefix-map={project}=.", flags)
        self.assertIn(f"-ffile-prefix-map={real / 'core'}=/pio/core", flags)
        self.assertNotIn("/duplicate", " ".join(flags))
        self.assertNotIn("/ignored", " ".join(flags))
        self.assertFalse(any(old.endswith(os.sep) for old in olds))

    def test_prefix_map_skips_paths_containing_equals(self):
        self.assertEqual([], fp.prefix_map_flags([("/tmp/a=b", ".")]))

    def test_compiler_probe(self):
        calls = []

        def ok(args, **kwargs):
            calls.append(args)
            return subprocess.CompletedProcess(args, 0, "", "")

        def rejected(args, **_kwargs):
            return subprocess.CompletedProcess(args, 1, "", "unrecognized option")

        def missing(*_args, **_kwargs):
            raise FileNotFoundError("cc")

        self.assertTrue(fp.compiler_accepts("cc", ["-ffile-prefix-map=/a=."], ok))
        self.assertEqual(["cc", "-ffile-prefix-map=/a=.", "-E", "-x", "c", os.devnull], calls[0])
        self.assertFalse(fp.compiler_accepts("cc", ["-x"], rejected))
        self.assertFalse(fp.compiler_accepts("cc", ["-x"], missing))
        self.assertFalse(fp.compiler_accepts("", ["-x"], ok))

    @unittest.skipUnless(shutil.which("cc"), "no host C compiler")
    def test_host_compiler_applies_prefix_map_and_source_date_epoch(self):
        with tempfile.TemporaryDirectory() as tmp:
            src = Path(tmp).resolve() / "proj" / "t.c"
            src.parent.mkdir()
            src.write_text("const char *f = __FILE__; const char *d = __DATE__ \" \" __TIME__;\n")
            flags = fp.prefix_map_flags([(str(src.parent), ".")])
            if not fp.compiler_accepts("cc", flags):
                self.skipTest("host compiler lacks -ffile-prefix-map")
            env = dict(os.environ, SOURCE_DATE_EPOCH=fp.source_date_epoch_export({}, 1700000000))
            out = subprocess.run(["cc", *flags, "-E", "-P", str(src)], capture_output=True, text=True,
                                 check=True, env=env).stdout
        self.assertRegex(out, r'f = "(\./)?t\.c"')  # GCC keeps "./", clang drops it
        self.assertNotIn(tmp, out)
        self.assertIn('"Nov 14 2023" " " "22:13:20"', out)


class _FakeSconsEnv(dict):
    """Minimal stand-in for the PlatformIO SCons env used by pre_build_provenance.py."""

    def __init__(self, root, environ):
        super().__init__(PROJECT_DIR=str(root), PIOENV="board-env", ENV=environ, CC="fake-gcc",
                         PROJECT_CORE_DIR=str(root / "core"), PROJECT_PACKAGES_DIR=str(root / "core" / "packages"),
                         PROJECT_LIBDEPS_DIR=str(root / ".pio" / "libdeps"))
        self.appended = {}

    def subst(self, text):
        return self[text[1:]] if text.startswith("$") else text

    def Append(self, **kw):
        for key, values in kw.items():
            self.appended.setdefault(key, []).extend(values)

    def WhereIs(self, _name):
        return None


@unittest.skipUnless(HAS_GIT, "git is not installed")
class PreBuildScriptTest(unittest.TestCase):
    SCRIPT = Path(__file__).resolve().parent.parent / "pre_build_provenance.py"

    def run_script(self, root, environ):
        env = _FakeSconsEnv(root, environ)
        code = compile(self.SCRIPT.read_text(), str(self.SCRIPT), "exec")
        with unittest.mock.patch("builtins.print"):
            exec(code, {"Import": lambda _name: None, "env": env, "__name__": "SCons"})
        return env

    def make_repo(self, tmp):
        root = Path(tmp).resolve() / "checkout"
        root.mkdir()
        (root / "VERSION").write_text("0.1.3\n")
        git(root, "init", "-q")
        git(root, "add", "VERSION")
        git(root, "commit", "-q", "-m", "init")
        return root

    def test_exports_commit_time_after_provenance_and_registers_prefix_map(self):
        with tempfile.TemporaryDirectory() as tmp, unittest.mock.patch.dict(os.environ, clear=False):
            os.environ.pop("SOURCE_DATE_EPOCH", None)
            root = self.make_repo(tmp)
            scons_env = {"PATH": os.environ.get("PATH", "")}
            env = self.run_script(root, scons_env)
            header = (root / "include" / fp.HEADER_NAME).read_text()
            self.assertEqual("1700000000", os.environ["SOURCE_DATE_EPOCH"])
            self.assertEqual("1700000000", scons_env["SOURCE_DATE_EPOCH"])
            self.assertIn('#define BRUCIA_BUILD_TIME_SOURCE "commit"', header)  # meaning unchanged
            self.assertIn('#define BRUCIA_BUILD_TIME "2023-11-14T22:13:20Z"', header)
            for key in ("CCFLAGS", "ASPPFLAGS", "LINKFLAGS"):
                self.assertEqual(["$BRUCIA_PREFIX_MAP"], env.appended[key])
            with unittest.mock.patch("firmware_provenance.compiler_accepts", return_value=True) as probe, \
                    unittest.mock.patch("builtins.print"):
                flags = env["BRUCIA_PREFIX_MAP"](target=None, source=None, env=env, for_signature=False)
                env["BRUCIA_PREFIX_MAP"](target=None, source=None, env=env, for_signature=True)
            self.assertEqual(1, probe.call_count)  # probed once per compiler
            self.assertEqual(("fake-gcc", flags), probe.call_args.args)
            self.assertIn(f"-ffile-prefix-map={root}=.", flags)
            self.assertIn(f"-ffile-prefix-map={root / '.pio' / 'libdeps'}=./.pio/libdeps", flags)

    def test_unsupported_compiler_gets_no_prefix_map(self):
        with tempfile.TemporaryDirectory() as tmp, unittest.mock.patch.dict(os.environ, {"SOURCE_DATE_EPOCH": "5"}):
            root = self.make_repo(tmp)
            env = self.run_script(root, {"SOURCE_DATE_EPOCH": "5"})
            self.assertEqual("5", os.environ["SOURCE_DATE_EPOCH"])  # explicit value untouched
            self.assertEqual("5", env["ENV"]["SOURCE_DATE_EPOCH"])
            with unittest.mock.patch("firmware_provenance.compiler_accepts", return_value=False), \
                    unittest.mock.patch("builtins.print"):
                self.assertEqual([], env["BRUCIA_PREFIX_MAP"](target=None, source=None, env=env, for_signature=True))

    def test_tree_without_git_exports_nothing(self):
        with tempfile.TemporaryDirectory() as tmp, unittest.mock.patch.dict(os.environ, clear=False):
            os.environ.pop("SOURCE_DATE_EPOCH", None)
            root = Path(tmp).resolve()
            scons_env = {}
            self.run_script(root, scons_env)
            self.assertNotIn("SOURCE_DATE_EPOCH", os.environ)
            self.assertNotIn("SOURCE_DATE_EPOCH", scons_env)


if __name__ == "__main__":
    unittest.main()
