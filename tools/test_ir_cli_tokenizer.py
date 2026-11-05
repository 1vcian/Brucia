#!/usr/bin/env python3
"""The app's `ir tx_raw` CLI lines parse into one `samples` value on Bruce's CLI.

Bruce registers `ir` as a SimpleCLI composite command whose `tx_raw` sub-command has two
positional arguments, `frequency` and `samples` (src/core/serial_commands/ir_commands.cpp,
createIrTxRawCommand). The app builds that line in IrButton.cliRaw / cliTxRaw (used on LITE
firmware for raw IR replay, and for saved raw buttons). This test takes the exact expected
strings from the app's unit tests (IrButtonTest.kt, CoverageTest.kt) and checks that:

- SimpleCLI parses them into frequency + one samples value (quotes stripped), and the old
  unquoted form fails with "unknown argument";
- Bruce's sendRawCommand split of that value (src/modules/ir/custom_ir.cpp: one buffer slot per
  space + 1, chunks split on ' ', toInt) yields exactly the durations.

SimpleCLI is pinned to rennancockles/SimpleCLI b23d3779 (platformio.ini, library 1.1.4). When a
PlatformIO libdeps copy is present, its real C tokenizer (src/c/parser.c, arg.c, cmd.c,
cmd_error.c, comparator.c) is compiled and driven the way SimpleCLI::parse handles a composite
(SimpleCLI.cpp:62-90: the boundless composite `ir` from addCompositeCmd l.285 accepts the line,
then the text after the first word is parsed against the sub-commands). Without libdeps (CI host job) a port of the same rules is used, cross-checked
against the real code whenever both are available:

- parser.c parse_words (l.155-189): split on ' ' unless inside "..." or after a backslash;
- arg.c arg_set_value (l.169-205): drop unescaped '"' and the escaping backslash; an unclosed
  quote fails;
- cmd.c cmd_parse (l.261-317): each non '-' word fills the next unset positional argument; a word
  with no unset positional left is CMD_UNKOWN_ARG; a missing required positional is
  CMD_MISSING_ARG.
Run: python3 tools/test_ir_cli_tokenizer.py
"""
import glob
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
APP_TESTS = ROOT / "android/app/src/test/java/app/brucia"
CXX = os.environ.get("CC", "cc")
PARSE_SUCCESS, UNKNOWN_ARG, MISSING_ARG, UNCLOSED_QUOTE = 0, 2, 3, 5


def app_tx_raw_literals():
    """Every expected `ir tx_raw ...` string literal asserted by the app's unit tests."""
    out = []
    for name in ("IrButtonTest.kt", "CoverageTest.kt"):
        text = (APP_TESTS / name).read_text()
        for lit in re.findall(r'assertEquals\(\s*"((?:[^"\\]|\\.)*)"', text):
            lit = lit.replace('\\"', '"').replace("\\\\", "\\")
            if lit.startswith("ir tx_raw "):
                out.append(lit)
    return out


def parse_words(s):
    """Port of parser.c parse_words."""
    words, j, escaped, ignore_space = [], 0, False, False
    for i in range(len(s) + 1):
        c = s[i] if i < len(s) else "\0"
        if c == "\\" and not escaped:
            escaped = True
        elif c == '"' and not escaped:
            ignore_space = not ignore_space
        elif i == len(s) or (c == " " and not ignore_space and not escaped):
            if i - j > 0:
                words.append(s[j:i])
            j = i + 1
        elif escaped:
            escaped = False
    return words


def arg_value(w):
    """Port of arg.c arg_set_value; None for an unclosed quote."""
    out, escaped, in_quote = [], False, False
    for c in w:
        if c == "\\" and not escaped:
            escaped = True
        elif c == '"' and not escaped:
            in_quote = not in_quote
        else:
            out.append(c)
            escaped = False
    return None if in_quote else "".join(out)


def port_parse(line):
    """(mode, frequency, samples) for `ir <sub> ...` against tx_raw(frequency, samples)."""
    words = parse_words(line)
    if len(words) < 2 or words[0] != "ir" or words[1] != "tx_raw":
        return (1, None, None)
    vals = []
    for w in words[2:]:
        if w.startswith("-") or len(vals) == 2:
            return (UNKNOWN_ARG, None, None)
        v = arg_value(w)
        if v is None:
            # cmd.c sets positionals without checking the result; the value stays NULL.
            v = ""
        vals.append(v)
    if len(vals) < 2:
        return (MISSING_ARG, None, None)
    return (PARSE_SUCCESS, vals[0], vals[1])


