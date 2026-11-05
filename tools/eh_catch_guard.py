#!/usr/bin/env python3
"""Fail a firmware link when a source `catch` lost its exception table entry.

src/companion/** is compiled without C++ exceptions (pre_build_companion_flags.py).
Under LTO the link keeps the first copy of every header-defined inline/template
function; if a no-exception companion copy won, a caller's try/catch could be
optimized away without any diagnostic. This guard checks the linked firmware.elf
against the source instead of a stored reference:

1. Every `try { ... } catch (...)` in the given C/C++ sources gives a try-body line
   range (comments and string literals ignored).
2. The DWARF line table (readelf --debug-dump=decodedline) maps those lines to code
   addresses. No address: the code is not in this build (other board, LITE, gc).
3. Every try-body address must lie in a function with an unwind entry (.eh_frame
   FDE), and every function that holds try-body code plus code from other lines of
   the same file (the enclosing function or an inlined copy of it; lambdas defined
   inside the try are their own functions) must have an LSDA (.gcc_except_table)
   with at least one catch action (type filter > 0).

The .eh_frame and .gcc_except_table bytes are parsed here (ESP-IDF folds .eh_frame
into .flash.rodata behind the __eh_frame symbol; plain ELFs keep the section).
Only readelf is needed. Exit status 0 ok, 1 a catch site lost its handler,
2 the guard could not run (missing tool or debug line info).
"""
import argparse
import bisect
import os
import re
import struct
import subprocess
import sys

SOURCE_EXTS = ('.c', '.cc', '.cpp', '.cxx', '.h', '.hh', '.hpp', '.hxx', '.ino', '.tpp', '.ipp')
CATCH_RE = re.compile(r'\bcatch\s*\(')


class GuardError(RuntimeError):
    """The guard cannot judge this ELF (missing data); never treat as a pass."""


# ---------------------------------------------------------------- source side

def strip_code(text):
    """Blank comments and string/char literals, keeping offsets and newlines."""
    out, i, n = list(text), 0, len(text)
    while i < n:
        c = text[i]
        if text.startswith('//', i):
            j = text.find('\n', i)
            j = n if j == -1 else j
            out[i:j] = ' ' * (j - i)
            i = j
        elif text.startswith('/*', i):
            j = text.find('*/', i + 2)
            j = n if j == -1 else j + 2
            out[i:j] = [ch if ch == '\n' else ' ' for ch in text[i:j]]
            i = j
        elif c in '"\'':
            m = re.match(r'R"([^(\s]*)\(', text[i:]) if c == '"' and i and text[i - 1] == 'R' else None
            if m:
                end = text.find(')' + m.group(1) + '"', i)
                j = n if end == -1 else end + len(m.group(1)) + 2
            else:
                j = i + 1
                while j < n and text[j] != c and text[j] != '\n':
                    j += 2 if text[j] == '\\' else 1
                j = min(j + 1, n)
            out[i + 1:j - 1] = [ch if ch == '\n' else ' ' for ch in text[i + 1:j - 1]]
            i = j
        else:
            i += 1
    return ''.join(out)


def _match_open(code, close):
    depth = 0
    for k in range(close, -1, -1):
        if code[k] == '}':
            depth += 1
        elif code[k] == '{':
            depth -= 1
            if depth == 0:
                return k
    raise ValueError('unbalanced braces')


def _prev_token_end(code, k):
    k -= 1
    while k >= 0 and code[k].isspace():
        k -= 1
    return k


NOT_FUNCTION = {'if', 'for', 'while', 'switch', 'catch', 'return', 'sizeof', 'decltype'}
QUALIFIERS = {'const', 'override', 'final', 'noexcept', 'mutable', 'volatile'}


def _brace_pairs(code):
    pairs, stack = [], []
    for k, ch in enumerate(code):
        if ch == '{':
            stack.append(k)
        elif ch == '}' and stack:
            pairs.append((stack.pop(), k))
    return pairs


def _is_function_body(code, open_brace):
    k = _prev_token_end(code, open_brace)
    while k >= 0 and (code[k].isalnum() or code[k] == '_'):
        j = k
        while j >= 0 and (code[j].isalnum() or code[j] == '_'):
            j -= 1
        if code[j + 1:k + 1] not in QUALIFIERS:
            return False
        k = _prev_token_end(code, j + 1)
    if k < 0 or code[k] != ')':
        return False
    depth = 0
    while k >= 0:
        depth += code[k] == ')'
        depth -= code[k] == '('
        if depth == 0:
            break
        k -= 1
    k = _prev_token_end(code, k)
    if k >= 0 and code[k] == ']':
        return True  # lambda
    j = k
    while j >= 0 and (code[j].isalnum() or code[j] == '_'):
        j -= 1
    return j < k and code[j + 1:k + 1] not in NOT_FUNCTION


