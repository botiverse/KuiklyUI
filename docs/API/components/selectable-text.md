# SelectableText(系统可选择纯文本)

只读纯文本组件，使用各平台**系统原生文本视图**渲染，因此长按/选择时出现的是系统选择菜单，菜单锚定在选区旁。

**能力边界（重要）**：本组件的基线保证是**选词、拖动选择柄、全选、复制**。其余菜单动作（如翻译、查询、分享；Android 上的 PROCESS_TEXT 目标是一个例子）**由系统按当前 OS 版本、语言区域与已安装服务决定是否出现**，属于平台附赠能力，不是本组件的承诺；不同平台、不同系统版本出现的项可能不同（例如 HarmonyOS 的 copyOption 只保证本机复制范围，不能由此推断出现翻译/分享）。

与 `Text`（自绘富文本）的区别：`SelectableText` 不支持富文本/Span，但换来系统原生的选择交互；它**永远只读**——不会弹出输入法，用户与程序都无法通过交互修改文本，文本只能通过 `text` 属性更新。

平台实现：

| 平台 | 实现 |
|:----|:----|
| Android | `TextView.setTextIsSelectable(true)`（系统 ActionMode 菜单） |
| iOS | `UITextView`，`editable=false`、`selectable=true`（系统 Edit Menu） |
| HarmonyOS | ArkUI `Text` 节点开启系统 copyOption=LOCAL_DEVICE（原生选择/复制菜单） |

滚动不内建：长文本请自行包裹在滚动容器中。

## 属性

支持所有[基础属性](basic-attr-event.md#基础属性)，以及：

<div class="table-01">

| 方法 | 描述 | 参数类型 |
|:----|:-------|:--|
| text | 文本内容 | String |
| color | 文字颜色 | Color / Long |
| fontSize | 字体大小 | Float |
| fontWeightNormal / fontWeightMedium / fontWeightSemiBold / fontWeightBold | 字重 | - |
| lineHeight | 行高 | Float |
| textAlignLeft / textAlignCenter / textAlignRight | 对齐 | - |
| useDpFontSizeDim | 字体大小使用 dp 单位（不跟随系统字体缩放） | Boolean |

</div>

:::tabs

@tab:active 示例

```kotlin
SelectableText {
    attr {
        text("可以长按选择并使用系统菜单的文本")
        fontSize(16f)
        color(Color.BLACK)
        lineHeight(24f)
    }
}
```

:::

## Compose API

```kotlin
SelectableText(
    text = message.body,
    modifier = Modifier.fillMaxWidth(),
    style = TextStyle(fontSize = 16.sp, lineHeight = 24.sp)
)
```

`style` 支持 color、fontSize、fontWeight、lineHeight、textAlign；其余字段在该最小 surface 上忽略。未指定的字段会解析为确定性默认值（黑色、15f、400、fontSize×4/3、left）——由于 Compose 节点可复用，每次更新都会主动写入全部字段，保证样式从「已指定」切回「默认」时旧值被重置。
