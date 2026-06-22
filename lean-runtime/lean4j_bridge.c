// lean4j_bridge.c - Thin C wrappers for inline/undeclared Lean runtime functions
// These make static-inline and internal Lean functions callable from Java FFM API.
#include <lean/lean.h>

// Forward declarations for internal runtime functions (not in public headers)
void lean_initialize_runtime_module(void);
void lean_io_mark_end_initialization(void);
void lean_init_task_manager(void);

// Forward declaration for generated module initializer
lean_object* initialize_Lean4J(uint8_t builtin);

// --- Lifecycle ---

// Call once before any Lean function. Order: lean4j_initialize → initialize_Lean4J →
// lean4j_mark_end_init → (optionally) lean4j_init_task_manager.
void lean4j_initialize(void) {
    lean_initialize_runtime_module();
}

lean_object* lean4j_init_module(uint8_t builtin) {
    return initialize_Lean4J(builtin);
}

void lean4j_mark_end_init(void) {
    lean_io_mark_end_initialization();
}

void lean4j_start_task_manager(void) {
    lean_init_task_manager();
}

// --- Reference counting ---

void lean4j_inc(lean_object* o) {
    lean_inc(o);
}

void lean4j_dec(lean_object* o) {
    lean_dec(o);
}

// --- String marshalling ---

// Returns null-terminated UTF-8. Owned by the lean_object; do NOT free.
// Caller must keep object alive (call lean4j_inc before, lean4j_dec after use).
const char* lean4j_string_cstr(lean_object* s) {
    return lean_string_cstr(s);
}

size_t lean4j_string_utf8_len(lean_object* s) {
    return lean_string_size(s) - 1; // lean_string_size includes null terminator
}

lean_object* lean4j_mk_string(const char* s) {
    return lean_mk_string(s);
}

// --- IO result helpers ---

int lean4j_io_result_is_ok(lean_object* r) {
    return lean_io_result_is_ok(r) ? 1 : 0;
}

lean_object* lean4j_io_result_get_value(lean_object* r) {
    return lean_io_result_get_value(r);
}

lean_object* lean4j_io_result_get_error(lean_object* r) {
    return lean_io_result_get_error(r);
}
