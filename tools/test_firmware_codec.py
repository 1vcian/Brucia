#!/usr/bin/env python3
"""Compile the real firmware codec with an Arduino String shim and UBSan."""
from pathlib import Path
import os
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
def main():
    with tempfile.TemporaryDirectory(prefix="brucia-codec-") as tmp:
        tmp = Path(tmp)
        (tmp / "Arduino.h").write_text(SHIM)
        (tmp / "test.cpp").write_text(TEST)
        exe = tmp / "codec"
        subprocess.run([os.environ.get("CXX", "c++"), "-std=c++17", "-fsanitize=undefined",
                        "-fno-sanitize-recover=undefined", "-I", str(tmp), "-I", str(ROOT / "src/companion"),
                        str(tmp / "test.cpp"), str(ROOT / "src/companion/codec.cpp"), "-o", str(exe)], check=True)
        subprocess.run([str(exe)], check=True)


if __name__ == "__main__":
    main()
