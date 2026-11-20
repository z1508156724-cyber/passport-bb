#include <stdio.h>
#include <stdlib.h>
#include <stdatomic.h>
#include <string.h>
#include <sys/time.h>
#include "bsp_display.h"
#include "bsp_battery.h"
#include "esp_log.h"
#include "bsp_button.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "lvgl.h"
#include "nvs.h"
#include "nvs_flash.h"
#include "pager.h"
#include "pager_wire.h"

LV_FONT_DECLARE(pager_cjk);
LV_FONT_DECLARE(pager_extra);
LV_FONT_DECLARE(pager_emoji);
static lv_font_t display_font, extra_font;
void pager_screenshot_start(lv_display_t *target);
enum {INBOX, MENU, DETAILS, STATS, STOPWATCH, POMODORO, DND, APP_ALERTS, CLOCK, PAIRING, CLOCK_SETTINGS, DEVICE_SETTINGS, FIND_DEVICE, MEDIA};
enum {MAX_MESSAGES = 24, MAX_APP_RULES = 12};
static const char *menus[] = {"今日统计", "应用提醒", "秒表", "番茄钟", "免打扰", "息屏时钟", "蓝牙配对", "查找设备", "媒体控制", "设置"};
static const int pages[] = {STATS, APP_ALERTS, STOPWATCH, POMODORO, DND, CLOCK_SETTINGS, PAIRING, FIND_DEVICE, MEDIA, DEVICE_SETTINGS};
enum {MENU_COUNT = sizeof(menus) / sizeof(menus[0])};
_Static_assert(MENU_COUNT == sizeof(pages) / sizeof(pages[0]), "menu/page count mismatch");
static pager_message inbox[MAX_MESSAGES];
static bool pop_waiting[MAX_MESSAGES];
static int count, selected, menu_selected, page, body_page, awake_page;
enum {BODY_WIDTH = 196, BODY_HEIGHT = 110, BODY_LINE_SPACE = -8};
static pager_message popup;
static enum { DELETE_NONE, DELETE_ONE, DELETE_READ, DELETE_ALL } delete_prompt;
static struct {
    uint32_t id;
    bool apple, legacy;
    char app[sizeof(inbox[0].app)];
} delete_target;
static int64_t popup_opened;
#define POPUP_TIMEOUT_MS 3000
static bool alert, screen_off, dnd_enabled, clock_screen;
static int dnd_start = 22, dnd_end = 7, dnd_field;
static atomic_int zone_minutes = 480;
int pager_zone(void) {return zone_minutes;}
static int daily_count, daily_muted, daily_day = -1;
static uint32_t serial;
typedef enum { APP_TIMED, APP_STICKY, APP_SILENT } app_alert_mode;
typedef struct { char app[sizeof(inbox[0].app)]; uint8_t mode; } app_alert_rule;
static app_alert_rule app_rules[MAX_APP_RULES];
static char selected_app[sizeof(inbox[0].app)];
static const char *app_rule_status;
static const char *app_mode_names[] = {"3 秒收起", "按键收起", "静音"};
static nvs_handle_t settings;
static int64_t last_input, sw_start, sw_elapsed, pom_start, pom_remaining = 25 * 60 * 1000;
static bool sw_running, pom_running;
static int battery_soc = -1;
static const uint16_t sleep_choices[] = {15, 30, 60, 120, 300, 0};
enum {SLEEP_CHOICES = sizeof(sleep_choices) / sizeof(sleep_choices[0])};
static int sleep_choice = 1, volume = 45, brightness = 60, settings_field;
static uint16_t settings_revision;
typedef struct { uint8_t bytes[10]; } settings_request;
static QueueHandle_t settings_requests;
static bool details_has_next;
typedef struct { pager_message msg; bool remove, clear; } inbox_event;
static QueueHandle_t messages, buttons, clocks, locator_events;
static int64_t locator_until, phone_ring_until;
static bool phone_ring_failed;
static const char *media_status;
static int64_t media_until;
/* A press edge already acted on the key; drop the click the driver reports later. */
static bool press_used;
static lv_obj_t *root;
static const uint32_t ink = 0x182a1c, subdued = 0x344a30;
static const uint32_t lcd_background = 0xb8c891, panel = 0xa1b47b;
static const uint32_t bright = 0x613816, lcd_edge = 0x687e4d;
typedef struct { time_t utc; int zone; } clock_update;
typedef struct { bsp_btn_t key; bsp_btn_ev_t event; } button_input;

static int64_t ms(void) { return esp_timer_get_time() / 1000; }
static bool have_time(void) { return time(NULL) >= 1700000000; }

static struct tm local_at(time_t utc)
{
    utc += zone_minutes * 60;
    struct tm value;
    gmtime_r(&utc, &value);
    return value;
}

void pager_clock(time_t utc, int offset)
{
    clock_update value = {utc, offset};
    xQueueOverwrite(clocks, &value);
}

bool pager_receive_settings(const uint8_t *data, size_t size)
{
    settings_request request = {0};
    if (size > sizeof(request.bytes)) return false;
    memcpy(request.bytes, data, size);
    return xQueueSend(settings_requests, &request, 0) == pdTRUE;
}

void pager_locator_receive(bool start)
{
    xQueueOverwrite(locator_events, &start);
}

static void submit(inbox_event *event)
{
    if (xQueueSend(messages, event, 0) != pdTRUE) {
        inbox_event discarded;
        xQueueReceive(messages, &discarded, 0);
        xQueueSend(messages, event, 0);
    }
}
void pager_receive(const pager_message *msg)
{
    inbox_event event = {.msg = *msg};
    submit(&event);
}
void pager_remove_legacy(uint32_t id, bool clear)
{
    inbox_event event = {.msg.id = id, .remove = true, .clear = clear};
    submit(&event);
}

static void button(bsp_btn_t key, bsp_btn_ev_t event, void *arg)
{
    (void)arg;
    if (event == BSP_BTN_CLICK || event == BSP_BTN_LONG || event == BSP_BTN_DOUBLE) {
        button_input value = {key, event};
        xQueueSend(buttons, &value, 0);
    }
}

