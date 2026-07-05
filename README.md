# my-docs

一个为 [Halo](https://halo.run) 提供文档 / 知识库能力的插件。

`my-docs` 在 Halo 原有文章与页面模型之外，补充一套独立的文档系统，用来承载产品手册、开发文档、帮助中心这类需要树形目录和连续阅读体验的内容。

如果你需要开发、调试、构建或发布本插件，请查看 [docs/development.md](./docs/development.md)。

## 当前能力

- 文档库管理：创建、编辑、删除文档库，支持描述与封面
- 文档管理：按文档库维护文档，支持发布状态、slug、父子层级、排序权重
- 树形编排：在 Console 中拖拽调整目录树与同级顺序
- Markdown 编辑：使用独立 Vditor 编辑器编写 Markdown，支持从 Halo 附件库插入图片、附件与站内文档链接
- 前台展示：提供 `/docs`、`/docs/{librarySlug}`、`/docs/{librarySlug}/{docSlug}` 页面、侧边目录树，以及可配置布局的文档库首页
- 公共访问接口：仅暴露已发布文档的前台只读 API，避免泄露草稿
- 搜索与 SEO：已发布文档接入 Halo 搜索，并输出页面级 SEO 元信息
- 插件设置与仪表盘：支持默认排序、默认文档库、文档库首页布局、文档页面渲染，以及设置导出 / 加载与文档内容恢复，并提供后台统计卡片
- 文档阅读增强：支持同库文档标题短链接跳转，以及详情页正文大纲、滚动高亮、移动端折叠目录与跟随 Halo 的主题切换
- 自定义代码注入：支持在全局、文档库、单篇文档三个层级注入自定义 head / body 代码（HTML / CSS / JS），按 全局 → 文档库 → 文档 顺序叠加到阅读页
- 扩展点机制：对外开放 `DocContentHandler`（正文内容后处理）与 `DocDetailModelHandler`（阅读页 Model 注入）两个扩展点，其他插件可注册实现来扩展文档阅读页
- AI 辅助编写：文档设置页提供「后台代码片段」与「扩展插件开发」两段提示词一键复制，便于借助 AI 生成规范代码

## 安装与使用

安装方式：

1. 从 GitHub Release 下载插件 jar。
2. 在 Halo Console 的「插件」页面上传 jar。
3. 安装并启用 `my-docs`。

启用后可直接使用：

- Halo Console 侧边栏中的「文档」与「文档设置」入口
- 前台文档首页：`/docs`
- 单个文档库页：`/docs/{librarySlug}`
- 文档详情页：`/docs/{librarySlug}/{docSlug}`

## 兼容性

- Halo `>= 2.25.0`

## 升级建议

升级前建议先备份 Halo 数据，并在升级后检查：

- 如需迁移或回滚文档配置，可先在「文档设置」页导出当前设置与文档快照
- 文档库列表与文档树是否正常加载
- 文档编辑保存后前台是否能正常渲染
- `/docs`、`/docs/{librarySlug}`、`/docs/{librarySlug}/{docSlug}` 是否可访问
- 搜索是否能命中文档内容

## 界面预览

### Console 管理端

文档库列表：

![文档库列表](./docs/screenshots/console-libraries.webp)

文档树管理：

![文档树管理](./docs/screenshots/console-tree.webp)

文档编辑页：

![文档编辑页-0](./docs/screenshots/console-editor-0.webp)

文档编辑页，站内文档链接插入弹窗：

![文档编辑页-1](./docs/screenshots/console-editor-1.webp)

文档设置页：

![文档设置页-0](./docs/screenshots/console-settings-0.webp)

文档设置页，文档页面渲染配置：

![文档设置页-1](./docs/screenshots/console-settings-1.webp)

仪表盘统计组件：

![仪表盘统计组件](./docs/screenshots/dashboard-widget.webp)

### 前台文档站点

文档库首页：

![文档库首页-0](./docs/screenshots/site-index-0.webp)

文档库首页，文件夹选择弹窗：

![文档库首页-1](./docs/screenshots/site-index-1.webp)

单个文档库首页页内导航布局：

![文档库首页-2](./docs/screenshots/site-index-2.webp)

文档详情页：

![文档详情页-0](./docs/screenshots/site-detail-0.webp)

## 功能概览

### Console 管理端

- 侧边栏「文档」入口与「文档设置」入口
- 仪表盘文档统计组件
- 文档库列表与编辑弹窗
- 文档树管理页
- 文档编辑页（导航树 + Vditor 编辑器 + 工具栏附件库/文档链接插入）
- 文档设置页支持文档库首页布局编排：默认每行数量、默认每页最大行数、特定页行数、特定行列数、文档库坐标与文件夹标题/描述
- 文档设置页支持导出 / 加载设置，备份内容包含插件设置、文档库与文档 Markdown 内容；加载时按快照恢复
- 文档设置页支持配置全局自定义 head / body 代码，并提供「后台代码片段」「扩展插件开发」两段 AI 提示词一键复制
- 文档库编辑弹窗与文档编辑页分别支持配置该库 / 该文档专属的自定义 head / body 代码

### 前台文档站点

- 文档库首页：`/docs`
- 单个文档库页：`/docs/{librarySlug}`
- 文档详情页：`/docs/{librarySlug}/{docSlug}`
- 文档库首页支持按全局行号跨页排布，并可在坐标冲突时显示带名称和描述的文件夹卡片，点击后弹窗选择文档库
- 文档页面渲染可由插件设置统一控制
- 文档站点主题跟随 Halo 当前主题设置，而不是浏览器系统主题
- 阅读页按 全局 → 文档库 → 文档 层级注入管理员配置的自定义代码，并可被其他插件通过扩展点扩展

## 许可证

[GPL-3.0](./LICENSE) © tsdaer
