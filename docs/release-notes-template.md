# Release Notes 模板

用于 GitHub Release、应用市场说明或仓库发行说明。发布时将占位内容替换为真实版本信息。

## 标题

```text
my-docs vX.Y.Z
```

## 摘要

`my-docs` 为 Halo 提供独立的文档 / 知识库系统，支持文档库、树形目录、Markdown 编辑、前台文档站点、搜索与 SEO 集成。

本版本为 `vX.Y.Z`，适用于 Halo `>= 2.25.0`。

## 本版重点

- 文档库管理：创建、编辑、删除文档库，并支持描述与封面
- 文档树管理：支持父子层级调整、拖拽排序与同级重排
- Markdown 编辑：支持附件库插入图片、文件和站内文档链接
- 前台文档站点：提供 `/docs`、`/docs/{librarySlug}`、`/docs/{librarySlug}/{docSlug}` 页面
- 文档阅读体验：支持侧边目录树、正文大纲、同库标题短链接跳转
- 文档渲染设置：支持为浅色 / 深色模式分别配置自定义内容 CSS、正文 class 与代码主题
- 数学公式：使用 RaTeX WASM + 透明 Canvas 渲染，公式颜色跟随正文主题
- 搜索与 SEO：已发布文档可被 Halo 搜索索引，并输出页面级 SEO 元信息

## 安装

1. 在 Release 附件中下载 `plugin-my-docs-vX.Y.Z.jar`。
2. 进入 Halo Console。
3. 打开「插件」页面并上传该 jar。
4. 安装并启用 `my-docs`。

## 升级

1. 备份当前 Halo 数据。
2. 在维护窗口内上传新版本 jar。
3. 启用后检查：
   - 文档库列表是否正常加载
   - 文档树拖拽是否正常
   - 文档详情页是否能正常渲染
   - 搜索是否能命中文档内容

## 兼容性

- Halo：`>= 2.25.0`
- Java：21+

## 已知限制

- 当前未集成文档评论能力
- 发布说明中的截图需与当前版本界面保持一致

## 截图建议

发布时建议附上以下截图，命名与拍摄要求见 [screenshot-guide.md](./screenshot-guide.md)：

- `console-libraries.webp`
- `console-tree.webp`
- `console-editor.webp`
- `console-settings.webp`
- `dashboard-widget.webp`
- `site-index.webp`
- `site-detail.webp`
