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
- 插件设置与仪表盘：支持默认排序、默认文档库、文档库首页布局、明暗模式独立的自定义内容 CSS 与代码主题，以及设置导出 / 加载与文档内容恢复，并提供后台统计卡片
- 文档阅读增强：支持同库文档标题短链接跳转、详情页正文大纲、滚动高亮、移动端折叠目录，以及跟随 Halo / 系统明暗模式切换内容 CSS 与代码主题
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

## 文档渲染设置

文档设置页可单独配置浅色与深色模式下的 Markdown 内容 CSS、正文容器 class 和代码高亮主题。
内容样式仅支持自定义 CSS 地址，不再提供内置内容主题；例如使用 `github-markdown-css` 时，可将
容器 class 设置为 `markdown-body`。CSS 地址留空时使用插件提供的基础回退样式。

前台阅读页优先读取 Halo 主题写入页面或本地存储的明暗状态；当 Halo 主题选择 `auto` 或没有提供明确状态时，
跟随浏览器的 `prefers-color-scheme`。主题状态变化后，自定义内容 CSS、正文 class 与代码主题会在不重新生成正文 HTML 的情况下切换。

其他渲染选项只作用于前台阅读页，不改变后台 Vditor 编辑器：

- 代码行号、自动空格、裸 URL 自动链接
- Markdown 脚注、`==文字==` Mark 标记、常见技术术语大小写修正
- 顶层正文段落首行缩进
- Mermaid、Graphviz、ECharts、Markmap 等图表代码块渲染
- `$...$` 与 `$$...$$` 数学公式渲染（RaTeX WASM + 透明 Canvas，颜色跟随正文，失败时回退 Vditor）

## 升级建议

升级前建议先备份 Halo 数据，并在升级后检查：

- 如需迁移或回滚文档配置，可先在「文档设置」页导出当前设置与文档快照
- 文档库列表与文档树是否正常加载
- 文档编辑保存后前台是否能正常渲染
- 浅色 / 深色自定义内容 CSS、正文 class 与代码主题是否能随站点主题切换
- 自定义 Markdown CSS、代码行号、脚注、图表代码块和公式是否按设置生效
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
- 文档设置页支持为浅色 / 深色模式分别配置自定义 Markdown CSS、正文容器 class 及代码高亮主题；CSS 通过 HTTPS / 站内绝对路径接入，留空时使用基础回退样式
- 文档设置页支持代码行号、自动空格、自动链接、脚注、Mark、术语修正、段首缩进、图表代码块与数学公式渲染
- 文档设置页支持导出 / 加载设置，备份内容包含插件设置、文档库与文档 Markdown 内容；加载时按快照恢复
- 文档设置页支持配置全局自定义 head / body 代码，并提供「后台代码片段」「扩展插件开发」两段 AI 提示词一键复制
- 文档库编辑弹窗与文档编辑页分别支持配置该库 / 该文档专属的自定义 head / body 代码

### 前台文档站点

- 文档库首页：`/docs`
- 单个文档库页：`/docs/{librarySlug}`
- 文档详情页：`/docs/{librarySlug}/{docSlug}`
- 文档库首页支持按全局行号跨页排布，并可在坐标冲突时显示带名称和描述的文件夹卡片，点击后弹窗选择文档库
- 文档详情页按当前设置从 Markdown 生成展示 HTML，再执行内容扩展、同库链接改写与正文大纲提取；搜索和 API 仍使用已存储的 `spec.content`
- 自定义内容 CSS、正文 class 和代码主题按浅色 / 深色模式独立配置；优先跟随 Halo 当前主题，`auto` 或无明确信号时跟随浏览器系统主题
- 内置 Vditor 展示资源由插件本地提供；第三方 Markdown CSS 由访客浏览器直接加载，加载失败时保留可读的回退样式
- 阅读页按 全局 → 文档库 → 文档 层级注入管理员配置的自定义代码，并可被其他插件通过扩展点扩展

## 许可证

[GPL-3.0](./LICENSE) © tsdaer
