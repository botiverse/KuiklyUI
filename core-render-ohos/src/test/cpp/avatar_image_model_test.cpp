#include <cassert>
#include <iostream>
#include <string>

#include "libohos_render/expand/modules/cache/KRAvatarImageModel.h"

static std::string ValidDescriptor(const std::string &read_mode = "fetch") {
    const std::string partition(64, 'a');
    const std::string subject(64, 'b');
    const std::string bytes(64, 'c');
    return "{\"protocol\":\"slock_avatar_cache_v1\",\"managedSet\":\"typed_avatar_only\","
           "\"managedIndex\":\"opaque_partition_subject_bytes_v1\",\"budgetScope\":\"avatar_only\","
           "\"indexedRetirementRequired\":true,\"maxEntryBytes\":\"5242880\","
           "\"maxManagedBytes\":\"67108864\",\"remoteUrl\":\"https://example.invalid/avatar.png\","
           "\"nativePartitionKey\":\"" + partition + "\",\"nativeSubjectKey\":\"" + subject +
           "\",\"nativeBytesKey\":\"" + bytes + "\",\"readMode\":\"" + read_mode + "\","
           "\"commitGeneration\":\"1\",\"callerAuthority\":\"" + partition + "\"}";
}

int main() {
    const auto valid = KRParseAvatarImageModelJson(ValidDescriptor());
    assert(valid.typed && valid.valid);
    assert(valid.native_bytes_key == std::string(64, 'c'));
    assert(valid.commit_generation == "1");
    assert(valid.caller_authority == std::string(64, 'a'));
    assert(!KRAvatarMayUseDecodedHit(valid));
    assert(KRAvatarDecodedKeySuffix(valid) ==
           std::string(64, 'c') + ".g1.a" + std::string(64, 'a'));

    for (const std::string mode : {
             "render_fresh",
             "render_stale_revalidate",
             "render_stale_suppressed",
             "fetch",
             "suppressed",
         }) {
        assert(KRParseAvatarImageModelJson(ValidDescriptor(mode)).valid);
    }
    for (const std::string mode : {"", "unknown", "FETCH", " fetch", "fetch "}) {
        assert(!KRParseAvatarImageModelJson(ValidDescriptor(mode)).valid);
    }

    std::string uppercase = ValidDescriptor();
    uppercase.replace(uppercase.find(std::string(64, 'a')), 1, "A");
    assert(!KRParseAvatarImageModelJson(uppercase).valid);

    std::string wrong_budget = ValidDescriptor();
    wrong_budget.replace(wrong_budget.find("67108864"), 8, "67108865");
    assert(!KRParseAvatarImageModelJson(wrong_budget).valid);

    std::string wrong_mode = ValidDescriptor();
    wrong_mode.replace(wrong_mode.find("\"fetch\""), 7, "\"legacy\"");
    assert(!KRParseAvatarImageModelJson(wrong_mode).valid);
    std::string missing_caller = ValidDescriptor();
    const auto caller_start = missing_caller.find(",\"callerAuthority\"");
    missing_caller.erase(caller_start, missing_caller.size() - caller_start - 1);
    assert(!KRParseAvatarImageModelJson(missing_caller).valid);
    assert(!KRParseAvatarImageModelJson("{").valid);

    std::cout << "OHOS typed-avatar image-model contract test: PASS\n";
    return 0;
}
