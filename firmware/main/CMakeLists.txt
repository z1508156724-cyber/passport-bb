idf_component_register(SRCS "pager_main.c" "pager_ble.c" "pager_wire.c" "pager_cjk.c" "pager_extra.c" "pager_emoji.c" "pager_screenshot.c" "pager_tone.c" INCLUDE_DIRS "."
    REQUIRES bsp bt esp_timer nvs_flash json esp_driver_usb_serial_jtag)

target_compile_definitions(${COMPONENT_LIB} PRIVATE LV_LVGL_H_INCLUDE_SIMPLE)