static lv_obj_t *box(int x, int y, int w, int h, uint32_t color)
{
    lv_obj_t *object = lv_obj_create(root);
    lv_obj_remove_style_all(object);
    lv_obj_remove_flag(object, LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_set_pos(object, x, y);
    lv_obj_set_size(object, w, h);
    lv_obj_set_style_bg_opa(object, LV_OPA_COVER, 0);
    lv_obj_set_style_bg_color(object, lv_color_hex(color), 0);
    return object;
}

static const uint8_t lcd_masks[] = {0x3f, 0x06, 0x5b, 0x4f, 0x66, 0x6d, 0x7d, 0x07, 0x7f, 0x6f};

/* Seven straight LCD segments fit the display pixel grid at native size. */
static void lcd_digits(const char *text, int y, int thickness)
{
    int gap = thickness + 3;
    int digit_width = thickness * 6;
    int cell = digit_width + gap;
    int separator = thickness * 3 + 2;
    int total = -gap;
    for (const char *p = text; *p; p++) total += *p == ':' ? separator + gap : cell;
    int x = (240 - total) / 2;
    int t = thickness;
    const int segments[][4] = {
        {t, 0, 4*t, t}, {5*t, t, t, 4*t}, {5*t, 6*t, t, 4*t},
        {t, 10*t, 4*t, t}, {0, 6*t, t, 4*t}, {0, t, t, 4*t}, {t, 5*t, 4*t, t},
    };
    for (const char *p = text; *p; p++) {
        if (*p == ':') {
            box(x + t, y + 3*t, t, t, ink);
            box(x + t, y + 7*t, t, t, ink);
            x += separator + gap;
            continue;
        }
        unsigned mask = *p >= '0' && *p <= '9' ? lcd_masks[*p - '0'] : 0x40;
        for (int i = 0; i < 7; i++) {
            uint32_t color = mask & (1u << i) ? ink : panel;
            box(x + segments[i][0], y + segments[i][1], segments[i][2], segments[i][3], color);
        }
        x += cell;
    }
}

static void display_text(char *out, size_t capacity, const char *text)
{
    size_t length = strlen(text), used = 0;
    bool joined = false;
    for (size_t i = 0; i < length && used < capacity - 1;) {
        uint32_t point;
        size_t count = pager_utf8_decode((const uint8_t *)text + i, length - i, &point);
        const char *raw = text + i;
        i += count;
        /* Drop invisible emoji modifiers we cannot compose on this display. */
        if (point == 0x200d) { joined = true; continue; }
        if ((point >= 0xfe00 && point <= 0xfe0f) || (point >= 0x1f3fb && point <= 0x1f3ff)) continue;
        if (joined && point >= 0x1f000 && point <= 0x1faff) { joined = false; continue; }
        joined = false;
        lv_font_glyph_dsc_t glyph;
        bool supported = point < 0x80 || (lv_font_get_glyph_dsc(&display_font, &glyph, point, 0) && !glyph.is_placeholder);
        if (!supported || point == 0xfffd) out[used++] = '?';
        else {
            if (count > capacity - 1 - used) break;
            memcpy(out + used, raw, count);
            used += count;
        }
    }
    out[used] = 0;
}

static lv_obj_t *label(const char *text, int x, int y, int w, int h, uint32_t color, bool large, bool wrap)
{
    lv_obj_t *object = lv_label_create(root);
    lv_obj_set_pos(object, x, y);
    const lv_font_t *font = large ? &lv_font_montserrat_28 : &display_font;
    /* The CJK font needs 31 px even when its typical glyphs ink only 16 px.
       Adjacent single-line labels can overlap in their transparent margins. */
    lv_obj_set_size(object, w, h < font->line_height ? font->line_height : h);
    lv_obj_set_style_text_font(object, font, 0);
    if (wrap && !large) lv_obj_set_style_text_line_space(object, BODY_LINE_SPACE, 0);
    lv_obj_set_style_text_color(object, lv_color_hex(color), 0);
    lv_label_set_long_mode(object, wrap ? LV_LABEL_LONG_WRAP : LV_LABEL_LONG_DOT);
    if (large) lv_label_set_text(object, text);
    else {
        char localized[sizeof(inbox[0].body)];
        display_text(localized, sizeof(localized), text);
        lv_label_set_text(object, localized);
    }
    return object;
}

static void time_text(char *output, size_t length, time_t value, const char *format)
{
    if (value < 1700000000) snprintf(output, length, "--:--");
    else {
        struct tm local = local_at(value);
        strftime(output, length, format, &local);
    }
}

static void duration(char *output, size_t length, int64_t value)
{
    int total = (int)(value / 1000);
    snprintf(output, length, "%02d:%02d:%02d", total / 3600, total / 60 % 60, total % 60);
}

static char lcd_date[24];

static void draw_small_segment(lv_layer_t *layer, const lv_draw_rect_dsc_t *style,
                               int x, int y, int w, int h)
{
    lv_area_t rect = {x, y, x + w - 1, y + h - 1};
    lv_draw_rect(layer, style, &rect);
}

static void draw_lcd_date(lv_event_t *event)
{
    static const uint8_t segments[][4] = {
        {2, 0, 4, 2}, {6, 2, 2, 4}, {6, 8, 2, 4}, {2, 12, 4, 2},
        {0, 8, 2, 4}, {0, 2, 2, 4}, {2, 6, 4, 2},
    };
    lv_area_t bounds;
    lv_obj_get_coords(lv_event_get_target(event), &bounds);
    lv_layer_t *layer = lv_event_get_layer(event);
    lv_draw_rect_dsc_t style;
    lv_draw_rect_dsc_init(&style);
    style.bg_color = lv_color_hex(ink);
    int x = bounds.x1, y = bounds.y1;
    for (const char *p = lcd_date; *p; p++) {
        if (*p >= '0' && *p <= '9') {
            unsigned mask = lcd_masks[*p - '0'];
            for (int i = 0; i < 7; i++) {
                if (mask & (1u << i))
                    draw_small_segment(layer, &style, x + segments[i][0], y + segments[i][1],
                                       segments[i][2], segments[i][3]);
            }
            x += 10;
        } else if (*p == ':') {
            draw_small_segment(layer, &style, x + 1, y + 3, 2, 2);
            draw_small_segment(layer, &style, x + 1, y + 9, 2, 2);
            x += 5;
        } else {
            if (*p == '-') draw_small_segment(layer, &style, x, y + 6, 4, 2);
            x += 6;
        }
    }
}

static void header(const char *title)
{
    char battery[16];
    if (have_time()) time_text(lcd_date, sizeof(lcd_date), time(NULL), "%y-%m-%d %H:%M");
    else strlcpy(lcd_date, "-- -- --  --:--", sizeof(lcd_date));
    lv_obj_t *date = lv_obj_create(root);
    lv_obj_remove_style_all(date);
    lv_obj_remove_flag(date, LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_set_pos(date, 16, 18);
    lv_obj_set_size(date, 132, 14);
    lv_obj_add_event_cb(date, draw_lcd_date, LV_EVENT_DRAW_MAIN, NULL);
    label(pager_connected() ? "BLE" : "---", 150, 11, 29, 31, subdued, false, false);
    if (battery_soc < 0) strlcpy(battery, "--%", sizeof(battery));
    else snprintf(battery, sizeof(battery), "%u%%", (unsigned)battery_soc);
    lv_obj_t *level = label(battery, 181, 11, 43, 31,
                           battery_soc >= 0 && battery_soc <= 15 ? bright : subdued, false, false);
    lv_obj_set_style_text_align(level, LV_TEXT_ALIGN_RIGHT, 0);
    label(title, 16, 37, 208, 31, ink, false, false);
    box(16, 65, 208, 2, lcd_edge);
}

static void footer(const char *text)
{
    box(16, 281, 208, 1, lcd_edge);
    label(text, 23, 282, 194, 31, subdued, false, false);
}

/* Fit each page using the same font, width and line spacing as its label. */
static size_t fit_body(const char *body)
{
    char prefix[sizeof(inbox[0].body)];
    size_t length = strlen(body);
    memcpy(prefix, body, length + 1);
    size_t best = 0;
    for (size_t end = 0; end < length;) {
        do { end++; } while (end < length && ((unsigned char)body[end] & 0xc0) == 0x80);
        char after = prefix[end];
        prefix[end] = 0;
        lv_point_t size;
        lv_text_get_size(&size, prefix, &display_font, 0, BODY_LINE_SPACE, BODY_WIDTH, LV_TEXT_FLAG_NONE);
        prefix[end] = after;
        if (size.y > BODY_HEIGHT) break;
        best = end;
    }
    return best;
}

static bool split_body(const char *body, char *output, size_t capacity)
{
    for (int i = 0; i < body_page && *body; i++) {
        size_t fitted = fit_body(body);
        if (!fitted || !body[fitted]) {
            body_page = i;
            break;
        }
        body += fitted;
    }
    size_t fitted = fit_body(body);
    snprintf(output, capacity, "%.*s", (int)fitted, body);
    return body[fitted] != 0;
}

static app_alert_mode app_mode(const char *app)
{
    for (int i = 0; i < MAX_APP_RULES; i++)
        if (app_rules[i].app[0] && !strcmp(app_rules[i].app, app)) return app_rules[i].mode;
    return APP_TIMED;
}

static int app_candidates(const char **list)
{
    int size = 0;
    for (int i = 0; i < MAX_APP_RULES; i++)
        if (app_rules[i].app[0]) list[size++] = app_rules[i].app;
    for (int i = 0; i < count; i++) {
        const char *app = inbox[i].app;
        if (!app[0]) continue;
        int known = 0;
        while (known < size && strcmp(list[known], app)) known++;
        if (known == size) list[size++] = app;
    }
    return size;
}

static int app_choice_index(const char **list, int size)
{
    for (int i = 0; i < size; i++)
        if (!strcmp(list[i], selected_app)) return i;
    return 0;
}

static void cycle_app_mode(const char *app)
{
    int slot = -1;
    for (int i = 0; i < MAX_APP_RULES; i++) {
        if (!strcmp(app_rules[i].app, app)) { slot = i; break; }
        if (!app_rules[i].app[0] && slot < 0) slot = i;
    }
    if (slot < 0) { app_rule_status = "提醒规则已满"; return; }
    app_alert_rule previous = app_rules[slot];
    app_alert_mode mode = (app_mode(app) + 1) % 3;
    app_rules[slot] = (app_alert_rule){.mode = mode};
    if (mode != APP_TIMED) strlcpy(app_rules[slot].app, app, sizeof(app_rules[slot].app));
    esp_err_t result = nvs_set_blob(settings, "app_alerts", app_rules, sizeof(app_rules));
    if (result == ESP_OK) result = nvs_commit(settings);
    if (result == ESP_OK) ESP_LOGI("pager", "Application alert mode saved: %d", mode);
    else {
        app_rules[slot] = previous;
        app_rule_status = "保存失败";
    }
}

static int read_count(void)
{
    int read = 0;
    for (int i = 0; i < count; i++) read += !inbox[i].unread;
    return read;
}

static int delete_target_index(void)
{
    for (int i = 0; i < count; i++)
        if (inbox[i].id == delete_target.id && inbox[i].apple == delete_target.apple &&
            inbox[i].legacy == delete_target.legacy && !strcmp(inbox[i].app, delete_target.app)) return i;
    return -1;
}

static void render(void)
{
    char text[128], t[24];
    lv_obj_clean(root);
    if (screen_off) return;
    if (locator_until) {
        header("查找设备");
        box(14, 88, 212, 152, panel);
        label("手机正在查找本机", 23, 108, 190, 31, ink, false, false);
        label("本机正在响铃", 23, 154, 190, 31, bright, false, false);
        footer("按任意键停止");
        return;
    }
    if (!alert && delete_prompt != DELETE_NONE) {
        header(delete_prompt == DELETE_ONE ? "删除通知" : delete_prompt == DELETE_READ ? "清除已读" : "清除全部");
        box(14, 92, 212, 120, panel);
        if (delete_prompt == DELETE_ONE) {
            label("删除这条通知？", 22, 111, 195, 31, ink, false, false);
            int index = delete_target_index();
            if (index >= 0) label(inbox[index].app, 22, 150, 195, 31, subdued, false, false);
            else label("通知已移除", 22, 150, 195, 31, subdued, false, false);
        } else if (delete_prompt == DELETE_READ) {
            snprintf(text, sizeof(text), "清除 %d 条已读通知？", read_count());
            label(text, 22, 111, 195, 31, ink, false, false);
            label(count > read_count() ? "上下键改为全部清除" : "未读通知会保留",
                  22, 150, 195, 31, subdued, false, false);
        } else {
            snprintf(text, sizeof(text), "清除全部 %d 条通知？", count);
            label(text, 22, 111, 195, 31, ink, false, false);
            if (read_count() && count > read_count()) {
                snprintf(text, sizeof(text), "含 %d 条未读", count - read_count());
                label(text, 22, 150, 195, 31, subdued, false, false);
            } else label("收件箱会清空", 22, 150, 195, 31, subdued, false, false);
        }
        footer(delete_prompt == DELETE_ONE ? "确定清除   上下键取消" : "确定清除   上下切换");
        return;
    }
    if (alert || page == DETAILS) {
        const pager_message *msg = alert ? &popup : &inbox[selected];
        bool critical = msg->type != 0;
        header(!alert ? "通知详情" : msg->type == 1 ? "来电提醒" : msg->type == 2 ? "闹钟提醒" : "新通知");
        box(14, 77, 212, 201, critical ? 0xd8b481 : panel);
        time_text(t, sizeof(t), msg->time, "%H:%M");
        snprintf(text, sizeof(text), "%s  %s", t, msg->app);
        label(text, 22, 85, 196, 22, bright, false, false);
        lv_obj_t *title = label(msg->title, 22, 110, 196, 48, ink, false, true);
        lv_label_set_long_mode(title, LV_LABEL_LONG_DOT);
        if (alert) {
            lv_obj_t *body = label(msg->body, 22, 157, BODY_WIDTH, BODY_HEIGHT, ink, false, true);
            lv_label_set_long_mode(body, LV_LABEL_LONG_DOT);
            footer(msg->type || app_mode(msg->app) == APP_STICKY ? "按任意键收起" : "3秒收起   按键关闭");
        } else {
            char body[sizeof(msg->body)], content[sizeof(msg->body)];
            display_text(body, sizeof(body), msg->body);
            details_has_next = split_body(body, content, sizeof(content));
            label(content, 22, 157, BODY_WIDTH, BODY_HEIGHT, ink, false, true);
            footer(details_has_next || body_page ? "上下翻页  长按上键删除" : "确定返回  长按上键删除");
        }
        return;
    }
    if (page == INBOX) {
        header("BB 寻呼  /  通知");
        if (!count) label("等待手机通知", 19, 109, 200, 25, subdued, false, false);
        int start = selected / 3 * 3;
        for (int i = start; i < count && i < start + 3; i++) {
            int y = 76 + (i - start) * 66;
            const pager_message *msg = &inbox[i];
            box(14, y, 212, 64, i == selected ? panel : lcd_background);
            if (msg->unread) box(19, y + 10, 6, 6, ink);
            time_text(t, sizeof(t), msg->time, "%H:%M");
            label(msg->app, 30, y + 3, 137, 18, subdued, false, false);
            label(t, 170, y + 3, 53, 18, subdued, false, false);
            label(msg->title[0] ? msg->title : msg->body, 24, y + 21, 194, 18, ink, false, false);
            label(msg->title[0] ? msg->body : "", 24, y + 38, 194, 18, subdued, false, false);
        }
        /* A track on the right edge shows where the three visible rows sit in the
           whole inbox; without it a full inbox looks the same on page one and last. */
        const int track_y = 76, track_h = 196;
        if (count > 3) {
            box(230, track_y, 4, track_h, panel);
            int thumb = track_h * 3 / count;
            if (thumb < 22) thumb = 22;
            int span = track_h - thumb;
            int pos = span * start / (count - 3);
            box(230, track_y + pos, 4, thumb, ink);
        }
        if (count) {
            snprintf(text, sizeof(text), "%d/%d", selected + 1, count);
            lv_obj_t *at = label(text, 176, 37, 50, 31, subdued, false, false);
            lv_obj_set_style_text_align(at, LV_TEXT_ALIGN_RIGHT, 0);
        }
        if (read_count()) {
            box(16, 281, 208, 1, lcd_edge);
            label("长按上清通知 长按下键息屏", 19, 282, 202, 31, subdued, false, false);
        } else footer(count ? "确定查看  长按上清空" : "长按菜单  长按下键息屏");
    } else if (page == MENU) {
        header("功能菜单");
        /* Two-column launcher grid: ten entries stay visible without a long list. */
        for (int i = 0; i < MENU_COUNT; i++) {
            int x = 16 + (i % 2) * 106;
            int y = 70 + (i / 2) * 41;
            bool picked = i == menu_selected;
            box(x, y, 104, 36, picked ? ink : panel);
            /* The bitmap font's ink begins below the top of its line box. */
            lv_obj_t *item = label(menus[i], x, y + 1, 104, 31,
                                   picked ? lcd_background : ink, false, false);
            lv_obj_set_style_text_align(item, LV_TEXT_ALIGN_CENTER, 0);
        }
        footer("确定进入   上下选择");
    } else if (page == STATS) {
        header("今日统计");
        snprintf(text, sizeof(text), "%d", daily_count);
        label(text, 22, 101, 180, 40, ink, true, false);
        label("收到通知", 22, 149, 185, 22, ink, false, false);
        snprintf(text, sizeof(text), "静默 %d", daily_muted);
        label(text, 22, 185, 185, 22, subdued, false, false);
        footer("确定返回");
    } else if (page == DND) {
        header("免打扰时段");
        snprintf(text, sizeof(text), "%c 免打扰   %s", dnd_field == 0 ? '>' : ' ', dnd_enabled ? "开" : "关");
        label(text, 20, 91, 205, 24, ink, false, false);
        snprintf(text, sizeof(text), "%c 开始   %02d:00", dnd_field == 1 ? '>' : ' ', dnd_start);
        label(text, 20, 131, 205, 24, ink, false, false);
        snprintf(text, sizeof(text), "%c 结束   %02d:00", dnd_field == 2 ? '>' : ' ', dnd_end);
        label(text, 20, 171, 205, 24, ink, false, false);
        footer("确定切换项   上下修改");
    } else if (page == APP_ALERTS) {
        const char *list[MAX_MESSAGES + MAX_APP_RULES];
        int size = app_candidates(list);
        header("应用提醒");
        if (!size) label("收到通知后可选应用", 16, 104, 209, 31, subdued, false, false);
        else {
            int index = app_choice_index(list, size);
            const char *app = list[index];
            snprintf(text, sizeof(text), "%d / %d", index + 1, size);
            label(text, 18, 78, 198, 31, subdued, false, false);
            lv_obj_t *name = label(app, 18, 106, 204, 48, ink, false, true);
            lv_label_set_long_mode(name, LV_LABEL_LONG_DOT);
            box(14, 166, 212, 41, panel);
            label(app_mode_names[app_mode(app)], 22, 170, 194, 31, ink, false, false);
            if (app_rule_status) label(app_rule_status, 18, 217, 202, 31, bright, false, false);
        }
        footer(size ? "上下选应用   确定切换" : "长按确定返回");
    } else if (page == PAIRING) {
        unsigned remaining = pager_pair_seconds();
        bool connected = pager_connected();
        header("蓝牙配对设置");
        box(14, 76, 212, 103, panel);
        label("Passport BB", 22, 78, 193, 31, ink, false, false);
        label(connected ? "手机已连接" : "手机未连接", 22, 104, 192, 31,
              connected ? ink : subdued, false, false);
        if (remaining) snprintf(text, sizeof(text), "配对开放  %02u:%02u", remaining / 60, remaining % 60);
        else strlcpy(text, "配对已关闭", sizeof(text));
        label(text, 22, 137, 193, 31, bright, false, false);
        label("安卓须在伴侣 App 内连接", 18, 181, 205, 31, bright, false, false);
        label(connected ? "换手机前先断开" : "弹出系统提示后点配对", 18, 207, 205, 31, subdued, false, false);
        label("iPhone 从系统蓝牙连接", 18, 237, 205, 31, ink, false, false);
        footer(remaining ? "确定键关闭配对" : "确定键开启配对");
    } else if (page == MEDIA) {
        header("媒体控制");
        bool available = pager_locator_ready();
        box(14, 88, 212, 152, panel);
        label(available ? "手机正在播放的音乐" : "手机未连接", 22, 104, 196, 31,
              available ? ink : bright, false, false);
        const char *state = media_status ? media_status : "上下键切换歌曲";
        label(state, 22, 143, 196, 31, media_until ? bright : subdued, false, false);
        label("确定键播放或暂停", 22, 181, 196, 31, ink, false, false);
        footer(available ? "上下切歌   长按返回" : "长按确定返回");
    } else if (page == FIND_DEVICE) {
        header("查找设备");
        bool available = pager_locator_ready();
        label("查找安卓手机", 20, 92, 200, 31, ink, false, false);
        const char *hint = phone_ring_failed ? "发送失败，请重试" : phone_ring_until ? "已发出响铃请求" :
                           available ? "手机已连接，可响铃" : "请连接新版安卓伴侣";
        label(hint, 20, 137, 200, 31, available ? subdued : bright, false, false);
        footer(available ? (phone_ring_until ? "确定停止   长按返回" : "确定响铃   长按返回") : "长按确定返回");
    } else if (page == DEVICE_SETTINGS) {
        static const char *names[] = {"自动息屏", "提示音音量", "屏幕亮度"};
        char values[3][16];
        if (sleep_choices[sleep_choice]) snprintf(values[0], sizeof(values[0]), "%u 秒", sleep_choices[sleep_choice]);
        else strlcpy(values[0], "关闭", sizeof(values[0]));
        if (volume) snprintf(values[1], sizeof(values[1]), "%d%%", volume);
        else strlcpy(values[1], "静音", sizeof(values[1]));
        snprintf(values[2], sizeof(values[2]), "%d%%", brightness);
        header("设备设置");
        for (int i = 0; i < 3; i++) {
            int y = 81 + i * 52;
            uint32_t color = settings_field == i ? bright : ink;
            box(14, y, 212, 43, panel);
            if (settings_field == i) box(14, y + 5, 2, 33, bright);
            label(names[i], 23, y + 2, 120, 31, color, false, false);
            lv_obj_t *value = label(values[i], 148, y + 2, 65, 31, color, false, false);
            lv_obj_set_style_text_align(value, LV_TEXT_ALIGN_RIGHT, 0);
        }
        footer("确定切换项   上下修改");
    } else if (page == CLOCK_SETTINGS) {
        header("息屏时钟设置");
        label(clock_screen ? "低亮时钟已开启" : "低亮时钟已关闭", 22, 101, 196, 31, clock_screen ? ink : subdued, false, false);
        label(clock_screen ? "闲置后显示低亮时钟" : "闲置后关闭屏幕背光", 22, 143, 196, 31, subdued, false, false);
        footer("确定切换   长按返回");
    } else if (page == CLOCK) {
        header("息屏时钟");
        time_text(t, sizeof(t), time(NULL), "%H:%M");
        lcd_digits(t, 124, 5);
        footer("按任意键唤醒");
    } else {
        bool pom = page == POMODORO;
        header(pom ? "番茄钟  /  25 分钟" : "秒表");
        int64_t value = pom ? pom_remaining - (pom_running ? ms() - pom_start : 0)
                            : sw_elapsed + (sw_running ? ms() - sw_start : 0);
        duration(t, sizeof(t), value > 0 ? value : 0);
        lcd_digits(t, 123, 3);
        bool running = pom ? pom_running : sw_running;
        label(running ? "计时中" : "已暂停", 25, 173, 182, 31, running ? bright : subdued, false, false);
        footer(running ? "确定暂停   下键重置" : "确定开始   下键重置");
    }
}

/* One NVS record keeps device and phone edits atomic across power loss. */
static uint32_t pack_preferences(void)
{
    return (uint32_t)sleep_choice | (uint32_t)volume << 3 | (uint32_t)brightness << 10 |
           (uint32_t)dnd_enabled << 17 | (uint32_t)clock_screen << 18 |
           (uint32_t)dnd_start << 19 | (uint32_t)dnd_end << 24;
}

static void unpack_preferences(uint32_t value)
{
    sleep_choice = value & 7;
    volume = (value >> 3) & 127;
    brightness = (value >> 10) & 127;
    dnd_enabled = (value >> 17) & 1;
    clock_screen = (value >> 18) & 1;
    dnd_start = (value >> 19) & 31;
    dnd_end = (value >> 24) & 31;
}

static bool save_preferences(void)
{
    esp_err_t result = nvs_set_u32(settings, "preferences", pack_preferences());
    if (result == ESP_OK) result = nvs_commit(settings);
    if (result != ESP_OK) {
        ESP_LOGW("pager", "Settings save failed: %s", esp_err_to_name(result));
        return false;
    }
    settings_revision++;
    return true;
}

static void handle_settings(const settings_request *request)
{
    const uint8_t *bytes = request->bytes;
    uint8_t result = 0;
    if (bytes[0] == 3) {
        uint16_t revision = bytes[2] | bytes[3] << 8;
        if (revision != settings_revision) result = 1;
        else if (bytes[4] >= SLEEP_CHOICES || bytes[5] > 100 || bytes[6] < 10 ||
                 bytes[6] > 100 || bytes[7] > 3 || bytes[8] > 23 || bytes[9] > 23) result = 2;
        else {
            uint32_t old = pack_preferences();
            sleep_choice = bytes[4];
            volume = bytes[5];
            brightness = bytes[6];
            dnd_enabled = bytes[7] & 1;
            clock_screen = bytes[7] & 2;
            dnd_start = bytes[8];
            dnd_end = bytes[9];
            if (!save_preferences()) {
                unpack_preferences(old);
                result = 3;
            } else {
                pager_tone_volume((uint8_t)volume);
                if (page == CLOCK && !clock_screen) {
                    page = awake_page;
                    screen_off = true;
                    bsp_display_backlight(0);
                } else if (!screen_off && page != CLOCK) bsp_display_backlight((uint8_t)brightness);
                ESP_LOGI("pager", "Remote preferences saved: sleep=%u volume=%d brightness=%d",
                         sleep_choices[sleep_choice], volume, brightness);
            }
        }
    }
    uint8_t snapshot[] = {2, bytes[1], result, settings_revision, settings_revision >> 8,
                          sleep_choice, volume, brightness, dnd_enabled | clock_screen << 1,
                          dnd_start, dnd_end};
    pager_send_settings(snapshot, sizeof(snapshot));
}

static void wake(void)
{
    screen_off = false;
    last_input = ms();
    bsp_display_backlight((uint8_t)brightness);
}

static void next_popup(void)
{
    int choice = -1;
    for (int i = count - 1; i >= 0; i--) {
        if (pop_waiting[i] && (choice < 0 || inbox[i].type > inbox[choice].type)) choice = i;
    }
    if (choice >= 0) {
        popup = inbox[choice];
        pop_waiting[choice] = false;
        alert = true;
        popup_opened = ms();
        wake();
        pager_tone_notify();
    }
}

static void dismiss_popup(void)
{
    alert = false;
    /* Keep queued notifications in the inbox without opening another popup. */
    memset(pop_waiting, 0, sizeof(pop_waiting));
}

static void check_day(void)
{
    if (!have_time()) return;
    struct tm stamp = local_at(time(NULL));
    int key = stamp.tm_year * 366 + stamp.tm_yday;
    if (key != daily_day) {
        if (daily_day != -1) daily_count = daily_muted = 0;
        daily_day = key;
    }
}

static void erase_message(int index)
{
    bool removed_detail = page == DETAILS && selected == index;
    memmove(inbox + index, inbox + index + 1, (count-index-1) * sizeof(inbox[0]));
    memmove(pop_waiting + index, pop_waiting + index + 1, (count-index-1) * sizeof(pop_waiting[0]));
    count--;
    if (selected > index || (selected == count && selected > 0)) selected--;
    if (removed_detail) {
        page = INBOX;
        body_page = 0;
    }
}
static void remove_legacy(uint32_t id, bool clear)
{
    for (int i = count - 1; i >= 0; i--)
        if (inbox[i].legacy && (clear || inbox[i].id == id)) erase_message(i);
}

static void accept(pager_message msg)
{
    if (!have_time() && (msg.apple || msg.legacy) && msg.time >= 1700000000) {
        struct timeval tv = {.tv_sec = msg.time};
        settimeofday(&tv, NULL);
    }
    if (msg.time < 1700000000) msg.time = time(NULL);
    bool updated = false, unread = true, was_waiting = false;
    if (!msg.apple && !msg.legacy) msg.id = ++serial;
    else {
        for (int i = 0; i < count; i++) {
            if (inbox[i].apple == msg.apple && inbox[i].legacy == msg.legacy &&
                inbox[i].id == msg.id && !strcmp(inbox[i].app, msg.app)) {
                updated = true;
                unread = inbox[i].unread;
                was_waiting = pop_waiting[i];
                if (alert && popup.apple == msg.apple && popup.legacy == msg.legacy &&
                    popup.id == msg.id && !strcmp(popup.app, msg.app)) popup = msg;
                erase_message(i);
                break;
            }
        }
    }
    bool in_menu = page != INBOX && page != DETAILS && page != CLOCK;
    bool menu_quiet = !screen_off && in_menu && !msg.type;
    bool quiet = msg.silent || menu_quiet || (delete_prompt != DELETE_NONE && !msg.type);
    int hour = local_at(time(NULL)).tm_hour;
    if (dnd_enabled && have_time()) {
        quiet |= dnd_start == dnd_end || (dnd_start < dnd_end ?
            hour >= dnd_start && hour < dnd_end : hour >= dnd_start || hour < dnd_end);
    }
    quiet |= app_mode(msg.app) == APP_SILENT;
    check_day();
    struct tm when = local_at(msg.time), today = local_at(time(NULL));
    bool counted_today = !have_time() || (when.tm_year == today.tm_year && when.tm_yday == today.tm_yday);
    if (counted_today && !updated) {
        daily_count++;
        daily_muted += quiet;
    }
    int index = 0;
    while (index < count && inbox[index].time > msg.time) index++;
    if (index >= MAX_MESSAGES) return;
    if (count < MAX_MESSAGES) count++;
    memmove(inbox + index + 1, inbox + index, (count-index-1) * sizeof(inbox[0]));
    memmove(pop_waiting + index + 1, pop_waiting + index, (count-index-1) * sizeof(pop_waiting[0]));
    inbox[index] = msg;
    inbox[index].unread = unread;
    if (menu_quiet && !updated) ESP_LOGI("pager", "Notification stored without interrupting the menu");
    pop_waiting[index] = !quiet && (!updated || was_waiting);
    if (screen_off && pop_waiting[index]) wake();
    if (page == INBOX) selected = 0;
    else if (selected >= index && selected < count - 1) selected++;
    if (!alert) next_popup();
    else if (pop_waiting[index] && msg.type && !popup.type) {
        for (int i = 0; i < count; i++) {
            if (inbox[i].id == popup.id && inbox[i].apple == popup.apple &&
                inbox[i].legacy == popup.legacy && !strcmp(inbox[i].app, popup.app)) pop_waiting[i] = true;
        }
        alert = false;
        next_popup();
    }
}

static void input(button_input event)
{
    /* The driver reports a click only after release plus the window it reserves for
       a possible second tap, which this pager never uses. The navigation keys act
       on the press edge instead and the click that follows is dropped, so the cursor
       moves while the finger is still down. Confirm keeps click semantics so that a
       long press still reaches the page-level long actions. */
    if (event.event == BSP_BTN_PRESS) {
        if (event.key == BSP_BTN_OK) return;
        event.event = BSP_BTN_CLICK;
        press_used = true;
    } else if (event.event == BSP_BTN_CLICK) {
        bool consumed = press_used;
        press_used = false;
        if (consumed && event.key != BSP_BTN_OK) return;
    } else if (event.event == BSP_BTN_DOUBLE) return;
    bool ok = event.key == BSP_BTN_OK;
    int step = event.key == BSP_BTN_UP ? -1 : 1;
    if (locator_until) {
        locator_until = 0;
        pager_locator_tone(false);
        wake();
        return;
    }
    bool slept = screen_off || (page == CLOCK && !alert);
    if (alert && !screen_off) {
        wake();
        dismiss_popup();
        if (page == CLOCK) page = awake_page;
        ESP_LOGI("pager", "Notification dismissed");
        return;
    }
    if (!screen_off && event.key == BSP_BTN_DOWN && event.event == BSP_BTN_LONG) {
        if (page == CLOCK) page = awake_page;
        screen_off = true;
        bsp_display_backlight(0);
        ESP_LOGI("pager", "Display blanked by DOWN long press");
        return;
    }
    wake();
    if (slept) {
        if (page == CLOCK) page = awake_page;
        if (page == DETAILS && !count) page = INBOX;
        ESP_LOGI("pager", "Display woken by function button");
        return;
    }
    if (delete_prompt != DELETE_NONE) {
        if (event.event == BSP_BTN_LONG) {
            delete_prompt = DELETE_NONE;
            return;
        }
        if (event.event == BSP_BTN_CLICK) {
            if (ok) {
                if (delete_prompt == DELETE_ONE) {
                    int index = delete_target_index();
                    if (index >= 0) erase_message(index);
                } else if (delete_prompt == DELETE_ALL) {
                    for (int i = count - 1; i >= 0; i--) erase_message(i);
                } else {
                    for (int i = count - 1; i >= 0; i--)
                        if (!inbox[i].unread) erase_message(i);
                }
                page = INBOX;
                ESP_LOGI("pager", "Notification clear confirmed");
                delete_prompt = DELETE_NONE;
            } else if (delete_prompt == DELETE_ONE) {
                delete_prompt = DELETE_NONE;
            } else if (read_count() && count > read_count()) {
                /* Up/Down swaps the two clear scopes while the confirmation stays open. */
                delete_prompt = delete_prompt == DELETE_READ ? DELETE_ALL : DELETE_READ;
            }
        }
        return;
    }
    if (event.event == BSP_BTN_LONG && event.key == BSP_BTN_UP) {
        if (page == DETAILS && count) {
            const pager_message *msg = &inbox[selected];
            delete_target.id = msg->id;
            delete_target.apple = msg->apple;
            delete_target.legacy = msg->legacy;
            strlcpy(delete_target.app, msg->app, sizeof(delete_target.app));
            delete_prompt = DELETE_ONE;
            ESP_LOGI("pager", "Single notification clear requested");
        } else if (page == INBOX && count) {
            /* Long-press Up opens the clear prompt; it defaults to wiping the whole
               inbox and Up/Down switches to read-only if both kinds are present. */
            delete_prompt = DELETE_ALL;
            ESP_LOGI("pager", "Clear prompt opened");
        }
        return;
    }
    if (event.event == BSP_BTN_LONG) {
        if (ok) {
            page = page == MENU ? INBOX : MENU;
            if (page == MENU) ESP_LOGI("pager", "Function menu opened");
        }
        return;
    }
    if (page == INBOX) {
        if (!count) return;
        if (ok) {
            body_page = 0;
            inbox[selected].unread = false;
            page = DETAILS;
        } else selected = (selected + step + count) % count;
    } else if (page == DETAILS) {
        if (ok) page = INBOX;
        else if (step > 0 && details_has_next) body_page++;
        else if (step < 0 && body_page) body_page--;
    } else if (page == MENU) {
        if (!ok) menu_selected = (menu_selected + step + MENU_COUNT) % MENU_COUNT;
        else {
            page = pages[menu_selected];
            if (page == APP_ALERTS) { selected_app[0] = 0; app_rule_status = NULL; }
            if (page == DEVICE_SETTINGS) settings_field = 0;
        }
    } else if (page == MEDIA) {
        if (pager_locator_ready()) {
            bool sent;
            if (ok) { sent = pager_media(2); media_status = "已发送 播放/暂停"; }
            else if (step > 0) { sent = pager_media(1); media_status = "已发送 下一首"; }
            else { sent = pager_media(0); media_status = "已发送 上一首"; }
            if (!sent) media_status = "发送失败，请重试";
            media_until = ms() + 2500;
        } else {
            media_status = "请先连接手机";
            media_until = ms() + 2500;
        }
    } else if (page == FIND_DEVICE) {
        if (ok && pager_locator_ready()) {
            bool start = !phone_ring_until;
            phone_ring_failed = !pager_find_phone(start);
            if (!phone_ring_failed) phone_ring_until = start ? ms() + 10000 : 0;
        }
    } else if (page == DEVICE_SETTINGS) {
        if (ok) settings_field = (settings_field + 1) % 3;
        else {
            int increase = -step;
            if (settings_field == 0) {
                sleep_choice = (sleep_choice + increase + SLEEP_CHOICES) % SLEEP_CHOICES;
            } else if (settings_field == 1) {
                volume += increase * 5;
                if (volume < 0) volume = 0;
                if (volume > 100) volume = 100;
                pager_tone_volume((uint8_t)volume);
            } else {
                brightness += increase * 5;
                if (brightness < 10) brightness = 10;
                if (brightness > 100) brightness = 100;
                bsp_display_backlight((uint8_t)brightness);
            }
            save_preferences();
        }
    } else if (page == CLOCK_SETTINGS) {
        if (ok) {
            clock_screen = !clock_screen;
            save_preferences();
        }
    } else if (page == PAIRING) {
        if (ok) {
            if (pager_pair_seconds()) pager_pair_close();
            else pager_pair();
        }
    } else if (page == STATS) {
        if (ok) page = MENU;
    } else if (page == DND) {
        if (ok) dnd_field = (dnd_field + 1) % 3;
        else {
            if (dnd_field == 0) dnd_enabled = !dnd_enabled;
            else if (dnd_field == 1) dnd_start = (dnd_start + 24 + step) % 24;
            else dnd_end = (dnd_end + 24 + step) % 24;
            save_preferences();
        }
    } else if (page == APP_ALERTS) {
        const char *list[MAX_MESSAGES + MAX_APP_RULES];
        int size = app_candidates(list);
        if (!size) return;
        app_rule_status = NULL;
        int index = app_choice_index(list, size);
        if (!ok) index = (index + step + size) % size;
        strlcpy(selected_app, list[index], sizeof(selected_app));
        if (ok) cycle_app_mode(selected_app);
    } else if (page == STOPWATCH) {
        if (ok) {
            if (sw_running) sw_elapsed += ms() - sw_start;
            else sw_start = ms();
            sw_running = !sw_running;
        } else if (step > 0) { sw_running = false; sw_elapsed = 0; }
    } else if (page == POMODORO) {
        if (ok) {
            if (pom_running) pom_remaining -= ms() - pom_start;
            else pom_start = ms();
            pom_running = !pom_running;
        } else if (step > 0) { pom_running = false; pom_remaining = 1500000; }
    }
}

void app_main(void)
{
    ESP_ERROR_CHECK(nvs_flash_init());
    ESP_ERROR_CHECK(nvs_open("pager", NVS_READWRITE, &settings));
    size_t size = sizeof(app_rules);
    esp_err_t rules = nvs_get_blob(settings, "app_alerts", app_rules, &size);
    if (rules == ESP_ERR_NVS_NOT_FOUND) {
        char old_mutes[MAX_APP_RULES][sizeof(inbox[0].app)] = {0};
        size = sizeof(old_mutes);
        if (nvs_get_blob(settings, "mutes", old_mutes, &size) == ESP_OK) {
            for (int i = 0; i < MAX_APP_RULES; i++) {
                old_mutes[i][sizeof(old_mutes[i]) - 1] = 0;
                if (!old_mutes[i][0]) continue;
                strlcpy(app_rules[i].app, old_mutes[i], sizeof(app_rules[i].app));
                app_rules[i].mode = APP_SILENT;
            }
        }
    }
    for (int i = 0; i < MAX_APP_RULES; i++) {
        app_rules[i].app[sizeof(app_rules[i].app) - 1] = 0;
        if (app_rules[i].mode != APP_STICKY && app_rules[i].mode != APP_SILENT)
            app_rules[i].app[0] = 0;
    }
    uint32_t options;
    if (nvs_get_u32(settings, "options", &options) == ESP_OK) {
        dnd_enabled = options & 1;
        clock_screen = options & 2;
        dnd_start = (options >> 8) & 0xff;
        dnd_end = (options >> 16) & 0xff;
    }
    uint32_t device_ui;
    if (nvs_get_u32(settings, "device_ui", &device_ui) == ESP_OK) {
        unsigned preset = device_ui & 0xff;
        unsigned saved_volume = (device_ui >> 8) & 0xff;
        unsigned saved_brightness = (device_ui >> 16) & 0xff;
        if (preset < SLEEP_CHOICES) sleep_choice = (int)preset;
        if (saved_volume <= 100) volume = (int)saved_volume;
        if (saved_brightness >= 10 && saved_brightness <= 100) brightness = (int)saved_brightness;
    }
    uint32_t preferences;
    if (nvs_get_u32(settings, "preferences", &preferences) == ESP_OK) {
        uint32_t previous = pack_preferences();
        unpack_preferences(preferences);
        if (sleep_choice >= SLEEP_CHOICES || volume > 100 || brightness < 10 ||
            brightness > 100 || dnd_start > 23 || dnd_end > 23) unpack_preferences(previous);
    }
    pager_tone_volume((uint8_t)volume);
    messages = xQueueCreate(16, sizeof(inbox_event));
    buttons = xQueueCreate(12, sizeof(button_input));
    clocks = xQueueCreate(1, sizeof(clock_update));
    locator_events = xQueueCreate(1, sizeof(bool));
    settings_requests = xQueueCreate(4, sizeof(settings_request));
    if (!messages || !buttons || !clocks || !locator_events || !settings_requests) abort();
    bool battery_ready = bsp_battery_init() == ESP_OK;
    if (battery_ready) battery_soc = bsp_battery_soc();
    ESP_LOGI("pager", "Battery gauge ready=%d SOC=%d", battery_ready, battery_soc);
    ESP_ERROR_CHECK(bsp_display_init());
    lv_display_t *display = bsp_lvgl_init();
    if (!display) abort();
    if (!bsp_lvgl_lock(500)) abort();
    root = lv_screen_active();
    extra_font = pager_extra;
    extra_font.fallback = &pager_emoji;
    display_font = pager_cjk;
    display_font.fallback = &extra_font;
    pager_screenshot_start(display);
    lv_obj_set_style_bg_color(root, lv_color_hex(lcd_background), 0);
    lv_obj_remove_flag(root, LV_OBJ_FLAG_SCROLLABLE);
    render();
    bsp_lvgl_unlock();
    ESP_ERROR_CHECK(bsp_button_init(button, NULL));
    wake();
    pager_tone_start();
    pager_ble_start();
    int64_t last_render = -1000;
    int64_t next_battery_read = ms() + 15000;
    bool connected = false;
    bool dirty = true;
    while (true) {
        clock_update clock;
        inbox_event message;
        button_input key;
        if (xQueueReceive(clocks, &clock, 0)) {
            struct timeval tv = {.tv_sec = clock.utc};
            settimeofday(&tv, NULL);
            zone_minutes = clock.zone;
            dirty = true;
        }
        settings_request request;
        while (xQueueReceive(settings_requests, &request, 0)) {
            if (pager_locator_ready()) handle_settings(&request);
            dirty = true;
        }
        bool find;
        if (xQueueReceive(locator_events, &find, 0)) {
            locator_until = find ? ms() + 10000 : 0;
            pager_locator_tone(find);
            if (find) wake();
            dirty = true;
        }
        if (locator_until && ms() >= locator_until) {
            locator_until = 0;
            pager_locator_tone(false);
            dirty = true;
        }
        if (phone_ring_until && (ms() >= phone_ring_until || !pager_locator_ready())) {
            phone_ring_until = 0;
            dirty = true;
        }
        while (xQueueReceive(messages, &message, 0)) {
            if (message.remove) remove_legacy(message.msg.id, message.clear);
            else accept(message.msg);
            dirty = true;
        }
        while (xQueueReceive(buttons, &key, 0)) {input(key); dirty = true;}
        int previous_day = daily_day;
        check_day();
        if (daily_day != previous_day) dirty = true;
        if (battery_ready && ms() >= next_battery_read) {
            next_battery_read = ms() + 15000;
            int reading = bsp_battery_soc();
            if (reading != battery_soc) {
                battery_soc = reading;
                dirty = true;
            }
        }
        if (connected != pager_connected()) {connected = pager_connected(); dirty = true;}
        if (pom_running && ms() - pom_start >= pom_remaining) {
            pom_running = false;
            pom_remaining = 1500000;
            accept((pager_message){.app = "番茄钟", .title = "专注时间结束", .type = 2});
            dirty = true;
        }
        if (alert && !popup.type && app_mode(popup.app) != APP_STICKY &&
            ms() - popup_opened >= POPUP_TIMEOUT_MS) {
            dismiss_popup();
            if (page == CLOCK) bsp_display_backlight(8);
            ESP_LOGI("pager", "Notification auto-dismissed");
            dirty = true;
        }
        if (page == PAIRING && pager_pair_seconds()) last_input = ms();
        if (!alert && !locator_until && !screen_off && page != CLOCK && sleep_choices[sleep_choice] &&
            ms() - last_input >= (int64_t)sleep_choices[sleep_choice] * 1000) {
            if (clock_screen) {awake_page = page; page = CLOCK; bsp_display_backlight(8);}
            else {screen_off = true; bsp_display_backlight(0);}
            dirty = true;
        }
        if (!screen_off && ((page == CLOCK || page == STOPWATCH || page == POMODORO || page == INBOX || page == PAIRING || page == FIND_DEVICE) &&
            !alert && ms() - last_render >= (page == INBOX || page == CLOCK ? 10000 : 1000))) dirty = true;
        if (dirty && bsp_lvgl_lock(100)) {
            render();
            bsp_lvgl_unlock();
            last_render = ms();
            dirty = false;
        }
        vTaskDelay(pdMS_TO_TICKS(25));
    }
}
