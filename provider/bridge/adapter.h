// SPDX-License-Identifier: Apache-2.0
#pragma once
#include <stddef.h>
#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif
// Immutable configuration. Borrowed strings remain valid until destroy.
void* ahd_adapter_create(void);
void ahd_adapter_destroy(void* adapter);
size_t ahd_adapter_count(const void* adapter);
const char* ahd_adapter_id(const void* adapter, size_t index);
uint32_t ahd_adapter_max_concurrent(const void* adapter);
uint64_t ahd_adapter_max_pixel_rate(const void* adapter);
bool ahd_adapter_probe(const void* adapter, size_t index);
// Returns an AIBinder with ONE owned strong reference, or null on failure.
// The caller must adopt it using binder::unstable_api::new_spibinder.
void* ahd_adapter_device(const void* adapter, size_t index);
// Read-only camera metadata ABI decoding; does not configure hardware.
bool ahd_metadata_budget(const uint8_t* bytes, size_t size, uint64_t* pixels, uint32_t* fps);
#ifdef __cplusplus
}
#endif
