# AGENTS.md — bytemain/KuiklyUI fork 协作约定

本仓库是 Tencent-TDS/KuiklyUI 的业务 fork，消费方为 botiverse/mobile（third_party/kuikly-ui gitlink）。以下约定来自 2026-07 divergence audit 与 fork 维护复盘（task #39），所有 human 与 agent 贡献者均须遵守。

## 铁律

1. **所有 KuiklyUI 变更合 `staging2`，并同步更新 mobile gitlink。** 不合 staging2 的修复等于没修；合 staging2 不更新 gitlink 的修复等于没交付（mobile main 分支保护，一律走 PR）。
2. **仓库禁用 merge commit，PR 一律 squash 合入**，squash message 保留来源信息（cherry-pick 吸收时列 upstream commit 清单）。

## staging2 只收终态

- 微观试错（padding/margin/几何微调）必须在任务分支内迭代完成，**staging2 只收最终形态**。2026-06~07 的 fork-only 历史里约 8% 是 fix+revert 净零对（如 067c6e07+9bb84eb1、20f9c2a4+8b9623e7），全是把 staging2 当试错分支造成的。
- PR 内多轮修改请 squash 或 force-push 任务分支，不要把 A/B 试探逐个合入。

## 吸收 upstream 前必先查

- **查在途/已合 PR**：修上游问题前，先查 Tencent-TDS/KuiklyUI 是否有在途或已合的等价修复（教训：zenipchen OHOS LazyColumn 系列在 fork 自研一遍，实际就是 upstream PR #1478，最后整体 revert，纯重复劳动）。
- **判断"已吸收"用文件内容，不用 patch-id**：批量 squash 吸收（如 bcacb669）会造成 `git cherry` 假阳性。判据是 `git diff origin/staging2 <upstream-commit>^ -- <files>` + 符号级 grep，不是 cherry 的 `+/-`。
- 吸收走 divergence audit 批次：upstream commit、影响面、吸收风险、回归点，回执 #Kuiklybase。

## 高试错热点必须带锁定测试

以下区域被 audit 判定为高试错密度，改动**必须**附带或更新锁定测试，否则不予合入：

- Android 行高居中（`HRLineHeightSpan`，现有 HRLineHeightSpanGlyphTest / HRLineHeightSpanTest）
- Android inline-code chip 几何（`KRRichTextViewDrawer` / `KRRichTextBuilder` —— 目前**缺** drawer 几何锁定测试，补测试是公开 backlog 项）
- compose lazy scroll echo / offset（`KuiklyScrollInfo` / `SubcomposeLayout`，两周 5+ 次迭代的区域）

## 与 fork 特性的冲突处理

fork-only 特性（如 native dispatch capture 家族 3509beef/95275204、slock inline-code chrome）与 upstream 修复撞同一区域时，**禁止机械覆盖**：必须由特性作者联合审查后手工合并，并配真机回归门（参考 #1508 OHOS 半边的处理）。
