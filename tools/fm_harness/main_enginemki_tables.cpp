// Standalone desktop golden-table harness for Deluge C EngineMkI
// (EngineMkI::EngineMkI, EngineMkI.cpp:65-78). Includes EngineMkI.cpp directly
// so the static sinLogTable[1024] and sinExpTable[1024] filled by the real
// constructor can be dumped as 2048 little-endian uint16_t values.

#include "dsp/dx/EngineMkI.cpp"

#include <cstdio>

int main(int argc, char** argv) {
  const char* outpath = (argc > 1) ? argv[1] : "c_enginemki_tables.bin";
  EngineMkI mk1;

  FILE* f = fopen(outpath, "wb");
  if (!f) {
    perror("fopen");
    return 1;
  }
  fwrite(sinLogTable, sizeof(uint16_t), SINLOG_TABLESIZE, f);
  fwrite(sinExpTable, sizeof(uint16_t), SINEXP_TABLESIZE, f);
  fclose(f);
  fprintf(stderr, "wrote %d + %d uint16 entries to %s\n", SINLOG_TABLESIZE, SINEXP_TABLESIZE,
          outpath);
  return 0;
}
