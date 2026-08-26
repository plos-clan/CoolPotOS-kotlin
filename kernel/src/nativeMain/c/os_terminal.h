#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

typedef struct {
    size_t width;
    size_t height;
    uint32_t *buffer;
    size_t pitch;
    uint8_t red_mask_size;
    uint8_t red_mask_shift;
    uint8_t green_mask_size;
    uint8_t green_mask_shift;
    uint8_t blue_mask_size;
    uint8_t blue_mask_shift;
} TerminalDisplay;

typedef struct {
    uint32_t foreground;
    uint32_t background;
    uint32_t ansi_colors[16];
} TerminalPalette;

#ifdef __cplusplus
extern "C" {
#endif

void *terminal_new(const TerminalDisplay *display,
                   float font_size,
                   void *(*malloc)(size_t),
                   void (*free)(void *));

void terminal_destroy(void *terminal);
size_t terminal_rows(void *terminal);
size_t terminal_columns(void *terminal);
void terminal_flush(void *terminal);
void terminal_process(void *terminal, const uint8_t *data, size_t len);
void terminal_set_auto_flush(void *terminal, bool auto_flush);
void terminal_set_crnl_mapping(void *terminal, bool auto_crnl);
void terminal_set_custom_color_scheme(void *terminal, const TerminalPalette *palette);

#ifdef __cplusplus
}
#endif
