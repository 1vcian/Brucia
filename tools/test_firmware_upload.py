#!/usr/bin/env python3
"""Failure injection against the actual Arduino-free upload transaction engine.

Fake rename models LittleFS replacement and FAT's refusal to replace a destination.
Reset boundaries preserve the fake medium; this is not physical power-loss acceptance.
"""
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
TEST = r'''
#include "storage_upload.h"
#include <cassert>
#include <cstring>
#include <iostream>
#include <map>
#include <set>
using namespace brucia::companion::upload;
const std::string TOKEN="0123456789abcdef0123456789abcdef";
const std::string OTHER="fedcba9876543210fedcba9876543210";
struct Fake : Store {
    std::map<std::string,std::string> files;
    std::set<std::string> dirs;
    bool atomic=false, failWrite=false, shortJournal=false, failDigest=false, failErase=false;
    int moves=0; std::set<int> failedMoves;
    Kind kind(const std::string&p) override {return dirs.count(p)?Kind::Other:files.count(p)?Kind::File:Kind::Missing;}
    bool load(const std::string&p,size_t max,std::string&out) override {
        if(!files.count(p)||files[p].size()>max)return false;out=files[p];return true;
    }
    bool write(const std::string&p,const uint8_t*b,size_t n,bool append) override {
        if(!append)files[p]="";
        if((failWrite&&p==PART)||(shortJournal&&p==JOURNAL)) {
            if(n)files[p].append((const char*)b,n/2);return false;
        }
        if(n)files[p].append((const char*)b,n);return true;
    }
    bool digest(const std::string&p,uint32_t&size,uint32_t&crc) override {
        if(failDigest||!files.count(p))return false;size=files[p].size();
        crc=crcStep(0xffffffff,(const uint8_t*)files[p].data(),size)^0xffffffff;return true;
    }
    bool move(const std::string&a,const std::string&b) override {
        ++moves;if(failedMoves.count(moves)||!files.count(a)||(!atomic&&files.count(b)))return false;
        files[b]=files[a];files.erase(a);return true;
    }
    bool erase(const std::string&p) override {return !failErase&&files.erase(p)>0;}
    bool replacesAtomically()const override{return atomic;}
};
void stage(Transaction&t,const std::string&path="/target") {
    assert(!t.begin(TOKEN,path,3,0x352441c2,0));
    assert(!t.chunk(TOKEN,0,(const uint8_t*)"abc",3,1));
}
void clean(Fake&f){assert(!f.files.count(PART)&&!f.files.count(BACKUP)&&!f.files.count(JOURNAL));}
int main(){int checks=0;
    for(bool atomic:{false,true})for(bool existing:{false,true}) {
        Fake f;f.atomic=atomic;if(existing)f.files["/target"]="previous";
        Transaction t(f);stage(t);assert(!t.commit(TOKEN));assert(f.files["/target"]=="abc");clean(f);++checks;
        assert(!t.begin(TOKEN,"/target",0,0,0));assert(!t.commit(TOKEN));assert(f.files["/target"].empty());clean(f);++checks;
    }
    for(auto path:{"/", "relative", "/../target", "/a/./b", "/a//b", "/a/", "/a ", "/a.",
                  "/a\\b", "/.BRUCIA-UPLOAD.part", "/a\nb", "/a:b"}) {
        Fake f;Transaction t(f);assert(t.begin(TOKEN,path,0,0,0));assert(f.files.empty());++checks;
    }
    assert(validPath("/folder/hello world.txt"));assert(!validPath("/"+std::string(192,'a')));++checks;
    {
        Fake f;f.files["/target"]="previous";Transaction t(f);stage(t);
        assert(t.begin(OTHER,"/other",0,0,0));assert(t.abort(OTHER));assert(t.commit(OTHER));
        assert(t.chunk(TOKEN,0,(const uint8_t*)"x",1,1));assert(t.chunk(OTHER,3,(const uint8_t*)"x",1,1));
        assert(!t.abort(TOKEN));assert(f.files["/target"]=="previous");clean(f);++checks;
    }
    for(int failure=0;failure<4;failure++) {
        Fake f;f.files["/target"]="previous";Transaction t(f);stage(t);
        if(failure==0)f.files[PART]="abd";
        if(failure==1)f.files[PART]+="extra";
        if(failure==2)f.failDigest=true;
        if(failure==3)f.files[PART]="ab";
        assert(t.commit(TOKEN));assert(f.files["/target"]=="previous");clean(f);++checks;
    }
    {
        Fake f;f.files["/target"]="previous";Transaction t(f);
        assert(!t.begin(TOKEN,"/target",3,0x352441c2,0));f.failWrite=true;
        assert(t.chunk(TOKEN,0,(const uint8_t*)"abc",3,1));assert(!t.active());
        assert(f.files["/target"]=="previous");clean(f);++checks;
    }
    for(int failAt:{1,2}) {
        Fake f;f.files["/target"]="previous";Transaction t(f);stage(t);f.failedMoves.insert(failAt);
        assert(t.commit(TOKEN));assert(f.files["/target"]=="previous");clean(f);++checks;
    }
    {
        Fake f;f.files["/target"]="previous";Transaction t(f);stage(t);f.failedMoves={2,3};
        assert(t.commit(TOKEN));assert(!f.files.count("/target"));assert(f.files[BACKUP]=="previous");
        assert(f.files.count(JOURNAL));Transaction reboot(f);assert(!reboot.recover());
        assert(f.files["/target"]=="previous");clean(f);++checks;
    }
    for(int boundary=0;boundary<3;boundary++) {
        Fake f;f.files["/target"]="previous";Transaction t(f);stage(t);
        if(boundary>=1)assert(f.move("/target",BACKUP));
        if(boundary==2)assert(f.move(PART,"/target"));
        Transaction reboot(f);assert(!reboot.recover());
        assert(f.files["/target"]==(boundary==2?"abc":"previous"));clean(f);++checks;
    }
    {
        Fake f;f.files["/target"]="previous";Transaction t(f);stage(t);f.failErase=true;
        assert(!t.commit(TOKEN));assert(t.cleanupPending());assert(f.files["/target"]=="abc");
        f.failErase=false;Transaction reboot(f);assert(!reboot.recover());clean(f);++checks;
    }
    for(int collision=0;collision<3;collision++) {
        Fake f;f.files["/target"]="previous";
        f.files[collision==0?PART:collision==1?BACKUP:JOURNAL]="user data";
        auto before=f.files;Transaction t(f);assert(t.begin(TOKEN,"/target",0,0,0));assert(f.files==before);++checks;
    }
    {
        Fake f;f.files["/target"]="previous";f.shortJournal=true;Transaction t(f);
        assert(t.begin(TOKEN,"/target",0,0,0));assert(f.files["/target"]=="previous");
        auto before=f.files;assert(t.recover());assert(f.files==before);++checks;
    }
    {
        Fake f;f.files["/target"]="previous";Transaction t(f);stage(t);
        f.move("/target",BACKUP);f.files["/target"]="foreign content";
        Transaction reboot(f);assert(reboot.recover());assert(f.files[BACKUP]=="previous");
        assert(f.files["/target"]=="foreign content");++checks;
    }
    {
        Fake f;f.files["/target"]="previous";Transaction t(f);
        assert(!t.begin(TOKEN,"/target",0,0,0xfffffff0));t.expire(10);assert(t.active());
        t.expire(120000);assert(!t.active());assert(f.files["/target"]=="previous");clean(f);++checks;
    }
    {
        Fake f;Transaction t(f);assert(t.begin(TOKEN,"/target",MAX_SIZE+1,0,0));
        assert(t.begin("bad","/target",0,0,0));assert(f.files.empty());
        f.dirs.insert("/dir");assert(t.begin(TOKEN,"/dir",0,0,0));assert(f.files.empty());++checks;
    }
    std::cout<<"Upload transaction PASS ("<<checks<<" failure/recovery scenarios, UBSan)\n";
}
'''


def main():
    with tempfile.TemporaryDirectory(prefix="brucia-upload-") as tmp:
        tmp = Path(tmp)
        source = tmp / "test.cpp"
        source.write_text(TEST)
        exe = tmp / "test"
        subprocess.run([os.environ.get("CXX", "c++"), "-std=c++17", "-fsanitize=undefined",
                        "-fno-sanitize-recover=undefined", "-I", str(ROOT / "src/companion"),
                        str(source), str(ROOT / "src/companion/storage_upload.cpp"), "-o", str(exe)], check=True)
        subprocess.run([str(exe)], check=True)


if __name__ == "__main__":
    main()
