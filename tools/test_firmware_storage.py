#!/usr/bin/env python3
"""Exercise the actual storage handlers and codec with fake FS/JSON/Arduino IO.

Tests command parsing, correlation, checksum, truncation and IO error propagation;
not physical SD behavior. No ESP32 or PlatformIO installation required.
"""
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
ARDUINO = r'''
#pragma once
#include <string>
#include <cstdint>
#include <cstddef>
#include <algorithm>
class String : public std::string {
public:
    using std::string::string; using std::string::operator=;
    String(const std::string &s):std::string(s){}
    bool startsWith(const char *s)const{return rfind(s,0)==0;}
    int lastIndexOf(char c)const {auto n=rfind(c);return n==npos?-1:int(n);}
    String substring(size_t from)const{return substr(from);}
    String substring(size_t from,size_t to)const{return substr(from,to-from);}
    void trim(){auto a=find_first_not_of(" \r\n\t");if(a==npos){clear();return;}
        auto b=find_last_not_of(" \r\n\t");*this=substr(a,b-a+1);}
};
'''
SHIM = r'''
#include "Arduino.h"
#include "codec.h"
#include <cassert>
#include <cstring>
#include <cstdlib>
#include <map>
#include <vector>
#include <iostream>
struct JsonObject;
struct Node {
    std::map<std::string,Node> fields; long long number=0; std::string text;
    Node& operator[](const char *s){return fields[s];}
    Node& operator=(const String&s){text=s;return *this;}
    Node& operator=(const char*s){text=s;return *this;}
    template<class T> Node& operator=(T n){number=n;return *this;}
    template<class T> T to();
};
struct JsonObject {Node*n;Node&operator[](const char*s){return (*n)[s];}};
template<> JsonObject Node::to<JsonObject>(){return {this};}
using JsonDocument=Node;
const int FILE_READ=0,FILE_WRITE=1,FILE_APPEND=2;
uint32_t nowMs=0;
uint32_t millis(){return nowMs;}
struct FS;
struct File {
    FS *fs=nullptr;std::string path;size_t offset=0;bool valid=false;
    explicit operator bool()const{return valid;}
    size_t size(); bool isDirectory();int read(uint8_t*,size_t);size_t write(const uint8_t*,size_t);
    void close(){}
};
struct FS {
    std::map<std::string,std::string> files;
    bool readFail=false,writeFail=false,directory=false;
    bool growOnRead=false;uint32_t readDelay=0;
    File open(const String&p,int mode){
        if(mode==FILE_READ && !files.count(p))return {};
        if(mode==FILE_WRITE)files[p]="";
        if(mode==FILE_APPEND)files.try_emplace(p,"");
        return {this,p,0,true};
    }
} storage;
size_t File::size(){return fs->files[path].size();}
bool File::isDirectory(){return fs->directory;}
int File::read(uint8_t*b,size_t n){nowMs+=fs->readDelay;if(fs->readFail)return 0;
    if(fs->growOnRead){fs->files[path]+="extra";fs->growOnRead=false;}
    n=std::min(n,size()-offset);
    memcpy(b,fs->files[path].data()+offset,n);offset+=n;return n;}
size_t File::write(const uint8_t*b,size_t n){if(fs->writeFail)n=0;fs->files[path].append((const char*)b,n);return n;}
bool storageAvailable=true;
bool getFsStorage(FS*&fs){fs=&storage;return storageAvailable;}
namespace brucia {namespace companion {
const int MSG_DATA=7,MSG_ACK=4,MSG_ERR=5,COMPANION_MAX_FRAME=8192;
struct CommandRequest {String command;uint32_t id;};
std::vector<Node> sent;
void beginEnvelope(Node&n,int kind,uint32_t id){n["t"]=kind;n["id"]=id;}
void sendEnvelope(Node&n){sent.push_back(n);}
void sendErr(uint32_t id,int code,const char*msg){Node n;beginEnvelope(n,MSG_ERR,id);n["d"]["code"]=code;n["d"]["msg"]=msg;sent.push_back(n);}
}}
'''
TEST = r'''
using namespace brucia::companion;
void command(const char*s){sent.clear();assert(dispatchStorageCommand({String(s),123}));}
void ack(){assert(sent.back()["t"].number==MSG_ACK);assert(sent.back()["id"].number==123);}
void error(){assert(sent.back()["t"].number==MSG_ERR);assert(sent.back()["id"].number==123);}
int main(){
    command("fs_write /hello world.txt YWJj");ack();assert(storage.files["/hello world.txt"]=="abc");
    command("fs_write_append /hello world.txt ZA==");ack();assert(storage.files["/hello world.txt"]=="abcd");
    command("fs_write /hello world.txt ");ack();assert(storage.files["/hello world.txt"].empty());
    command("fs_write /hello world.txt YWJj");ack();
    command("fs_write /hello world.txt ?bad");error();assert(storage.files["/hello world.txt"]=="abc");
    command("fs_read /hello world.txt");ack();assert(sent.size()==2);
    assert(sent[0]["id"].number==123);assert(sent[0]["d"]["seq"].number==0);
    assert(sent[0]["d"]["chunk"].text=="YWJj");
    assert(sent[1]["d"]["size"].number==3);assert(sent[1]["d"]["crc32"].number==0x352441C2);
    storage.files["/empty"]="";command("fs_read /empty");ack();assert(sent.size()==1);
    assert(sent[0]["d"]["size"].number==0 && sent[0]["d"]["crc32"].number==0);
    storage.files["/long"]=std::string(1600,'x');command("fs_read /long");ack();assert(sent.size()==4);
    for(int i=0;i<3;i++){assert(sent[i]["id"].number==123);assert(sent[i]["d"]["seq"].number==i);}
    command("fs_read /missing");error();
    storage.readFail=true;command("fs_read /long");error();assert(sent.size()==1);storage.readFail=false;
    storage.readDelay=120001;command("fs_read /long");error();assert(sent.back()["d"]["code"].number==408);storage.readDelay=0;
    storage.files["/grow"]="abc";storage.growOnRead=true;command("fs_read /grow");ack();
    assert(sent.back()["d"]["size"].number==3 && sent[0]["d"]["chunk"].text=="YWJj");
    storage.files["/large"]=std::string(16*1024*1024+1,'x');command("fs_read /large");error();
    assert(sent.back()["d"]["code"].number==413);storage.files.erase("/large");
    storage.directory=true;command("fs_read /long");error();storage.directory=false;
    storage.writeFail=true;command("fs_write /failed YWJj");error();storage.writeFail=false;
    storageAvailable=false;command("fs_read /long");error();storageAvailable=true;
    command("fs_write no_separator");error();
    assert(!dispatchStorageCommand({String("other"),123}));
    std::cout<<"Firmware storage PASS (native fake FS/JSON, real handlers + codec)\n";
}
'''


def main():
    source = (ROOT / "src/companion/storage.cpp").read_text()
    source = "\n".join(line for line in source.splitlines() if not line.startswith("#include"))
    with tempfile.TemporaryDirectory(prefix="brucia-storage-") as tmp:
        tmp = Path(tmp)
        (tmp / "Arduino.h").write_text(ARDUINO)
        (tmp / "storage.cpp").write_text(SHIM + source + TEST)
        exe = tmp / "storage"
        subprocess.run([
            os.environ.get("CXX", "c++"), "-std=c++17", "-fsanitize=undefined", "-fno-sanitize-recover=undefined",
            "-I", str(tmp), "-I", str(ROOT / "src/companion"), str(tmp / "storage.cpp"),
            str(ROOT / "src/companion/codec.cpp"), "-o", str(exe),
        ], check=True)
        subprocess.run([str(exe)], check=True)


if __name__ == "__main__":
    main()
