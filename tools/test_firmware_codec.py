#!/usr/bin/env python3
"""Compile the real firmware codec with an Arduino String shim and UBSan."""
from pathlib import Path
import base64
import os
import random
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SHIM = r'''
#pragma once
#include <string>
#include <cstdint>
#include <cstddef>
class String : public std::string {
public:
    using std::string::string;
    using std::string::operator=;
};
'''
TEST = r'''
#include "codec.h"
#include <cassert>
#include <cstring>
#include <cstdlib>
#include <iostream>
using namespace brucia::companion;
int main() {
    const char *plain[]={"f","fo","foo","foob","fooba","foobar"};
    const char *encoded[]={"Zg==","Zm8=","Zm9v","Zm9vYg==","Zm9vYmE=","Zm9vYmFy"};
    for(int i=0;i<6;++i) {
        assert(b64encode((const uint8_t*)plain[i],strlen(plain[i]))==encoded[i]);
        size_t n=99; auto out=companionB64decode(encoded[i],&n);
        assert(out && n==strlen(plain[i]) && !memcmp(out,plain[i],n)); free(out);
    }
    // Long arbitrary binary data catches the former signed left-shift overflow.
    uint8_t data[4096]; for(size_t i=0;i<sizeof(data);++i) data[i]=i%256;
    auto encodedData=b64encode(data,sizeof(data));
    size_t n=0; auto out=companionB64decode(encodedData,&n);
    assert(out && n==sizeof(data) && !memcmp(out,data,n)); free(out);
    for(const char *bad:{"?Zg==","Z=g=","Zg===","Zg==AAAA","Z","Zh==","Zm9=","===="}) {
        n=123; out=companionB64decode(bad,&n); assert(!out && n==0);
    }
    out=companionB64decode(" Z m 8 =\r\n",&n); assert(out && n==2);free(out);
    out=companionB64decode("Zg",&n); assert(out && n==1);free(out); // legacy unpadded
    out=companionB64decode("",&n); assert(!out && n==0);
    std::cout<<"Firmware codec PASS (UBSan)\n";
}
'''
def reference_cases():
    """Python's encoder is an independent oracle, not a same-codec round trip."""
    rng = random.Random(0xB12C1A)
    cases = []
    for length in [*range(1, 129), 255, 256, 767, 768, 3072, 6144]:
        raw = bytes(rng.randrange(256) for _ in range(length))
        expected = base64.b64encode(raw).decode("ascii")
        values = ",".join(str(b) for b in raw)
        cases.append('''{
            const uint8_t raw[] = {''' + values + '''};
            const String expected = "''' + expected + '''";
            assert(b64encode(raw, sizeof(raw)) == expected);
            size_t size = 0;
            auto decoded = companionB64decode(expected, &size);
            assert(decoded && size == sizeof(raw) && !memcmp(decoded, raw, size));
            free(decoded);
        }''')
    # Corrupt each position of a valid sample: no invalid byte may be ignored.
    cases.append('''{
        const std::string original = "AAECAwQFBgcICQoLDA0ODw==";
        for (size_t i = 0; i < original.size(); ++i) {
            String corrupted(original.c_str()); corrupted[i] = '?';
            size_t size = 123;
            auto decoded = companionB64decode(corrupted, &size);
            assert(!decoded && size == 0);
        }
    }''')
    return "\n".join(cases)


def main():
    with tempfile.TemporaryDirectory(prefix="brucia-codec-") as tmp:
        tmp = Path(tmp)
        (tmp / "Arduino.h").write_text(SHIM)
        test = TEST.replace('    std::cout<<"Firmware codec PASS', reference_cases() + '\n    std::cout<<"Firmware codec PASS')
        (tmp / "test.cpp").write_text(test)
        exe = tmp / "codec"
        subprocess.run([os.environ.get("CXX", "c++"), "-std=c++17", "-fsanitize=undefined",
                        "-fno-sanitize-recover=undefined", "-I", str(tmp), "-I", str(ROOT / "src/companion"),
                        str(tmp / "test.cpp"), str(ROOT / "src/companion/codec.cpp"), "-o", str(exe)], check=True)
        subprocess.run([str(exe)], check=True)


if __name__ == "__main__":
    main()