def _enclosing_function(code, pairs, offset):
    for open_brace, close in sorted((p for p in pairs if p[0] < offset < p[1]), reverse=True):
        if _is_function_body(code, open_brace):
            return code.count('\n', 0, open_brace) + 1, code.count('\n', 0, close) + 1
    return 1, code.count('\n') + 1


def try_blocks(text):
    """[(try_line, body_end_line, function_first_line, function_last_line)] (1-based)
    for each try block that has at least one catch."""
    code = strip_code(text)
    pairs = _brace_pairs(code)
    blocks = set()
    for match in CATCH_RE.finditer(code):
        k = _prev_token_end(code, match.start())
        while k >= 0 and code[k] == '}':
            open_brace = _match_open(code, k)
            t = _prev_token_end(code, open_brace)
            if code[max(0, t - 2):t + 1] == 'try' and (t < 3 or not (code[t - 3].isalnum() or code[t - 3] == '_')):
                blocks.add((code.count('\n', 0, t - 2) + 1, code.count('\n', 0, k) + 1,
                            *_enclosing_function(code, pairs, t)))
                break
            if code[t] != ')':
                break
            depth, p = 0, t  # skip a previous handler "catch (...)" and its block
            while p >= 0:
                depth += code[p] == ')'
                depth -= code[p] == '('
                if depth == 0:
                    break
                p -= 1
            p = _prev_token_end(code, p)
            if code[max(0, p - 4):p + 1] != 'catch':
                break
            k = _prev_token_end(code, p - 4)
    return sorted(blocks)


def catch_sites(project_dir, roots=('src', 'lib')):
    """{relative path: [try_blocks() tuples]} for sources containing a catch."""
    sites = {}
    for root in roots:
        for dirpath, dirnames, filenames in os.walk(os.path.join(project_dir, root)):
            dirnames.sort()
            for name in sorted(filenames):
                if not name.endswith(SOURCE_EXTS):
                    continue
                path = os.path.join(dirpath, name)
                with open(path, encoding='utf-8', errors='replace') as handle:
                    text = handle.read()
                if 'catch' not in text:
                    continue
                blocks = try_blocks(text)
                if blocks:
                    sites[os.path.relpath(path, project_dir).replace(os.sep, '/')] = blocks
    return sites


# ------------------------------------------------------------------- ELF side

class Elf:
    def __init__(self, path):
        with open(path, 'rb') as handle:
            self.data = data = handle.read()
        if data[:4] != b'\x7fELF' or data[4] != 1 or data[5] != 1:
            raise GuardError(f'{path}: not a 32-bit little-endian ELF')
        shoff, = struct.unpack_from('<I', data, 0x20)
        shentsize, shnum, shstrndx = struct.unpack_from('<HHH', data, 0x2e)
        raw = [struct.unpack_from('<IIIIIIIIII', data, shoff + i * shentsize) for i in range(shnum)]
        names = raw[shstrndx]
        self.sections = []
        for name, typ, flags, addr, off, size, link, info, align, entsize in raw:
            end = data.index(b'\0', names[4] + name)
            self.sections.append((data[names[4] + name:end].decode(), typ, addr, off, size, link, entsize))

    def section(self, name):
        for section in self.sections:
            if section[0] == name:
                return section
        return None

    def symbol(self, wanted):
        for name, typ, addr, off, size, link, entsize in self.sections:
            if typ != 2:  # SHT_SYMTAB
                continue
            stroff = self.sections[link][3]
            for k in range(0, size, entsize or 16):
                st_name, value = struct.unpack_from('<II', self.data, off + k)
                end = self.data.index(b'\0', stroff + st_name)
                if self.data[stroff + st_name:end].decode(errors='replace') == wanted:
                    return value
        return None

    def view(self, addr):
        """Bytes from addr to the end of the loaded (non-NOBITS) section holding it."""
        for name, typ, start, off, size, link, entsize in self.sections:
            if typ != 8 and start and start <= addr < start + size:
                return self.data[off + addr - start:off + size]
        raise GuardError(f'address 0x{addr:x} is not in a loaded section')


def _uleb(b, i):
    result = shift = 0
    while True:
        byte = b[i]
        i += 1
        result |= (byte & 0x7f) << shift
        shift += 7
        if not byte & 0x80:
            return result, i


def _sleb(b, i):
    result = shift = 0
    while True:
        byte = b[i]
        i += 1
        result |= (byte & 0x7f) << shift
        shift += 7
        if not byte & 0x80:
            if byte & 0x40:
                result -= 1 << shift
            return result, i


