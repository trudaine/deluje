// Standalone golden-buffer generator for NE10 fixed-point int32 r2c/c2r 1D FFT.
// Links against the real ../DelugeFirmware/src/NE10/modules/dsp/NE10_fft.c and NE10_fft_int32.c.

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>

#include "NE10_types.h"
#include "NE10_fft.h"

extern "C" {

void* delugeAlloc(unsigned int requiredSize, bool mayUseOnChipRam) {
    (void)mayUseOnChipRam;
    return std::malloc(requiredSize);
}

void delugeDealloc(void* address) {
    std::free(address);
}

void routineWithClusterLoading() {}

ne10_fft_cfg_float32_t ne10_fft_alloc_c2c_float32_c(ne10_int32_t nfft) {
    (void)nfft;
    return nullptr;
}

void ne10_mixed_radix_generic_butterfly_int32_c(
    ne10_fft_cpx_int32_t* Fout,
    const ne10_fft_cpx_int32_t* Fin,
    const ne10_int32_t* factors,
    const ne10_fft_cpx_int32_t* twiddles,
    ne10_fft_cpx_int32_t* buffer,
    const ne10_int32_t scaled_flag) {
    (void)Fout;
    (void)Fin;
    (void)factors;
    (void)twiddles;
    (void)buffer;
    (void)scaled_flag;
    std::abort();
}

void ne10_mixed_radix_generic_butterfly_inverse_int32_c(
    ne10_fft_cpx_int32_t* Fout,
    const ne10_fft_cpx_int32_t* Fin,
    const ne10_int32_t* factors,
    const ne10_fft_cpx_int32_t* twiddles,
    ne10_fft_cpx_int32_t* buffer,
    const ne10_int32_t scaled_flag) {
    (void)Fout;
    (void)Fin;
    (void)factors;
    (void)twiddles;
    (void)buffer;
    (void)scaled_flag;
    std::abort();
}
}

// Deterministic Q19-range synthetic input matching (value32 >> 12) in wave_table.cpp:437.
// Uses uint32 wrap arithmetic so Java's int wrap produces bit-identical inputs.
static int32_t synthTimeSample(int nfft, int i) {
    uint32_t x = static_cast<uint32_t>(i) * 1103515245u + static_cast<uint32_t>(nfft) * 12345u + 0x9E3779B9u;
    x ^= (x >> 13);
    x *= 1664525u;
    x ^= (x >> 16);
    // Signed 19-bit range [-262144, +262143] to stay well within unscaled FFT headroom up to nfft=2048.
    return static_cast<int32_t>(x << 13) >> 13;
}

static int32_t synthFreqComponent(int nfft, int k, int imagPart) {
    uint32_t x = static_cast<uint32_t>(k) * 2654435761u
               + static_cast<uint32_t>(nfft) * 1013904223u
               + static_cast<uint32_t>(imagPart) * 0x85EBCA6Bu;
    x ^= (x >> 15);
    x *= 2246822519u;
    x ^= (x >> 13);
    // Signed 18-bit range [-131072, +131071] so unscaled c2r sum over nfft<=2048 fits in int32.
    return static_cast<int32_t>(x << 14) >> 14;
}

static void writeBin(const char* path, const int32_t* data, size_t count) {
    FILE* f = std::fopen(path, "wb");
    if (!f) {
        std::perror(path);
        std::exit(1);
    }
    if (std::fwrite(data, sizeof(int32_t), count, f) != count) {
        std::perror("fwrite");
        std::exit(1);
    }
    std::fclose(f);
}

int main(int argc, char** argv) {
    if (argc != 4) {
        std::fprintf(stderr, "Usage: %s <r2c|c2r> <nfft> <out.bin>\n", argv[0]);
        return 1;
    }
    const char* mode = argv[1];
    int nfft = std::atoi(argv[2]);
    const char* outPath = argv[3];
    int ncfft = nfft >> 1;

    ne10_fft_r2c_cfg_int32_t cfg = ne10_fft_alloc_r2c_int32(nfft);
    if (!cfg) {
        std::fprintf(stderr, "ne10_fft_alloc_r2c_int32(%d) returned NULL\n", nfft);
        return 1;
    }

    if (std::strcmp(mode, "r2c") == 0) {
        auto* fin = static_cast<ne10_int32_t*>(std::calloc(nfft, sizeof(ne10_int32_t)));
        auto* fout = static_cast<ne10_fft_cpx_int32_t*>(std::calloc(ncfft + 1, sizeof(ne10_fft_cpx_int32_t)));
        for (int i = 0; i < nfft; i++) {
            fin[i] = synthTimeSample(nfft, i);
        }
        ne10_fft_r2c_1d_int32_c(fout, fin, cfg, 0);
        writeBin(outPath, reinterpret_cast<const int32_t*>(fout), static_cast<size_t>(2 * (ncfft + 1)));
        std::free(fin);
        std::free(fout);
    } else if (std::strcmp(mode, "c2r") == 0) {
        auto* fin = static_cast<ne10_fft_cpx_int32_t*>(std::calloc(ncfft + 1, sizeof(ne10_fft_cpx_int32_t)));
        auto* fout = static_cast<ne10_int32_t*>(std::calloc(nfft, sizeof(ne10_int32_t)));
        fin[0].r = synthFreqComponent(nfft, 0, 0);
        fin[0].i = 0;
        for (int k = 1; k < ncfft; k++) {
            fin[k].r = synthFreqComponent(nfft, k, 0);
            fin[k].i = synthFreqComponent(nfft, k, 1);
        }
        fin[ncfft].r = synthFreqComponent(nfft, ncfft, 0);
        fin[ncfft].i = 0;
        ne10_fft_c2r_1d_int32_c(fout, fin, cfg, 0);
        writeBin(outPath, fout, static_cast<size_t>(nfft));
        std::free(fin);
        std::free(fout);
    } else {
        std::fprintf(stderr, "Unknown mode: %s\n", mode);
        return 1;
    }

    ne10_fft_destroy_r2c_int32(cfg);
    return 0;
}
