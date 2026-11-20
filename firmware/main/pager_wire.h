#pragma once
#include "pager.h"
#include <stddef.h>

enum {PAGER_UPSERT = 1, PAGER_REMOVE, PAGER_CLEAR};
int pager_legacy_decode(const uint8_t *bytes, size_t length, pager_message *out);
void pager_copy_text(char *out, size_t limit, const void *src, size_t length);
size_t pager_utf8_decode(const uint8_t *bytes, size_t length, uint32_t *point);