def read_encoded(b, i, enc, base):
    """DW_EH_PE value at b[i]; base is the address of b[0] (for pcrel)."""
    if enc == 0xff:
        return None, i
    fmt = enc & 0x0f
    if fmt in (0x00, 0x03):
        value, = struct.unpack_from('<I', b, i); j = i + 4
    elif fmt == 0x0b:
        value, = struct.unpack_from('<i', b, i); j = i + 4
    elif fmt == 0x02:
        value, = struct.unpack_from('<H', b, i); j = i + 2
    elif fmt == 0x0a:
        value, = struct.unpack_from('<h', b, i); j = i + 2
    elif fmt == 0x01:
        value, j = _uleb(b, i)
    elif fmt == 0x09:
        value, j = _sleb(b, i)
    else:
        raise GuardError(f'unsupported pointer encoding 0x{enc:02x}')
    if enc & 0x70 not in (0x00, 0x10):
        raise GuardError(f'unsupported pointer application 0x{enc:02x}')
    if enc & 0x70 == 0x10 and value:  # pcrel; like libgcc, a raw 0 stays null
        value += base + i
    return value & 0xffffffff, j


def parse_eh_frame(b, base):
    """[(pc_begin, pc_end, lsda_address or None)] until the zero terminator or the end."""
    cies, fdes, i = {}, [], 0
    while i + 4 <= len(b):
        length, = struct.unpack_from('<I', b, i)
        if length == 0:
            break
        if length == 0xffffffff:
            raise GuardError('64-bit DWARF .eh_frame is not supported')
        record, i, end = i, i + 4, i + 4 + length
        cie_id, = struct.unpack_from('<I', b, i)
        if cie_id == 0:
            j = i + 4
            version = b[j]; j += 1
            k = b.index(0, j)
            aug = b[j:k].decode(); j = k + 1
            _, j = _uleb(b, j)
            _, j = _sleb(b, j)
            if version == 1:
                j += 1
            else:
                _, j = _uleb(b, j)
            renc, lenc = 0x00, 0xff
            if aug.startswith('z'):
                _, j = _uleb(b, j)
                for ch in aug[1:]:
                    if ch == 'R':
                        renc = b[j]; j += 1
                    elif ch == 'L':
                        lenc = b[j]; j += 1
                    elif ch == 'P':
                        penc = b[j]; j += 1
                        _, j = read_encoded(b, j, penc & 0x7f, base)
                    elif ch not in 'SB':
                        raise GuardError(f'unknown CIE augmentation {aug!r}')
            cies[record] = (aug, renc, lenc)
        else:
            aug, renc, lenc = cies[i - cie_id]
            pc, j = read_encoded(b, i + 4, renc, base)
            rng, j = read_encoded(b, j, renc & 0x0f, base)
            lsda = None
            if aug.startswith('z'):
                _, j = _uleb(b, j)
                if 'L' in aug:
                    lsda, j = read_encoded(b, j, lenc, base)
            fdes.append((pc, pc + rng, lsda or None))
        i = end
    return fdes


def lsda_has_catch(b, base):
    """True when the LSDA at b[0] (address base) has a call site with a catch filter."""
    i = 0
    lpenc = b[i]; i += 1
    if lpenc != 0xff:
        _, i = read_encoded(b, i, lpenc, base)
    ttenc = b[i]; i += 1
    if ttenc != 0xff:
        _, i = _uleb(b, i)
    csenc = b[i]; i += 1
    cslen, i = _uleb(b, i)
    table_end = i + cslen
    actions = []
    while i < table_end:
        _, i = read_encoded(b, i, csenc, base)
        _, i = read_encoded(b, i, csenc, base)
        _, i = read_encoded(b, i, csenc, base)
        action, i = _uleb(b, i)
        if action:
            actions.append(action)
    for action in actions:
        k = table_end + action - 1
        for _ in range(256):  # bounded chain walk
            type_filter, k2 = _sleb(b, k)
            if type_filter > 0:
                return True
            displacement, _ = _sleb(b, k2)
            if displacement == 0:
                break
            k = k2 + displacement
    return False


def frame_table(elf):
    """Sorted [(start, end, has_catch)] for every FDE of the linked image."""
    start = elf.symbol('__eh_frame')
    if start is None:
        section = elf.section('.eh_frame')
        if section is None:
            return []  # no unwind data at all: every compiled try body fails below
        start = section[2]
        data = elf.data[section[3]:section[3] + section[4]]
    else:
        data = elf.view(start)
    table = []
    for pc, end, lsda in parse_eh_frame(data, start):
        table.append((pc, end, bool(lsda) and lsda_has_catch(elf.view(lsda), lsda)))
    return sorted(table)


