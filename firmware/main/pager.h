#pragma once
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <time.h>
typedef struct {
    char app[96], title[161], body[481];
    time_t time;
    uint32_t id;
    uint8_t type; /* 1 call, 2 alarm */
    bool silent, apple, legacy, unread;
} pager_message;
void pager_remove_legacy(uint32_t id, bool clear);
void pager_receive(const pager_message *message);
void pager_clock(time_t utc, int offset_minutes);
int pager_zone(void);
void pager_ble_start(void);
void pager_pair(void);
void pager_pair_close(void);
unsigned pager_pair_seconds(void);
bool pager_connected(void);

void pager_tone_start(void);
void pager_tone_notify(void);
void pager_tone_volume(uint8_t percent);

bool pager_locator_ready(void);
bool pager_find_phone(bool start);
bool pager_media(int action);
void pager_locator_receive(bool start);
void pager_locator_tone(bool active);

bool pager_receive_settings(const uint8_t *data, size_t size);
bool pager_send_settings(const uint8_t *data, size_t size);
