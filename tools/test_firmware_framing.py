#!/usr/bin/env python3
"""Compile and exercise the actual firmware USB reader with host-side Arduino shims.

Requires a C++ compiler; no ESP32 or PlatformIO dependencies. This checks framing,
not USB timing or the Arduino allocator. Run: python3 tools/test_firmware_framing.py
"""
from pathlib import Path
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
source = (ROOT / "src/companion/protocol.cpp").read_text()
start = source.index("static uint8_t rxHeader[4];")
end = source.index("static void writeFrame", start)
shim = r'''
#include <cassert>
#include <cstdint>
#include <deque>
#include <string>
#include <iostream>
using std::size_t;
class String : public std::string {
public:
    using std::string::string;
    using std::string::operator=;
    bool reserve(size_t n) { std::string::reserve(n); return true; }
};
static uint32_t nowMs = 0;
static uint32_t millis() { return nowMs; }
static const uint32_t COMPANION_MAX_FRAME = 8192;
struct SerialShim {
    std::deque<uint8_t> q;
    size_t available() { return q.size(); }
    int read() { if(q.empty()) return -1; int c=q.front(); q.pop_front(); return c; }
} Serial;
static void push(const std::string &s) { for(unsigned char c:s) Serial.q.push_back(c); }
static std::string frame(const std::string &s) {
    uint32_t n=s.size(); std::string r;
    for(int i=0;i<4;++i) r+=char(n>>(8*i));
    return r+s;
}
'''
tests = r'''
int main() {
    String out;
    const auto f=frame("{\"t\":10}");
    // Every possible split, including inside the four-byte length header.
    for(size_t split=1;split<f.size();++split) {
        resetFrameReader();
        push(f.substr(0,split)); assert(!readFrame(out,20));
        nowMs+=100; // longer than the old 20ms timeout
        push(f.substr(split)); assert(readFrame(out,20)); assert(out=="{\"t\":10}");
    }
    resetFrameReader();
    push(frame("one")+frame("two"));
    assert(readFrame(out,20) && out=="one");
    assert(readFrame(out,20) && out=="two");
    push(frame(std::string(8192,'x')));
    assert(readFrame(out,20) && out.size()==8192);
    push(std::string(4,'\0')); assert(!readFrame(out,20));
    push(std::string("\1\x20\0\0",4)); assert(!readFrame(out,20)); // 8193
    push(frame("valid")); assert(readFrame(out,20) && out=="valid");
    push(f.substr(0,2)); assert(!readFrame(out,20));
    nowMs+=2001; push(frame("after timeout"));
    assert(readFrame(out,20) && out=="after timeout");
    // A mode change discards the previous session's prefix.
    push(f.substr(0,5)); assert(!readFrame(out,20)); resetFrameReader();
    push(frame("new session")); assert(readFrame(out,20) && out=="new session");
    assert(!readFrame(out,20));
    std::cout << "Firmware framing PASS\n";
}
'''
def main():
    with tempfile.TemporaryDirectory(prefix="brucia-framing-") as tmp:
        cpp = Path(tmp) / "framing.cpp"
        exe = Path(tmp) / "framing"
        cpp.write_text(shim + source[start:end] + tests)
        subprocess.run([os.environ.get("CXX", "c++"), "-std=c++17", "-Wall", "-Wextra", str(cpp), "-o", str(exe)], check=True)
        subprocess.run([str(exe)], check=True)


if __name__ == "__main__":
    main()
