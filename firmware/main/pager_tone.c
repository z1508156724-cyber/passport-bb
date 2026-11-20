#include <stdbool.h>
#include <stdatomic.h>
#include <stdint.h>
#include "bsp_audio.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

enum {RATE = 16000, BLOCK = 160, LEVEL = 7200, PAGER_HZ = 2400, PULSE_MS = 105, GAP_MS = 65};
static TaskHandle_t tone_task;
static atomic_uchar alert_volume = 45;
static atomic_bool locator_active;

static esp_err_t segment(unsigned hz, unsigned duration_ms)
{
    int16_t pcm[BLOCK];
    unsigned total = RATE * duration_ms / 1000;
    unsigned phase = 0;
    const unsigned ramp = 48;
    for (unsigned offset = 0; offset < total;) {
        unsigned count = total - offset < BLOCK ? total - offset : BLOCK;
        for (unsigned i = 0; i < count; i++) {
            unsigned position = offset + i;
            unsigned tail = total - 1 - position;
            unsigned gain = position < tail ? position : tail;
            if (gain > ramp) gain = ramp;
            int polarity = phase < RATE / 2 ? 1 : -1;
            pcm[i] = hz ? polarity * (int)(gain * LEVEL / ramp) : 0;
            phase = (phase + hz) % RATE;
        }
        esp_err_t result = bsp_audio_write(pcm, count * sizeof(pcm[0]));
        if (result != ESP_OK) return result;
        offset += count;
    }
    return ESP_OK;
}

static void tone_worker(void *unused)
{
    (void)unused;
    bool ready = false;
    for (;;) {
        ulTaskNotifyTake(pdTRUE, portMAX_DELAY);
        unsigned volume = alert_volume;
        bool finding = locator_active;
        if (!volume && !finding) continue;
        esp_err_t result = ESP_OK;
        if (!ready) {
            result = bsp_audio_init();
            if (result == ESP_OK) result = bsp_audio_set_format(RATE, 16, 1);
            if (result == ESP_OK) ready = true;
        }
        if (finding) {
            if (result == ESP_OK && locator_active) {
                bsp_audio_set_volume(75);
                ESP_LOGI("pager", "Locator speaker started");
                while (locator_active && result == ESP_OK) {
                    result = segment(2850, 140);
                    if (result == ESP_OK && locator_active) result = segment(0, 145);
                }
                if (result == ESP_OK) segment(0, 25);
                ESP_LOGI("pager", "Locator speaker stopped");
            }
            if (result != ESP_OK) ESP_LOGW("pager", "Locator audio failed: %s", esp_err_to_name(result));
            continue;
        }
        if (result == ESP_OK) {
            bsp_audio_set_volume(volume);
            result = segment(PAGER_HZ, PULSE_MS);
        }
        if (result == ESP_OK) result = segment(0, GAP_MS);
        if (result == ESP_OK) result = segment(PAGER_HZ, PULSE_MS);
        if (result == ESP_OK) result = segment(0, 30);
        if (result == ESP_OK) ESP_LOGI("pager", "Pager alert tone played");
        else ESP_LOGW("pager", "Pager alert audio failed: %s", esp_err_to_name(result));
        /* Coalesce requests that arrived while the current tone was playing. */
        if (!locator_active) ulTaskNotifyTake(pdTRUE, 0);
    }
}

void pager_tone_start(void)
{
    if (xTaskCreate(tone_worker, "pager_tone", 4096, NULL, 3, &tone_task) != pdPASS)
        ESP_LOGW("pager", "Pager tone task unavailable");
}

void pager_tone_notify(void)
{
    if (tone_task) xTaskNotifyGive(tone_task);
}

void pager_tone_volume(uint8_t percent)
{
    alert_volume = percent;
}


void pager_locator_tone(bool active)
{
    locator_active = active;
    if (active && tone_task) xTaskNotifyGive(tone_task);
}
