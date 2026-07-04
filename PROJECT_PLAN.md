# my-docs 项目计划

本文件描述 `my-docs` 插件从脚手架到可用版本的开发路线。它是一份活文档，随实现进展更新。

## 1. 背景与目标

Halo 原生的内容模型是「文章（Post）」和「页面（SinglePage）」，适合博客与独立页面，但不擅长表达**具有层级结构、需要侧边目录导航的成套文档**（如产品手册、开发文档、帮助中心）。

`my-docs` 的目标是在 Halo 中提供一个独立的文档子系统：

- 内容与普通文章解耦，单独管理
- 支持「文档库 → 分组 → 文档」的多级组织
- 前台提供带侧边目录树的文档阅读界面
- 自带文档编辑与 Markdown 渲染，不依赖 Halo 文章编辑器
- 复用 Halo 的通用能力（附件、搜索、评论、权限），避免重复造轮子

### 非目标（当前阶段不做）

- 多语言文档版本切换
- 文档版本历史 / diff（依赖 Halo 快照能力，后续评估）
- 在线协同编辑

## 2. 领域模型

采用 Halo 的 Extension（类 CRD）机制定义自定义资源。初步设计三类资源：

| 资源      | GVK Kind       | 说明                                             |
| --------- | -------------- | ------------------------------------------------ |
| 文档库    | `Doc­Library`  | 一套独立文档的容器，拥有名称、slug、描述、封面     |
| 文档      | `Doc`          | 单篇文档，含标题、slug、正文、所属库、父节点、排序 |
| （可选）分组 | 通过 `Doc.spec.parent` 自引用实现树，暂不单独建模 |                                                  |

> 注：字段命名、索引、查询 API 会随 Halo 版本演进。实现前需对照官方文档核对
> `AbstractExtension` / `@GVK` / `IndexSpecs` / `ReactiveExtensionClient` 的最新签名，
> 不依赖记忆。

### Doc 关键字段（草案）

- `spec.title` — 标题（必填）
- `spec.slug` — 访问别名（库内唯一，建立唯一索引）
- `spec.libraryName` — 所属文档库的 `metadata.name`（建立索引）
- `spec.parent` — 父文档 `metadata.name`，为空表示顶层（建立索引，用于构建目录树）
- `spec.priority` — 同级排序权重（建立索引）
- `spec.content` / `spec.raw` — 正文（`content` 为渲染后 HTML，`raw` 为 Markdown 原文）
- `spec.rawType` — 正文原始格式，固定为 `markdown`（预留 `html` 以便日后扩展）
- `spec.publishTime` / `spec.published` — 发布状态

**内容编辑与渲染：插件自实现**：经查证，Halo 没有向插件开放可嵌入到自定义页面的富文本/
Markdown 编辑器组件——`@halo-dev/components` 仅导出 `VCodemirror`（纯代码编辑器），`editor:create`
扩展点是把编辑器 provider 注册进文章/页面编辑流程，方向相反，无法直接复用。因此文档的编辑与
渲染由本插件自行实现：

- **编辑（前端）**：在 Console UI 中集成一个自带的 Markdown 编辑器组件（如基于 CodeMirror/
  Milkdown/Vditor 等），产出 Markdown 原文存入 `spec.raw`，`spec.rawType=markdown`。
- **渲染（后端）**：保存或读取时，由插件后端将 Markdown 渲染为 HTML 写入 `spec.content`；
  引入 Markdown 渲染库（如 commonmark-java / flexmark），支持代码高亮、表格、任务列表等扩展语法。
- 前台直接展示 `spec.content`。

> 说明：以上关于「Halo 未开放可复用编辑器组件」的结论基于本地 Halo 插件开发参考核对得出，
> 未能在线比对最新文档。动手前建议在本地 `./gradlew haloServer` 环境实测确认（见 M0）。

需要建立索引的字段：`spec.slug`（唯一）、`spec.libraryName`、`spec.parent`、`spec.priority`，
以支持按库查询、构建目录树、排序。

## 3. 里程碑

### M0 — 脚手架校验（当前）

- [x] 项目脚手架就绪（`pnpm create halo-plugin`）
- [x] 完善 README 与项目计划
- [ ] 确认 `./gradlew haloServer` 可本地启动并加载插件
- [ ] 校对 `plugin.yaml`（displayName、description、requires）

### M1 — 后端数据模型与 API

