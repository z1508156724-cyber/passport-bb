#include <string.h>
#include <stdint.h>
#include "bsp_display.h"
#include "bsp_pins.h"
#include "driver/usb_serial_jtag.h"
#include "driver/usb_serial_jtag_vfs.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "lvgl.h"

static const char command[] = "FAP_SCREENSHOT_V1";
static const char header[] = "\nFAP_SCREENSHOT_V1 240 320 RGB565LE 153600\n";
static lv_display_t *display;
static bool capturing;
static int next_row;

static bool send_bytes(const void *buffer, int length)
{
    return usb_serial_jtag_write_bytes(buffer, length, pdMS_TO_TICKS(500)) == length;
}

/* This callback follows the BSP's corner mask and precedes SPI byte swapping. */
static void capture_flush(lv_event_t *event)
{
    if (!capturing) return;
    const lv_area_t *area = lv_event_get_param(event);
    lv_draw_buf_t *buf = lv_display_get_buf_active(display);
    if (!area || !buf || area->x1 != 0 || area->x2 != BSP_LCD_W - 1 ||
        area->y1 != next_row || buf->header.stride < BSP_LCD_W * sizeof(uint16_t)) {
        capturing = false;
        return;
    }
    if (!next_row && !send_bytes(header, sizeof(header) - 1)) {
        capturing = false;
        return;
    }
    for (int y = area->y1; y <= area->y2; y++) {
        const uint8_t *source = buf->data + (y - area->y1) * buf->header.stride;
        if (!send_bytes(source, BSP_LCD_W * sizeof(uint16_t))) {
            capturing = false;
            return;
        }
        next_row++;
    }
}

static void capture(void)
{
    if (!bsp_lvgl_lock(500)) return;
    /* Drain old partial updates before forcing one complete top-to-bottom frame. */
    lv_refr_now(display);
    esp_log_level_set("*", ESP_LOG_NONE);
    next_row = 0;
    capturing = true;
    lv_obj_invalidate(lv_display_get_screen_active(display));
    lv_refr_now(display);
    capturing = false;
    esp_log_level_set("*", CONFIG_LOG_DEFAULT_LEVEL);
    bsp_lvgl_unlock();
}

static void serial_reader(void *unused)
{
    (void)unused;
    char window[sizeof(command) - 1] = {0};
    uint8_t input[32];
    for (;;) {
        int received = usb_serial_jtag_read_bytes(input, sizeof(input), pdMS_TO_TICKS(250));
        if (received < 0) vTaskDelay(pdMS_TO_TICKS(200));
        for (int i = 0; i < received; i++) {
            if (input[i] == '\r' || input[i] == '\n') {
                memset(window, 0, sizeof(window));
                continue;
            }
            memmove(window, window + 1, sizeof(window) - 1);
            window[sizeof(window) - 1] = input[i];
            if (!memcmp(window, command, sizeof(window))) {
                capture();
                memset(window, 0, sizeof(window));
            }
        }
    }
}

void pager_screenshot_start(lv_display_t *target)
{
    usb_serial_jtag_driver_config_t buffers = {.tx_buffer_size = 1024, .rx_buffer_size = 256};
    if (usb_serial_jtag_driver_install(&buffers) != ESP_OK) return;
    usb_serial_jtag_vfs_use_driver();
    display = target;
    lv_display_add_event_cb(display, capture_flush, LV_EVENT_FLUSH_START, NULL);
    xTaskCreate(serial_reader, "pager_shot", 8192, NULL, 3, NULL);
}
