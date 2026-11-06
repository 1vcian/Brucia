#!/usr/bin/env python3
"""The app's file-browser `storage list|remove|mkdir <path>` lines reach Bruce's CLI as one path.

Bruce registers `storage` as a SimpleCLI composite command whose `list`, `remove` and `mkdir`
sub-commands take one positional `filepath` (src/core/serial_commands/storage_commands.cpp,
createStorageCommand). The app builds those lines in DeviceFileBrowser (cliPath / listCommand).
This test takes every `storage ...` string literal asserted by DeviceFileBrowserTest.kt and checks
that SimpleCLI parses each into exactly one CLI line whose filepath is the intended path, while the
old unquoted spelling of the same paths fails ("unknown argument") or splits into two commands.

SimpleCLI is pinned to rennancockles/SimpleCLI b23d3779 (platformio.ini). When a PlatformIO
libdeps copy is present (ROOT/.pio/libdeps, or the SIMPLECLI_SRC directory holding src/c), its
real C tokenizer is compiled and driven the way SimpleCLI::parse handles a composite; the Python
port below is cross-checked against it. Rules ported:
- parser.c parse_lines (l.196-231): a line ends at CR/LF or ";;" unless inside "..." (a quote
  preceded by a backslash does not toggle);
- parser.c parse_words, arg.c arg_set_value, cmd.c cmd_parse: as in test_ir_cli_tokenizer.py.
Run: python3 tools/test_storage_cli_paths.py
"""
import glob
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
from test_ir_cli_tokenizer import parse_words, arg_value, PARSE_SUCCESS, UNKNOWN_ARG, MISSING_ARG  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
APP_TEST = ROOT / "android/app/src/test/java/app/brucia/DeviceFileBrowserTest.kt"
CC = os.environ.get("CC", "cc")
SUBS = ("list", "remove", "mkdir")


def parse_lines(s):
    """Port of parser.c parse_lines: the text of each CLI line."""
    lines, j, ignore = [], 0, False
    i = 0
    while i <= len(s):
        c = s[i] if i < len(s) else "\0"
        prev = s[i - 1] if i > 0 else "\0"
        if c == '"' and (prev != "\\" or i == 0):
            ignore = not ignore
        nxt = s[i + 1] if i + 1 < len(s) else "\0"
        delimiter = c == ";" and nxt == ";" and not ignore and (i == 0 or prev != "\\")
        linebreak = c in "\r\n" and not ignore
        if linebreak or i == len(s) or delimiter:
            if i - j > 0:
                lines.append(s[j:i])
            if delimiter:
                i += 1
            j = i + 1
        i += 1
    return lines


def port_parse(text):
    """[(mode, sub, filepath)] per CLI line of `text` against the storage composite."""
    out = []
    for line in parse_lines(text):
        words = parse_words(line)
        if len(words) < 2 or words[0] != "storage" or words[1] not in SUBS:
            out.append((1, None, None))
            continue
        vals = []
        mode = PARSE_SUCCESS
        for w in words[2:]:
            if w.startswith("-") or vals:
                mode = UNKNOWN_ARG
                break
            v = arg_value(w)
            vals.append("" if v is None else v)
        if mode == PARSE_SUCCESS and not vals and words[1] != "list":
            mode = MISSING_ARG
        out.append((mode, words[1], vals[0] if vals else "") if mode == PARSE_SUCCESS else (mode, None, None))
    return out


def app_storage_literals():
    text = APP_TEST.read_text()
    out = []
    for lit in re.findall(r'"((?:[^"\\]|\\.)*)"', text):
        lit = lit.replace('\\"', '"').replace("\\\\", "\\")
        if lit.startswith("storage ") and lit.split(" ")[1] in SUBS and '"' in lit:
            out.append(lit)
    return out