- [ ] 定义 `DocLibrary`、`Doc` Extension（`@GVK` + `AbstractExtension`）
- [ ] 在插件 `start()` 中通过 `SchemeManager` 注册，并声明索引
- [ ] 在 `stop()` 中 `unregister`
- [ ] 依赖 Halo 自动生成的 CRUD API 验证增删改查
- [ ] 补充 slug 唯一性、必填校验（`@Schema`）
- [ ] **Markdown 渲染（后端自实现）**：引入 Markdown 渲染库（如 commonmark-java / flexmark），
      保存或读取时将 `spec.raw`（Markdown）渲染为 HTML 写入 `spec.content`，支持代码高亮、
      表格、任务列表等扩展语法
- [ ] 单元测试覆盖模型注册、查询与 Markdown 渲染

参考：`server-extension.md`、`server-lifecycle.md`、`server-shared-beans.md`、`server-api.md`

### M2 — Console 管理界面

- [ ] 在 `ui/src/index.ts` 中用 `definePlugin` 注册菜单与路由（替换示例页面）
- [ ] **侧边菜单入口**：在后台侧边栏注册「文档」菜单项，指向文档管理页
      （route `meta.menu`，选定分组 `content`，配 `icon` 与 `priority`）
- [ ] **文档设置页**：提供插件的文档设置界面（默认排序、每页数量、默认库等），
      作为一条子路由；侧边菜单入口可下钻到此设置页
- [ ] **仪表盘入口**：通过 `console:dashboard:widgets:create` 扩展点注册一个仪表盘小组件，
      展示文档统计（库数量、文档总数）并提供「进入文档设置」的快捷跳转按钮
- [ ] 文档库列表页：增删改查
- [ ] 文档树管理页：树形展示、拖拽排序、层级调整
- [ ] 文档编辑页：集成插件自带的 Markdown 编辑器组件撰写正文（原文存 `spec.raw`，
      `spec.rawType=markdown`），提供实时预览
- [ ] 通过 `generateApiClient` 生成前端 API 客户端并调用
- [ ] RBAC 角色模板：管理端权限

参考：`ui-entry.md`、`ui-extension-points.md`、`ui-components.md`、`ui-forms.md`、`ui-api-request.md`、`server-security.md`

### M3 — 主题前台展示

- [ ] 实现 `DocFinder`（`@Finder`），向主题暴露：按库列文档、取单篇、构建目录树
- [ ] 提供 Thymeleaf 模板（文档站首页、单篇文档页、侧边目录树）
- [ ] 定义前台访问路由与 slug 解析
- [ ] 面向匿名访问的公共 API 角色模板（aggregate-to-anonymous）

参考：`theme-integration.md`、`server-security.md`

### M4 — 增强能力

- [ ] 接入 Halo 搜索：实现 `HaloDocumentsProvider` 索引文档内容
- [ ] （可选）接入评论系统：为 `Doc` 实现 `CommentSubject`
- [ ] 插件设置项（`plugin.yaml` settings）：默认排序、每页数量等
- [ ] 文档封面、SEO 元信息

参考：`server-search.md`、`theme-integration.md`（CommentSubject）、`plugin-manifest.md`

### M5 — 发布

- [ ] 完善文档与截图
- [ ] CI 通过（`.github/workflows/ci.yaml`）
- [ ] `./gradlew build` 产出 jar，走 CD 发布流程
- [ ] （可选）提交至 Halo 应用市场

## 4. 技术决策与风险

| 项                | 决策 / 说明                                                                 |
| ----------------- | --------------------------------------------------------------------------- |
| 数据存储          | 使用 Halo Extension，随 Halo 主存储走，无需自建数据库                        |
| 目录树            | 用 `spec.parent` 自引用 + `spec.priority` 排序，前端/Finder 侧组装成树       |
| slug 唯一性       | 依赖 Extension 唯一索引（Halo 2.22+ 的 `IndexSpecs.single(...).unique(true)`）|
| 前端构建          | 沿用脚手架的 Rsbuild，不切换                                                 |
| 版本要求          | `requires >= 2.25.0`；使用版本敏感 API 前查 `api-changelog.md`              |
| 风险：API 演进    | Extension / UI API 跨版本变化，实现时以官方文档为准，避免依赖记忆签名        |
| 风险：目录树性能  | 大量文档时递归组装树可能慢，必要时限制层级或分页加载子节点                   |

## 5. 参考资料

插件开发各主题的官方参考见 `halo-plugin-dev` 技能的 References 索引；本计划各里程碑已标注对应条目。核心入口：

- Halo 插件开发文档：https://docs.halo.run/developer-guide/plugin/basics/overview
- 插件示例（todolist）：https://docs.halo.run/developer-guide/plugin/examples/todolist
