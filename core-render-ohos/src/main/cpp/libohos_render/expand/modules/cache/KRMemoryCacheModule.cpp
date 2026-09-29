/*
 * Tencent is pleased to support the open source community by making KuiklyUI
 * available.
 * Copyright (C) 2025 Tencent. All rights reserved.
 * Licensed under the License of KuiklyUI;
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://github.com/Tencent-TDS/KuiklyUI/blob/main/LICENSE
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "libohos_render/expand/modules/cache/KRMemoryCacheModule.h"
#include "libohos_render/expand/components/image/KRImageView.h"
#include "libohos_render/expand/modules/codec/KRCodec.h"
#include "libohos_render/expand/modules/network/KRNetworkModule.h"
#include "libohos_render/utils/KRURIHelper.h"
#include <cstdint>
#include <limits>
#include <multimedia/image_framework/image/image_source_native.h>
#include <multimedia/image_framework/image/pixelmap_native.h>
#include <new>
#include <shared_mutex>
#include <vector>

#ifdef __cplusplus
extern "C" {
#endif
// Remove this declaration if compatable api is raised to 18 and above
extern Image_ErrorCode OH_PixelmapNative_Destroy(OH_PixelmapNative **pixelmap) __attribute__((weak));
// Keep Kuikly loadable on its supported API 12-14 floor. The allocator-selecting
// decoder was added in API 15 and therefore must not become a strong BIND_NOW
// dependency of libkuikly.so.
extern Image_ErrorCode OH_ImageSourceNative_CreatePixelmapUsingAllocator(
    OH_ImageSourceNative *source, OH_DecodingOptions *options, IMAGE_ALLOCATOR_TYPE allocator,
    OH_PixelmapNative **pixelmap) __attribute__((weak));
#ifdef __cplusplus
};
#endif

constexpr char kMethodNameSetObject[] = "setObject";
constexpr char kMethodNameCacheImage[] = "cacheImage";
constexpr char kParamNameKey[] = "key";
constexpr char kParamNameValue[] = "value";
constexpr char kParamNameSrc[] = "src";
constexpr char kParamNameSync[] = "sync";
constexpr char kStatusKeyErrorCode[] = "errorCode";
constexpr char kStatusKeyErrorMsg[] = "errorMsg";
constexpr char kStatusKeyState[] = "state";
constexpr char kStatusKeyCacheKey[] = "cacheKey";
constexpr char kStatusKeyWidth[] = "width";
constexpr char kStatusKeyHeight[] = "height";
constexpr char kCacheStateComplete[] = "Complete";
constexpr char kCacheStateInProgress[] = "InProgress";
constexpr char kCacheKeyPrefix[] = "data:image_Md5_";

constexpr char kHttpPrefix[] = "http:";
constexpr char kHttpsPrefix[] = "https:";

static bool isNetwork(const std::string &src) {
    return src.compare(0, strlen(kHttpsPrefix), kHttpsPrefix) == 0 ||
           src.compare(0, strlen(kHttpPrefix), kHttpPrefix) == 0;
}

static bool isAssets(const std::string &src) { return src.compare(0, KR_ASSET_PREFIX.size(), KR_ASSET_PREFIX) == 0; }

static Image_ErrorCode ValidateCpuReadableRgbaPixelmap(OH_PixelmapNative *pixelmap) {
    if (pixelmap == nullptr) {
        return IMAGE_BAD_PARAMETER;
    }

    OH_Pixelmap_ImageInfo *info = nullptr;
    Image_ErrorCode code = OH_PixelmapImageInfo_Create(&info);
    if (code != IMAGE_SUCCESS || info == nullptr) {
        return code == IMAGE_SUCCESS ? IMAGE_BAD_PARAMETER : code;
    }

    uint32_t width = 0;
    uint32_t height = 0;
    uint32_t row_stride = 0;
    int32_t pixel_format = PIXEL_FORMAT_UNKNOWN;
    bool is_hdr = true;
    code = OH_PixelmapNative_GetImageInfo(pixelmap, info);
    if (code == IMAGE_SUCCESS) {
        code = OH_PixelmapImageInfo_GetWidth(info, &width);
    }
    if (code == IMAGE_SUCCESS) {
        code = OH_PixelmapImageInfo_GetHeight(info, &height);
    }
    if (code == IMAGE_SUCCESS) {
        code = OH_PixelmapImageInfo_GetRowStride(info, &row_stride);
    }
    if (code == IMAGE_SUCCESS) {
        code = OH_PixelmapImageInfo_GetPixelFormat(info, &pixel_format);
    }
    if (code == IMAGE_SUCCESS) {
        code = OH_PixelmapImageInfo_GetDynamicRange(info, &is_hdr);
    }
    OH_PixelmapImageInfo_Release(info);

    if (code != IMAGE_SUCCESS) {
        return code;
    }
    if (width == 0 || height == 0 || width > std::numeric_limits<uint32_t>::max() / 4) {
        return IMAGE_TOO_LARGE;
    }
    if (row_stride < width * 4 || pixel_format != PIXEL_FORMAT_RGBA_8888 || is_hdr) {
        return IMAGE_SOURCE_UNSUPPORTED_OPTIONS;
    }
    if (height > std::numeric_limits<size_t>::max() / row_stride) {
        return IMAGE_TOO_LARGE;
    }

    const size_t expected_byte_count = static_cast<size_t>(row_stride) * height;
    size_t byte_count = expected_byte_count;
    try {
        std::vector<uint8_t> pixels(expected_byte_count);
        code = OH_PixelmapNative_ReadPixels(pixelmap, pixels.data(), &byte_count);
        if (code == IMAGE_SUCCESS && byte_count != expected_byte_count) {
            code = IMAGE_UNKNOWN_ERROR;
        }
    } catch (const std::bad_alloc &) {
        code = IMAGE_ALLOC_FAILED;
    }
    return code;
}

static void ReleaseAndClearPixelmap(OH_PixelmapNative **pixelmap) {
    if (pixelmap != nullptr && *pixelmap != nullptr) {
        if (OH_PixelmapNative_Destroy != nullptr) {
            OH_PixelmapNative_Destroy(pixelmap);
        } else {
            OH_PixelmapNative_Release(*pixelmap);
            *pixelmap = nullptr;
        }
    }
}

static Image_ErrorCode CreateCanvasCompatiblePixelmap(OH_ImageSourceNative *source, OH_PixelmapNative **pixelmap) {
    if (source == nullptr || pixelmap == nullptr) {
        return IMAGE_BAD_PARAMETER;
    }
    *pixelmap = nullptr;

    OH_DecodingOptions *options = nullptr;
    Image_ErrorCode code = OH_DecodingOptions_Create(&options);
    if (code != IMAGE_SUCCESS) {
        return code;
    }
    if (options == nullptr) {
        return IMAGE_BAD_PARAMETER;
    }

    // Canvas reads the decoded pixels through OH_Drawing. Keeping HDR in AUTO lets
    // ImageSource select a hardware/surface-buffer PixelMap which is not reliably
    // readable by that path. Tone-map to SDR, request the format Canvas consumes,
    // and force shared memory so the resulting pixels are CPU-readable.
    code = OH_DecodingOptions_SetDesiredDynamicRange(options, IMAGE_DYNAMIC_RANGE_SDR);
    if (code == IMAGE_SUCCESS) {
        code = OH_DecodingOptions_SetPixelFormat(options, PIXEL_FORMAT_RGBA_8888);
    }
    if (code == IMAGE_SUCCESS) {
        if (OH_ImageSourceNative_CreatePixelmapUsingAllocator != nullptr) {
            code = OH_ImageSourceNative_CreatePixelmapUsingAllocator(
                source, options, IMAGE_ALLOCATOR_TYPE_SHARE_MEMORY, pixelmap);
            if (code == IMAGE_SUCCESS) {
                // Some platform decoders report success while still returning a
                // surface-buffer PixelMap. Treat the requested allocator as a
                // preference, not proof that Canvas can read the result.
                code = ValidateCpuReadableRgbaPixelmap(*pixelmap);
            }
            if (code != IMAGE_SUCCESS) {
                ReleaseAndClearPixelmap(pixelmap);
                KR_LOG_INFO_WITH_TAG(kMemoryCacheModuleName)
                    << "shared-memory decode was not Canvas-readable; retrying validated legacy decode, error code: "
                    << code;
            }
        }

        if (OH_ImageSourceNative_CreatePixelmapUsingAllocator == nullptr || code != IMAGE_SUCCESS) {
            // API 12-14 have no allocator-selecting decoder. API 15+ also uses
            // this retry when the allocator call succeeds but its actual result
            // fails the CPU-readable contract. The same SDR/RGBA options remain
            // active, and the fallback is admitted only after full validation;
            // this never restores the old unvalidated HDR AUTO path.
            code = OH_ImageSourceNative_CreatePixelmap(source, options, pixelmap);
            if (code == IMAGE_SUCCESS) {
                code = ValidateCpuReadableRgbaPixelmap(*pixelmap);
            }
        }
    }

    OH_DecodingOptions_Release(options);
    if (code != IMAGE_SUCCESS) {
        ReleaseAndClearPixelmap(pixelmap);
    }
    return code;
}

KRAnyValue KRMemoryCacheModule::Get(const std::string &key) {
    auto it = cache_map_.find(key);
    if (it == cache_map_.end()) {
        return KREmptyValue();
    } else {
        return it->second;
    }
}

OH_PixelmapNative *KRMemoryCacheModule::GetImage(const std::string &key) {
    std::shared_lock<std::shared_mutex> lock(mtx_);
    auto it = image_cache_map_.find(key);
    if (it == image_cache_map_.end()) {
        return nullptr;
    } else {
        return it->second;
    }
}

KRAnyValue KRMemoryCacheModule::CallMethod(bool sync, const std::string &method, KRAnyValue params,
                                           const KRRenderCallback &callback) {
    if (std::strcmp(method.c_str(), kMethodNameSetObject) == 0) {
        return SetObject(params);
    } else if (std::strcmp(method.c_str(), kMethodNameCacheImage) == 0) {
        return CacheImage(params, callback);
    } else {
        return KREmptyValue();
    }
}

KRAnyValue KRMemoryCacheModule::SetObject(const KRAnyValue &params) {
    auto map = params->toMap();
    auto key = map[kParamNameKey]->toString();
    auto value = map[kParamNameValue];
    cache_map_[key] = value;

    bool found = false;
    {
        std::shared_lock<std::shared_mutex> lock(mtx_);
        found = image_cache_map_.find(key) != image_cache_map_.end();
    }
    if (found) {
        OH_PixelmapNative *pixelmap = nullptr;
        {
            std::unique_lock<std::shared_mutex> lock(mtx_);
            auto it = image_cache_map_.find(key);
            if (it != image_cache_map_.end()) {
                pixelmap = it->second;
                image_cache_map_.erase(it);
            }
        }
        if (pixelmap) {
            ReleasePixelmap(pixelmap);
        }
    }

    return KREmptyValue();
}

OH_PixelmapNative *KRMemoryCacheModule::LoadPixelmapFromLocal(std::string &src) {
    OH_PixelmapNative *pixelmap = nullptr;
    OH_ImageSourceNative *source = nullptr;
    auto code = OH_ImageSourceNative_CreateFromUri(src.data(), src.length(), &source);
    if (code == IMAGE_SUCCESS) {
        code = CreateCanvasCompatiblePixelmap(source, &pixelmap);
        if (code != IMAGE_SUCCESS) {
            KR_LOG_ERROR_WITH_TAG(kMemoryCacheModuleName)
                << "failed to create Canvas-compatible SDR pixelmap, error code: " << code;
        }
        OH_ImageSourceNative_Release(source);
    } else {
        KR_LOG_ERROR_WITH_TAG(kMemoryCacheModuleName) << "failed to create image source from uri: " << src
                                                      << ", error code: " << code;
    }
    return pixelmap;
}

KRAnyValue KRMemoryCacheModule::CacheImage(const KRAnyValue &params, const KRRenderCallback &callback) {
    auto map = params->toMap();
    auto src = map[kParamNameSrc]->toString();
    auto cache_key = GenerateCacheKey(src);

    OH_PixelmapNative *pixelmap = GetImage(cache_key);
    if (pixelmap) {
        // already cached, return directly
        auto result = GenerateResult(cache_key, pixelmap);
        if (callback) {
            callback(NewKRRenderValue(result));
        }
        return NewKRRenderValue(std::move(result));
    }

    if (isAssets(src)) {
        // 获取资源文件目录
        const auto &rootView = GetRootView().lock();
        if (rootView) {
            std::string resfileDir = rootView->GetContext()->Config()->GetResfileDir();
            if (!resfileDir.empty()) {
                src = resfileDir + "/" + src.substr(KR_ASSET_PREFIX.size());
            }
        }
    }
    if (!isNetwork(src)) {
        pixelmap = LoadPixelmapFromLocal(src);
        if (pixelmap) {
            SetImage(cache_key, pixelmap);
            auto result = GenerateResult(cache_key, pixelmap);
            if (callback) {
                callback(NewKRRenderValue(result));
            }
            return NewKRRenderValue(std::move(result));
        } else {
            KRRenderValueMap result = GenerateError(-1, "failed to load image from local: invalid src");
            if (callback) {
                callback(NewKRRenderValue(result));
            }
            return NewKRRenderValue(std::move(result));
        }
    }

    // auto sync = map[kParamNameSync]->toBool(); // FIXME: sync mode is not supported yet

    const auto &rootView = GetRootView().lock();
    if (rootView) {
        auto network_module = std::dynamic_pointer_cast<KRNetworkModule>(rootView->GetModuleOrCreate(kNetworkModuleName));
        if (network_module) {
            std::weak_ptr<IKRRenderModuleExport> weak_self = shared_from_this();
            network_module->FetchFileByDownloadOrCache(src, [weak_self, cache_key, callback](KRAnyValue res) {
                KRMemoryCacheModule *module_self;
                if (auto self = weak_self.lock()) {
                    module_self = reinterpret_cast<KRMemoryCacheModule *>(self.get());
                } else {
                    return;
                }
                OH_PixelmapNative *pixelmap = nullptr;
                if (res && !res->isNull() && res->isString()) {
                    auto filePath = res->toString();
                    if (!filePath.empty()) {
                        pixelmap = module_self->LoadPixelmapFromLocal(filePath);
                    }
                }
                KRRenderValueMap result;
                if (pixelmap) {
                    module_self->SetImage(cache_key, pixelmap);
                    result = module_self->GenerateResult(cache_key, pixelmap);
                } else {
                    result = module_self->GenerateError(-1, "fetch failed");
                }
                if (callback) {
                    callback(NewKRRenderValue(result));
                }
            });
            KRRenderValueMap result;
            result[kStatusKeyState] = NewKRRenderValue(kCacheStateInProgress);
            result[kStatusKeyErrorCode] = NewKRRenderValue(0);
            result[kStatusKeyErrorMsg] = NewKRRenderValue("loading async");
            return NewKRRenderValue(result);
        }
    }
    KRRenderValueMap result = GenerateError(-1, "network module required");
    if (callback) {
        callback(NewKRRenderValue(result));
    }
    return NewKRRenderValue(std::move(result));
}

void KRMemoryCacheModule::SetImage(const std::string &cache_key, OH_PixelmapNative *pixelmap) {
    OH_PixelmapNative *exist_pixelmap = nullptr;
    {
        std::unique_lock<std::shared_mutex> lock(mtx_);
        auto it = image_cache_map_.find(cache_key);
        if (it != image_cache_map_.end()) {
            exist_pixelmap = it->second;
        }
        image_cache_map_[cache_key] = pixelmap;
    }
    if (exist_pixelmap) {
        ReleasePixelmap(exist_pixelmap);
    }
}

std::string KRMemoryCacheModule::GenerateCacheKey(const std::string &src) {
    std::ostringstream oss;
    oss << kCacheKeyPrefix;
    if (src.length() > 200) {
        oss << std::hash<std::string>{}(src);
    } else {
        oss << kuikly::KRBase64Encode(src);
    }
    return oss.str();
}

KRRenderValueMap KRMemoryCacheModule::GenerateResult(const std::string &cache_key, OH_PixelmapNative *pixelmap) {
    KRRenderValueMap result;
    result[kStatusKeyState] = NewKRRenderValue(kCacheStateComplete);
    result[kStatusKeyErrorCode] = NewKRRenderValue(0);
    result[kStatusKeyCacheKey] = NewKRRenderValue(cache_key);
    uint32_t width = 0;
    uint32_t height = 0;
    OH_Pixelmap_ImageInfo *info;
    if (OH_PixelmapImageInfo_Create(&info) == IMAGE_SUCCESS) {
        if (OH_PixelmapNative_GetImageInfo(pixelmap, info) == IMAGE_SUCCESS) {
            OH_PixelmapImageInfo_GetWidth(info, &width);
            OH_PixelmapImageInfo_GetHeight(info, &height);
        }
        OH_PixelmapImageInfo_Release(info);
    }
    result[kStatusKeyWidth] = NewKRRenderValue(static_cast<int32_t>(width));
    result[kStatusKeyHeight] = NewKRRenderValue(static_cast<int32_t>(height));
    return std::move(result);
}

KRRenderValueMap KRMemoryCacheModule::GenerateError(int32_t code, const std::string &message) {
    KRRenderValueMap result;
    result[kStatusKeyState] = NewKRRenderValue(kCacheStateComplete);
    result[kStatusKeyErrorCode] = NewKRRenderValue(code);
    result[kStatusKeyErrorMsg] = NewKRRenderValue(message);
    return std::move(result);
}

void KRMemoryCacheModule::OnDestroy() {
    std::vector<OH_PixelmapNative *> to_destroy;
    {
        std::unique_lock<std::shared_mutex> lock(mtx_);
        for (const auto &entry : image_cache_map_) {
            to_destroy.push_back(entry.second);
        }
        image_cache_map_.clear();
    }
    for (OH_PixelmapNative *pixelmap : to_destroy) {
        ReleasePixelmap(pixelmap);
    }
}

void KRMemoryCacheModule::ReleasePixelmap(OH_PixelmapNative *pixelmap) {
    ReleaseAndClearPixelmap(&pixelmap);
}
