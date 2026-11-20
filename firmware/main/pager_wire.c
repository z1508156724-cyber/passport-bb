#include "pager_wire.h"
#include <string.h>

/* Validate complete Unicode scalars before passing notification text to LVGL. */
size_t pager_utf8_decode(const uint8_t *bytes, size_t length, uint32_t *point)
{
    *point = 0xfffd;
    if (!length) return 0;
    unsigned lead = bytes[0];
    if (lead < 0x80) { *point = lead; return 1; }
    unsigned count, minimum, value;
    if (lead >= 0xc2 && lead <= 0xdf) {
        count = 2; minimum = 0x80; value = lead & 0x1f;
    } else if (lead >= 0xe0 && lead <= 0xef) {
        count = 3; minimum = 0x800; value = lead & 0x0f;
    } else if (lead >= 0xf0 && lead <= 0xf4) {
        count = 4; minimum = 0x10000; value = lead & 7;
    } else return 1;
    for (unsigned i = 1; i < count; i++) {
        if (i >= length || (bytes[i] & 0xc0) != 0x80) return i;
        value = (value << 6) | (bytes[i] & 0x3f);
    }
    if (value >= minimum && value <= 0x10ffff && (value < 0xd800 || value > 0xdfff))
        *point = value;
    return count;
}

void pager_copy_text(char *out, size_t capacity, const void *src, size_t length)
{
    if (!capacity) return;
    const uint8_t *bytes = src;
    size_t used = 0;
    for (size_t i = 0; i < length && used < capacity - 1;) {
        uint32_t point;
        size_t count = pager_utf8_decode(bytes + i, length - i, &point);
        if (point == 0xfffd) out[used++] = '?';
        else if (point == '\r') {
            out[used++] = '\n';
            if (i + count < length && bytes[i + count] == '\n') count++;
        } else if ((point < 0x20 && point != '\n') || point == 0x7f) out[used++] = ' ';
        else {
            if (count > capacity - 1 - used) break;
            memcpy(out + used, bytes + i, count);
            used += count;
        }
        i += count;
    }
    out[used] = 0;
}

int pager_legacy_decode(const uint8_t *bytes, size_t length, pager_message *out)
{
    if (length < 8 || bytes[0] != 1) return 0;
    unsigned op = bytes[1];
    *out = (pager_message){.id = (uint32_t)bytes[2] | (uint32_t)bytes[3] << 8 |
                               (uint32_t)bytes[4] << 16 | (uint32_t)bytes[5] << 24,
                           .silent = (bytes[6] & 1) != 0, .legacy = true};
    if (op == PAGER_REMOVE || op == PAGER_CLEAR) return length == 8 ? op : 0;
    if (op != PAGER_UPSERT) return 0;
    size_t offset = 8;
    unsigned seen = 0;
    while (offset < length) {
        if (length - offset < 3) return 0;
        unsigned field = bytes[offset];
        size_t size = bytes[offset + 1] | bytes[offset + 2] << 8;
        offset += 3;
        if (size > length - offset) return 0;
        const uint8_t *value = bytes + offset;
        if (field <= 4) {
            if (seen & (1u << field)) return 0;
            seen |= 1u << field;
        }
        switch (field) {
        case 1: pager_copy_text(out->app, sizeof(out->app), value, size); break;
        case 2: pager_copy_text(out->title, sizeof(out->title), value, size); break;
        case 3: pager_copy_text(out->body, sizeof(out->body), value, size); break;
        case 4: {
            uint64_t epoch = 0;
            if (size != 10) return 0;
            for (size_t i = 0; i < size; i++) {
                if (value[i] < '0' || value[i] > '9') return 0;
                epoch = epoch * 10 + value[i] - '0';
            }
            if (epoch < 1700000000 || epoch > 4102444800) return 0;
            out->time = (time_t)epoch;
            break;
        }
        default: break;
        }
        offset += size;
    }
    return (seen & 0x1e) == 0x1e && out->app[0] ? PAGER_UPSERT : 0;
}
