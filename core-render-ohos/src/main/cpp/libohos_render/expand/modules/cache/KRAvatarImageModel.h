/*
 * Tencent is pleased to support the open source community by making KuiklyUI
 * available.
 * Copyright (C) 2025 Tencent. All rights reserved.
 * Licensed under the License of KuiklyUI.
 */

#ifndef CORE_RENDER_OHOS_KRAVATARIMAGEMODEL_H
#define CORE_RENDER_OHOS_KRAVATARIMAGEMODEL_H

#include <string>

#include "thirdparty/cJSON/cJSON.h"

struct KRAvatarImageModel {
    bool typed = false;
    bool valid = false;
    std::string native_bytes_key;
    std::string commit_generation;
    std::string caller_authority;
};

inline bool KRIsOpaqueAvatarKey(const std::string &value) {
    if (value.size() != 64) return false;
    for (char ch : value) {
        if (!((ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f'))) return false;
    }
    return true;
}

inline bool KRIsPositiveAvatarGeneration(const std::string &value) {
    if (value.empty() || value.front() == '0') return false;
    for (char ch : value) {
        if (ch < '0' || ch > '9') return false;
    }
    return true;
}

inline bool KRJsonStringEquals(const cJSON *root, const char *key, const char *expected) {
    const cJSON *value = cJSON_GetObjectItemCaseSensitive(root, key);
    return cJSON_IsString(value) && value->valuestring != nullptr && std::string(value->valuestring) == expected;
}

inline std::string KRJsonString(const cJSON *root, const char *key) {
    const cJSON *value = cJSON_GetObjectItemCaseSensitive(root, key);
    return cJSON_IsString(value) && value->valuestring != nullptr ? value->valuestring : "";
}

inline bool KRAvatarMayUseDecodedHit(const KRAvatarImageModel &model) {
    // Every typed request must execute the ArkTS read-mode/generation authority first.
    return !model.valid;
}

inline std::string KRAvatarDecodedKeySuffix(const KRAvatarImageModel &model) {
    return model.native_bytes_key + ".g" + model.commit_generation + ".a" + model.caller_authority;
}

inline KRAvatarImageModel KRParseAvatarImageModelJson(const std::string &json) {
    KRAvatarImageModel model;
    model.typed = true;
    cJSON *root = cJSON_ParseWithLength(json.data(), json.size());
    if (root == nullptr) return model;

    const std::string remote_url = KRJsonString(root, "remoteUrl");
    const std::string partition_key = KRJsonString(root, "nativePartitionKey");
    const std::string subject_key = KRJsonString(root, "nativeSubjectKey");
    const std::string bytes_key = KRJsonString(root, "nativeBytesKey");
    const std::string generation = KRJsonString(root, "commitGeneration");
    const std::string caller_authority = KRJsonString(root, "callerAuthority");
    const std::string read_mode = KRJsonString(root, "readMode");
    const cJSON *indexed_retirement = cJSON_GetObjectItemCaseSensitive(root, "indexedRetirementRequired");

    const bool valid =
        KRJsonStringEquals(root, "protocol", "slock_avatar_cache_v1") &&
        KRJsonStringEquals(root, "managedSet", "typed_avatar_only") &&
        KRJsonStringEquals(root, "managedIndex", "opaque_partition_subject_bytes_v1") &&
        KRJsonStringEquals(root, "budgetScope", "avatar_only") &&
        KRJsonStringEquals(root, "maxEntryBytes", "5242880") &&
        KRJsonStringEquals(root, "maxManagedBytes", "67108864") &&
        cJSON_IsTrue(indexed_retirement) &&
        (remote_url.rfind("https://", 0) == 0 || remote_url.rfind("http://", 0) == 0) &&
        KRIsOpaqueAvatarKey(partition_key) &&
        KRIsOpaqueAvatarKey(subject_key) &&
        KRIsOpaqueAvatarKey(bytes_key) &&
        KRIsOpaqueAvatarKey(caller_authority) &&
        KRIsPositiveAvatarGeneration(generation) &&
        (read_mode == "render_fresh" || read_mode == "render_stale_revalidate" ||
         read_mode == "render_stale_suppressed" || read_mode == "fetch" || read_mode == "suppressed");

    if (valid) {
        model.valid = true;
        model.native_bytes_key = bytes_key;
        model.commit_generation = generation;
        model.caller_authority = caller_authority;
    }
    cJSON_Delete(root);
    return model;
}

#endif  // CORE_RENDER_OHOS_KRAVATARIMAGEMODEL_H
