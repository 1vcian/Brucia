#!/usr/bin/env python3
"""Sanitize the installed real Helix AAC SBR noise routine with synthetic state.

Requires firmware dependencies already installed. Checks this routine's bounds,
not whole-file AAC decoding or media compatibility. No third-party source copied.
"""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
MAIN = r'''
#include "sbr.h"
#include <assert.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include "sbrfreq.c"
int main(void) {
    unsigned char data[4096] = {0};
    PSInfoSBR *psi = calloc(1, sizeof(PSInfoSBR));
    assert(psi);
    SBRGrid grid = {0}; SBRFreq freq = {0}; SBRChan channel = {0};
    BitStreamInfo bits;
    grid.numNoiseFloors=1; grid.numNoiseFloorsPrev=1; freq.numNoiseFloorBands=1;
    SetBitstreamPointer(&bits,sizeof(data),data);
    fprintf(stderr,"Checking valid SBR noise state\n");
    DecodeSBRNoise(&bits,psi,&grid,&freq,&channel,0);
    assert(channel.noiseDataQuant[0][0]==0);
    for(int mode=0;mode<6;++mode) {
        fprintf(stderr,"Checking malformed SBR state %d\n",mode);
        memset(&grid,0,sizeof(grid)); memset(&freq,0,sizeof(freq)); memset(&channel,0,sizeof(channel));
        grid.numNoiseFloors=1; grid.numNoiseFloorsPrev=1; freq.numNoiseFloorBands=1;
        memset(channel.noiseDataQuant,0x55,sizeof(channel.noiseDataQuant));
        if(mode==0) grid.numNoiseFloors=MAX_NUM_NOISE_FLOORS+1;
        if(mode==1) grid.numNoiseFloors=0;
        if(mode==2) grid.numNoiseFloorsPrev=MAX_NUM_NOISE_FLOORS+1;
        if(mode==3) freq.numNoiseFloorBands=MAX_NUM_NOISE_FLOOR_BANDS+1;
        if(mode==4) freq.numNoiseFloorBands=0;
        if(mode==5) grid.numNoiseFloorsPrev=-1;
        SBRChan before=channel;
        SetBitstreamPointer(&bits,sizeof(data),data);
        DecodeSBRNoise(&bits,psi,&grid,&freq,&channel,0);
        assert(memcmp(&before,&channel,sizeof(channel))==0);
    }
    // Exercise every supported noise-array shape, both channels and coding modes.
    for(int floors=1;floors<=2;++floors) for(int previous=0;previous<=2;++previous)
    for(int bands=1;bands<=5;++bands) for(int ch=0;ch<=1;++ch)
    for(int coupling=0;coupling<=1;++coupling) for(int delta=0;delta<=1;++delta) {
        memset(&grid,0,sizeof(grid)); memset(&freq,0,sizeof(freq)); memset(&channel,0,sizeof(channel));
        grid.numNoiseFloors=floors;grid.numNoiseFloorsPrev=previous;freq.numNoiseFloorBands=bands;
        channel.deltaFlagNoise[0]=delta;channel.deltaFlagNoise[1]=delta;psi->couplingFlag=coupling;
        SetBitstreamPointer(&bits,sizeof(data),data);
        DecodeSBRNoise(&bits,psi,&grid,&freq,&channel,ch);
        assert(grid.numNoiseFloorsPrev==floors);
    }
    free(psi);
    unsigned char low[49]={0}, output[6];
    for(int i=0;i<49;++i) low[i]=i;
    assert(CalcFreqNoise(output,low,4,16,32,1)>0);
    for(int mode=0;mode<4;++mode) {
        memset(output,0x55,sizeof(output));
        int count = mode==0 ? CalcFreqNoise(output,low,4,0,32,1) :
            mode==1 ? CalcFreqNoise(output,low,4,1,64,3) :
            mode==2 ? CalcFreqNoise(output,low,0,16,32,1) : CalcFreqNoise(output,low,4,16,65,1);
        assert(count==0);
        for(int i=0;i<6;++i)assert(output[i]==0x55);
    }
    puts("AAC SBR noise bounds PASS (real dependency routine, UBSan)");
}
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('-e', '--environment', default='lilygo-t-embed-cc1101')
    args = parser.parse_args()
    library = ROOT / '.pio/libdeps' / args.environment / 'ESP8266Audio/src/libhelix-aac'
    if not (library / 'sbrhuff.c').exists():
        raise SystemExit('Install firmware dependencies first: ' + str(library))
    with tempfile.TemporaryDirectory(prefix='brucia-aac-') as directory:
        directory = Path(directory)
        (directory / 'Arduino.h').write_text('#include <stdint.h>\n#include <stddef.h>\n#include <string.h>\n')
        (directory / 'pgmspace.h').write_text('#define PROGMEM\n#define pgm_read_byte(p) (*(const unsigned char*)(p))\n#define pgm_read_word(p) (*(const unsigned short*)(p))\n#define pgm_read_dword(p) (*(const unsigned int*)(p))\n')
        (directory / 'test.c').write_text(MAIN)
        exe = directory / 'noise'
        subprocess.run([os.environ.get('CC', 'cc'), '-DARDUINO', '-fsanitize=undefined',
                        '-fno-sanitize-recover=all', '-I', str(directory), '-I', str(library),
                        str(directory / 'test.c'), *(str(library / name) for name in ('sbrhuff.c','sbrtabs.c','sbrmath.c','bitstream.c')),
                        '-o', str(exe)], check=True)
        subprocess.run([str(exe)], check=True, timeout=15)


if __name__ == '__main__':
    try:
        main()
    except subprocess.CalledProcessError as error:
        raise SystemExit(error.returncode) from None
