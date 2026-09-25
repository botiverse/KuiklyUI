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

#import "KRUIKit.h" // [macOS]
#import "KuiklyRenderViewExportProtocol.h"
NS_ASSUME_NONNULL_BEGIN

/*
 * @brief System-selectable read-only plain text.
 *
 * A UITextView with editable=NO / selectable=YES so the system edit menu
 * appears anchored to the selection. Baseline guarantee: Select All / Copy.
 * Further items (Look Up / Translate / Share, etc.) appear only when the OS
 * version, locale and installed services provide them. Never becomes an
 * input surface: no IME, no text mutation except through the "text" prop.
 */
@interface KRSelectableTextView : UITextView<KuiklyRenderViewExportProtocol>

@end

NS_ASSUME_NONNULL_END