# ----------------------------------------------------------------- line table

def line_addresses(readelf, elf_path, wanted):
    """{relative source path: [(line, address)]} for the wanted relative paths."""
    try:
        out = subprocess.run([readelf, '-W', '--debug-dump=decodedline', elf_path],
                             capture_output=True, text=True, check=True).stdout
    except (OSError, subprocess.CalledProcessError) as error:
        raise GuardError(f'readelf failed: {error}') from error
    return parse_decoded_lines(out, wanted)


def _owner(path, wanted):
    path = path.replace('\\', '/')
    while path.startswith('./'):
        path = path[2:]
    for rel in wanted:
        if path == rel or path.endswith('/' + rel):
            return rel
    return None


def parse_decoded_lines(text, wanted):
    result = {rel: [] for rel in wanted}
    current, seen_rows = None, False
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped:
            continue
        if stripped.endswith(':'):
            name = stripped[:-1]
            current = _owner(name[4:] if name.startswith('CU: ') else name, wanted)
            continue
        parts = stripped.split()
        if len(parts) >= 3 and parts[2].startswith('0x'):
            seen_rows = True
            if current and parts[1].isdigit():
                result[current].append((int(parts[1]), int(parts[2], 16)))
    if not seen_rows:
        raise GuardError('the ELF has no DWARF line table (build with -g)')
    return result


# ---------------------------------------------------------------------- check

def check(elf_path, readelf, project_dir, roots=('src', 'lib')):
    """(problems, summary) for the ELF. Raises GuardError when it cannot judge."""
    sites = catch_sites(project_dir, roots)
    try:
        elf = Elf(elf_path)
    except OSError as error:
        raise GuardError(str(error)) from error
    frames = frame_table(elf)
    starts = [frame[0] for frame in frames]
    lines = line_addresses(readelf, elf_path, list(sites))
    problems, checked, absent = [], 0, 0
    for rel, blocks in sorted(sites.items()):
        rows = lines.get(rel, [])
        for first, last, fn_first, fn_last in blocks:
            label = f'{rel}:{first}-{last}'
            addresses = sorted({addr for line, addr in rows if first <= line <= last})
            if not addresses:
                absent += 1
                continue
            checked += 1
            holders = set()
            for addr in addresses:
                k = bisect.bisect_right(starts, addr) - 1
                if k < 0 or not frames[k][0] <= addr < frames[k][1]:
                    problems.append(f'{label}: try body code at 0x{addr:x} has no unwind entry')
                    break
                holders.add(k)
            else:
                # The enclosing function (or an inlined copy) also holds code from its own
                # lines outside the try body; a lambda defined inside the try does not.
                owners = [k for k in sorted(holders)
                          if any(frames[k][0] <= addr < frames[k][1] and fn_first <= line <= fn_last
                                 and not first <= line <= last for line, addr in rows)]
                if not owners:
                    problems.append(f'{label}: no function containing the try statement was found')
                for k in owners:
                    if not frames[k][2]:
                        problems.append(f'{label}: function at 0x{frames[k][0]:x} holds the try body '
                                        'but its exception table has no catch handler')
    summary = (f'{checked} try/catch site(s) verified, {absent} not in this build, '
               f'{len(frames)} unwind entries')
    return problems, summary


def find_readelf(cc):
    """The toolchain readelf next to a gcc/g++ driver path, or None."""
    if not cc:
        return None
    head, tail = os.path.split(cc)
    match = re.match(r'(.*-)(?:gcc|g\+\+|cc|c\+\+)(\.exe)?$', tail)
    if not match:
        return None
    candidate = os.path.join(head, match.group(1) + 'readelf' + (match.group(2) or ''))
    return candidate if os.path.isfile(candidate) and os.access(candidate, os.X_OK) else None


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('elf')
    parser.add_argument('--readelf', required=True)
    parser.add_argument('--project-dir', default=os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    parser.add_argument('--root', action='append', help='source roots to scan (default: src, lib)')
    args = parser.parse_args(argv)
    try:
        problems, summary = check(args.elf, args.readelf, args.project_dir,
                                  tuple(args.root or ('src', 'lib')))
    except GuardError as error:
        print(f'[eh-catch-guard] UNAVAILABLE: {error}', file=sys.stderr)
        return 2
    for problem in problems:
        print(f'[eh-catch-guard] FAIL {problem}', file=sys.stderr)
    print(f'[eh-catch-guard] {"FAIL" if problems else "OK"}: {summary}')
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main())
