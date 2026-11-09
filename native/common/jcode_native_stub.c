#ifndef QCODE_STUB_MODULE
#define QCODE_STUB_MODULE "unknown"
#endif

#if defined(__GNUC__)
#define QCODE_EXPORT __attribute__((visibility("default")))
#else
#define QCODE_EXPORT
#endif

QCODE_EXPORT const char* jcode_native_module_name(void) {
    return QCODE_STUB_MODULE;
}