DRIVER = r'''
#include <stdio.h>
#include <string.h>
#include "c/arg.h"
#include "c/cmd.h"
#include "c/cmd_error.h"
#include "c/parser.h"
/* Mirrors SimpleCLI::parse (SimpleCLI.cpp:62-150) for the composite `storage` and its subs. */
int main(int argc, char **argv) {
    const char *subs[] = {"list", "remove", "mkdir"};
    for (int k = 1; k < argc; k++) {
        line_list *l = parse_lines(argv[k], strlen(argv[k]));
        for (line_node *n = l->first; n; n = n->next) {
            cmd *st = cmd_create_boundless("storage");
            st->composite = 1;
            cmd *head = NULL, *tail = NULL;
            for (int s = 0; s < 3; s++) {
                cmd *c = cmd_create_default(subs[s]);
                cmd_add_arg(c, s == 0 ? arg_create_opt_positional("filepath", "") : arg_create_req_positional("filepath"));
                if (tail) tail->next = c; else head = c;
                tail = c;
            }
            st->cmd_list = head;
            cmd_error *e = cmd_parse(st, n);
            int mode = e->mode;
            const char *sub = "", *val = "";
            if (mode == 0) {
                line_list *sl = parse_lines(n->words->first->next->str, n->len - n->words->first->len - 1);
                mode = 1;
                for (cmd *c = head; c; c = c->next) {
                    cmd_error *se = cmd_parse(c, sl->first);
                    if (se->mode == 0) { mode = 0; sub = c->name; val = arg_get_value(c->arg_list); break; }
                    if (se->mode > 1) { mode = se->mode; break; }
                }
            }
            printf("%d\t%s\t%s\n", mode, mode == 0 ? sub : "", mode == 0 ? (val ? val : "") : "");
        }
        printf("--\n");
    }
    return 0;
}
'''


def simplecli_src():
    env = os.environ.get("SIMPLECLI_SRC")
    if env and (Path(env) / "c/parser.c").exists():
        return Path(env)
    srcs = sorted(glob.glob(str(ROOT / ".pio/libdeps/*/SimpleCLI/src/c/parser.c")))
    return Path(srcs[0]).parents[1] if srcs else None


def real_parse(texts):
    src = simplecli_src()
    if src is None:
        return None
    with tempfile.TemporaryDirectory(prefix="brucia-simplecli-") as tmp:
        drv, exe = Path(tmp) / "drv.c", Path(tmp) / "drv"
        drv.write_text(DRIVER)
        files = [str(src / "c" / f) for f in ("parser.c", "arg.c", "cmd.c", "cmd_error.c", "comparator.c")]
        subprocess.run([CC, "-std=c99", "-w", "-I", str(src), str(drv), *files, "-o", str(exe)], check=True)
        out = subprocess.run([str(exe), *texts], check=True, capture_output=True, text=True).stdout
    res, cur = [], []
    for row in out.split("\n"):
        if row == "--":
            res.append(cur)
            cur = []
        elif row:
            mode, sub, val = row.split("\t")
            cur.append((0, sub, val) if int(mode) == 0 else (int(mode), None, None))
    return res


def intended(lit):
    """The path the app meant: the text between the first quote and the last one."""
    return lit[lit.index('"') + 1:lit.rindex('"')]


class StorageCliPathTest(unittest.TestCase):
    def test_app_literals_parse_to_one_line_with_the_whole_path(self):
        lits = app_storage_literals()
        self.assertGreaterEqual(len(lits), 4, lits)
        for lit in lits:
            self.assertEqual([(PARSE_SUCCESS, lit.split(" ")[1], intended(lit))], port_parse(lit), lit)

    def test_unquoted_spellings_failed_or_split(self):
        self.assertEqual([(UNKNOWN_ARG, None, None)], port_parse("storage list /My Files"))
        self.assertEqual([(UNKNOWN_ARG, None, None)], port_parse("storage remove /My Files/a b.txt"))
        # ";;" ran a second command: the remove of /keep.
        self.assertEqual([(PARSE_SUCCESS, "mkdir", "/x"), (PARSE_SUCCESS, "remove", "/keep")],
                         port_parse("storage mkdir /x;;storage remove /keep"))

    def test_port_matches_the_real_simplecli_sources(self):
        texts = app_storage_literals() + ["storage list /My Files", "storage remove /My Files/a b.txt",
                                          "storage mkdir /x;;storage remove /keep", "storage list /plain",
                                          "storage list", 'storage list "/a;;b" extra', "storage remove"]
        real = real_parse(texts)
        if real is None:
            self.skipTest("no SimpleCLI sources (.pio/libdeps or SIMPLECLI_SRC)")
        self.assertEqual([port_parse(t) for t in texts], real)


if __name__ == "__main__":
    unittest.main(verbosity=2)
