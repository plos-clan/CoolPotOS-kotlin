#include "bridge.h"

extern size_t ZSTD_decompress(void *destination, size_t capacity,
                              const void *source, size_t size);
extern unsigned ZSTD_isError(size_t code);

int cp_zstd_decompress(void *destination, size_t capacity,
                       const void *source, size_t size) {
    size_t result = ZSTD_decompress(destination, capacity, source, size);
    return ZSTD_isError(result) || result > 0x7fffffffUL ? -1 : (int)result;
}