def send_raw_durations(samples):
    """Port of custom_ir.cpp sendRawCommand's buffer sizing and split."""
    size = 1 + samples.count(" ")
    out = []
    while samples and len(out) < size:
        k = samples.find(" ")
        if k == -1:
            k = len(samples)
        chunk, samples = samples[:k], samples[k + 1:]
        m = re.match(r"\s*[-+]?\d+", chunk)  # Arduino String::toInt (atol)
        out.append(int(m.group()) if m else 0)
    return out


DRIVER = r'''
#include <stdio.h>
#include <string.h>
#include "c/arg.h"
#include "c/cmd.h"
#include "c/cmd_error.h"
#include "c/parser.h"
/* Mirrors SimpleCLI::parse (SimpleCLI.cpp:62-90) for the composite `ir` -> `tx_raw`. */
int main(int argc, char **argv) {
    for (int k = 1; k < argc; k++) {
        cmd *ir = cmd_create_boundless("ir"); /* SimpleCLI::addCompositeCmd: boundless + composite */
        ir->composite = 1;
        cmd *tx = cmd_create_default("tx_raw");
        cmd_add_arg(tx, arg_create_req_positional("frequency"));
        cmd_add_arg(tx, arg_create_req_positional("samples"));
        ir->cmd_list = tx;
        line_list *l = parse_lines(argv[k], strlen(argv[k]));
        line_node *n = l->first;
        cmd_error *e = cmd_parse(ir, n);
        int mode = e->mode;
        if (mode == 0) {
            line_list *sl = parse_lines(n->words->first->next->str, n->len - n->words->first->len - 1);
            cmd_error *se = cmd_parse(tx, sl->first);
            mode = se->mode;
        }
        if (mode == 0) printf("%d\t%s\t%s\n", mode, arg_get_value(tx->arg_list), arg_get_value(tx->arg_list->next));
        else printf("%d\t\t\n", mode);
    }
    return 0;
}
'''


def real_parse(lines):
    srcs = sorted(glob.glob(str(ROOT / ".pio/libdeps/*/SimpleCLI/src/c/parser.c")))
    if not srcs:
        return None
    src = Path(srcs[0]).parents[1]
    with tempfile.TemporaryDirectory(prefix="brucia-simplecli-") as tmp:
        drv, exe = Path(tmp) / "drv.c", Path(tmp) / "drv"
        drv.write_text(DRIVER)
        files = [str(src / "c" / f) for f in ("parser.c", "arg.c", "cmd.c", "cmd_error.c", "comparator.c")]
        subprocess.run([CXX, "-std=c99", "-w", "-I", str(src), str(drv), *files, "-o", str(exe)], check=True)
        out = subprocess.run([str(exe), *lines], check=True, capture_output=True, text=True).stdout
    res = []
    for row in out.splitlines():
        mode, freq, samples = row.split("\t")
        res.append((0, freq, samples) if int(mode) == 0 else (int(mode), None, None))
    return res


class IrCliTokenizerTest(unittest.TestCase):
    def test_app_literals_parse_to_one_samples_value(self):
        lits = app_tx_raw_literals()
        self.assertGreaterEqual(len(lits), 4, lits)
        for lit in lits:
            mode, freq, samples = port_parse(lit)
            self.assertEqual(PARSE_SUCCESS, mode, lit)
            quoted = re.search(r'"([^"]*)"', lit).group(1)
            self.assertEqual(quoted, samples, lit)
            self.assertGreater(int(freq), 0, lit)
            self.assertEqual([int(x) for x in quoted.split(" ")], send_raw_durations(samples), lit)

    def test_unquoted_samples_are_rejected(self):
        self.assertEqual(UNKNOWN_ARG, port_parse("ir tx_raw 38000 9000 4500 560")[0])
        # A single unquoted duration parses, but only sends that one value.
        self.assertEqual((PARSE_SUCCESS, "38000", "9000"), port_parse("ir tx_raw 38000 9000"))
        self.assertEqual(MISSING_ARG, port_parse("ir tx_raw 38000")[0])

    def test_port_matches_the_real_simplecli_sources(self):
        lines = app_tx_raw_literals() + ["ir tx_raw 38000 9000 4500 560", "ir tx_raw 38000 9000",
                                         "ir tx_raw 38000", 'ir tx_raw 38000 "9000 4500"  ',
                                         'ir tx_raw 36000 "1 2 3" extra']
        real = real_parse(lines)
        if real is None:
            self.skipTest("no .pio/libdeps SimpleCLI copy (run a firmware build first)")
        self.assertEqual([port_parse(line) for line in lines], real)


if __name__ == "__main__":
    unittest.main(verbosity=2)
