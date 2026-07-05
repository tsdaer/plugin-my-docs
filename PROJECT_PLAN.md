# my-docs 项目计划

本文件描述 `my-docs` 插件从脚手架到可用版本的开发路线。它是一份活文档，随实现进展更新。

## 1. 背景与目标

Halo 原生的内容模型是「文章（Post）」和「页面（SinglePage）」，适合博客与独立页面，但不擅长表达**具有层级结构、需要侧边目录导航的成套文档**（如产品手册、开发文档、帮助中心）。

`my-docs` 的目标是在 Halo 中提供一个独立的文档子系统：

- 内容与普通文章解耦，单独管理
- 支持「文档库 → 分组 → 文档」的多级组织
- 前台提供带侧边目录树与正文大纲的文档阅读界面
- 自带文档编辑与 Markdown 渲染，不依赖 Halo 文章编辑器
- 支持文档内与同库文档间的标题锚点跳转
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
- [x] 确认 `./gradlew haloServer` 可本地启动并加载插件（后台插件列表已显示本插件）
- [x] 校对 `plugin.yaml`（displayName 改为「文档」，description 说明文档功能）

### M1 — 后端数据模型与 API

- [x] 定义 `DocLibrary`、`Doc` Extension（`@GVK` + `AbstractExtension`）
- [x] 在插件 `start()` 中通过 `SchemeManager` 注册，并声明索引
- [x] 在 `stop()` 中 `unregister`
- [x] 字段必填/长度校验（`@Schema`）
- [x] 单元测试覆盖模型注册与注销
- [x] `./gradlew reload` 后用自动生成的 CRUD API 验证增删改查（已验证：创建/查询/删除、
      `spec.libraryName` 与 `spec.priority` 索引及排序均生效）
- [x] slug 库内唯一性校验（应用层实现）：`DocService` + `DocServiceImpl` 在创建/更新前，
      按 `spec.libraryName` + `spec.slug` 查询同库同 slug（排除自身），重复则抛
      `ServerWebInputException`（HTTP 400）；通过 Console 自定义端点 `DocEndpoint`
      （`console.api.docs.halo.run/v1alpha1`，POST `/docs`、PUT `/docs/{name}`）走此校验。
      已实测：库内首篇 200、同库同 slug 400、跨库同 slug 200
- [x] **Markdown 渲染（后端自实现）**：引入 commonmark-java（core + tables/task-list/
      strikethrough/autolink/heading-anchor 扩展，均 0.24.0，`implementation` 打包进 jar）。
      `MarkdownRenderer` 服务将 Markdown 渲染为 HTML，围栏代码块输出 `language-xxx` class
      供前台客户端高亮；`DocReconciler`（`Reconciler<Request>`，自动被 Spring 发现）监听
      `Doc`，把 `spec.raw` 渲染进 `spec.content`。渲染确定性：仅当结果与已存 `content`
      不同才 `update`，重复 reconcile 自然收敛、无更新死循环。单测覆盖渲染输出与
      reconciler 的渲染/跳过/资源不存在三种路径

参考：`server-extension.md`、`server-lifecycle.md`、`server-shared-beans.md`、`server-api.md`、`server-reconciler.md`

> ⚠️ 实现约束（已踩坑）：
> - 插件仅依赖 `run.halo.app:api`（`compileOnly`），`SpringdocRouteBuilder` 位于 Halo 的
>   `application` 模块，**不在插件编译 classpath 上**。自定义端点改用标准 WebFlux
>   `RouterFunctions.route()`，代价是端点不进 OpenAPI spec —— M2 用 `generateApiClient`
>   生成前端客户端时不会覆盖它，前端需对该端点手写 `axiosInstance` 调用。
> - 查询构建用 `Queries`（`QueryFactory` 自 2.22.0 起弃用）；不确定签名的异常类型
>   （如 `DuplicateNameException`）不要凭记忆用，优先用标准的 `ServerWebInputException`。

### M2 — Console 管理界面

- [x] 在 `ui/src/index.ts` 中用 `definePlugin` 注册菜单与路由（替换示例页面）
- [x] **侧边菜单入口**：在后台侧边栏注册「文档」菜单项，指向文档库列表页
      （route `meta.menu`，分组 `content`，`icon` 用 `~icons/ri/book-2-line`，`priority=40`）
- [x] **文档设置页**：提供插件的文档设置界面（默认排序、每页数量、默认库等），
      作为 `/docs/settings` 子路由；侧边菜单注册「文档设置」，文档库列表页也提供设置入口。
      `plugin.yaml` 声明 `settingName=my-docs-settings` 与 `configMapName=my-docs-configmap`，
      `extensions/settings.yaml` 提供 Halo 插件设置表单；自定义设置页读写 `my-docs-configmap`
      的 `basic` 分组 JSON，管理角色补充 ConfigMap 读写权限
- [x] **仪表盘入口**：通过 `console:dashboard:widgets:create` 扩展点注册一个仪表盘小组件，
      展示文档统计（库数量、文档总数）并提供「管理文档」「文档设置」快捷跳转按钮
- [x] 文档库列表页：增删改查（`DocLibraryList.vue` 列表/分页/删除 +
      `DocLibraryEditingModal.vue` 新建/编辑表单，`@tanstack/vue-query` v4 + 生成的 CRUD 客户端）
- [x] 文档列表页：进入某库后平铺列出库内文档，含新建/编辑/删除入口
      （`DocList.vue`，`DocV1alpha1Api.listDoc` 按 `spec.libraryName` fieldSelector 过滤 + 分页）；
      文档库列表每行增加「管理文档」入口跳转
- [x] 文档树管理页：树形展示、拖拽排序、层级调整（`DocList.vue` 用自写递归组件
      `DocTreeNode.vue` + 原生 HTML5 拖拽渲染（原 `@he-tree/vue` 在 Halo 外置 Vue 下
      静默不渲染，已弃用，bundle 减 44kB），`buildDocTree`/`flattenPositions`/`moveNode`
      （`utils/doc-tree.ts`，含单测）按 `spec.parent`/`priority` 组树与三区落点移动；
      拖拽后 diff 出受影响节点，只对 parent/priority 变化者走 `DocEndpoint` PUT 即时保存，
      `Promise.all` 并发 + invalidate 重拉校正；节点标题直接可点进编辑，行内新建子文档/编辑/删除。
      树需全量故去分页，上限 200 并 log+Toast 提示）
- [x] 文档编辑页：独立路由 `DocEditor.vue`（**左侧可折叠文档导航树 `DocNavTree.vue` +
      右侧编辑器**布局）+ `MarkdownEditor.vue`（包装 **Vditor** 即时渲染，destroy 时序已修）；
      元信息 FormKit 受控表单 + Markdown 正文；侧边树点击切换文档（`watch(docName)` 重载，
      dirty 检测 + 未保存 `Dialog` 确认）；创建/更新走 `DocEndpoint`（`axiosInstance` POST/PUT，
      带 slug 库内唯一校验），正文原文存 `spec.raw`、`spec.rawType=markdown`，HTML 由后端
      `DocReconciler` 渲染进 `spec.content`。（Vditor chunk 已懒加载，未拖入主 bundle）
- [x] 通过 `generateApiClient` 生成前端 API 客户端并调用（`build.gradle` 配置 openApi 分组
      匹配 `docs.halo.run/v1alpha1/**`，生成物在 `ui/src/api/generated`）
- [x] RBAC 角色模板：管理端权限（`roleTemplates.yaml` 定义 view/manage 两条，
      ui-permissions `plugin:my-docs:libraries:view|manage`）

参考：`ui-entry.md`、`ui-extension-points.md`、`ui-components.md`、`ui-forms.md`、`ui-api-request.md`、`server-security.md`

### M3 — 主题前台展示

- [x] 实现 `DocFinder`（`@Finder("myDocs")`），向主题暴露：文档库列表、按库列已发布文档、
      按库 slug + 文档 slug 取单篇已发布文档、构建已发布文档目录树。目录树按
      `spec.parent` / `spec.priority` 组装，缺失父节点时回退为顶层节点
- [x] 提供 Thymeleaf 模板：`/docs` 文档库首页、`/docs/{librarySlug}` 文档库页、
      `/docs/{librarySlug}/{docSlug}` 单篇文档页，以及递归侧边目录树片段。模板默认位于
      `templates/docs/**`，通过 `TemplateNameResolver` 允许主题覆盖同名模板
- [x] 定义前台访问路由与 slug 解析：`DocPageController` 使用库 slug 解析 `DocLibrary`，
      再用文档 slug 在该库内解析已发布 `Doc`；未找到或未发布返回 404
- [x] 面向匿名访问的公共 API 角色模板（aggregate-to-anonymous）：新增
      `api.docs.halo.run/v1alpha1` 公开只读端点（库列表、库详情、库内已发布文档、单篇已发布文档、
      目录树），并通过隐藏角色模板聚合到 anonymous；不直接匿名开放原始 CRUD API，避免泄露草稿

参考：`theme-integration.md`、`server-security.md`

### M4 — 增强能力

- [x] 接入 Halo 搜索：实现 `HaloDocumentsProvider`（`DocSearchDocumentsProvider`），将已发布且未删除、
      能解析到所属文档库 slug 的 `Doc` 转为 `HaloDocument`；正文用 `spec.content` 去 HTML 后索引，
      无 HTML 时回退 `spec.raw`，链接指向 `/docs/{librarySlug}/{docSlug}`。单测覆盖草稿/删除/孤儿文档过滤、
      HTML 去标签与 permalink/type 字段
- [ ] 编辑器接入 Halo 附件库：在 `DocEditor.vue` / `MarkdownEditor.vue` 提供「插入附件」入口，
      复用 Halo 附件选择能力，把所选图片 / 文件转换为 Markdown 语法回填到 `spec.raw`；至少支持
      图片插入（`![alt](url)`）与普通文件链接（`[text](url)`），后续再评估批量插入与附件属性编辑
- [ ] 同库文档标题跳转：基于现有 Markdown 标题锚点能力，为文档详情页支持当前文档
      `#heading-id` 跳转，以及同一文档库内其它文档的标题跳转；约定优先支持 Markdown 相对链接
      `./doc-slug#heading-id`（或等价的同库短链接形式），由渲染层或前台展示层解析为
      `/docs/{librarySlug}/{docSlug}#heading-id`。跨库目标暂不做解析，保持普通链接语义
- [ ] 文档渲染设置：把 Vditor / Markdown 渲染相关配置纳入插件设置页统一管理，例如代码高亮、
      标题锚点、数学公式、流程图、emoji、任务列表、预览主题等；编辑态预览与前台实际渲染尽量共用
      同一份配置，避免“编辑器里看到的效果”和前台 HTML 不一致
- [ ] 正文大纲视图：在文档详情页基于当前文档标题层级生成独立大纲，与左侧文档树区分开来；
      桌面端优先放在正文右侧，移动端改为可折叠入口。大纲需支持当前阅读位置高亮、点击滚动到标题、
      与标题锚点跳转行为保持一致
- [ ] （可选）接入评论系统：为 `Doc` 实现 `CommentSubject`
- [x] 插件设置项（`plugin.yaml` settings）：默认排序、每页数量等（已随 M2 设置页实现）
- [x] 文档封面、SEO 元信息：复用 `DocLibrary.spec.cover` 作为文档库封面，Console 文档库
      编辑弹窗支持录入封面地址，后台列表与前台 `/docs`、`/docs/{librarySlug}` 模板展示封面；
      `DocPageController` 引入 `DocPageSeoMetadataFactory`，为索引页/文档库页/文档详情页统一输出
      `title`、`description`、`og:title`、`og:description`、`og:type`，并在有封面时输出
      `og:image`；详情页描述优先从渲染后的 HTML 摘要生成，回退 Markdown / 文档库描述

参考：`server-search.md`、`theme-integration.md`（CommentSubject）、`plugin-manifest.md`

### M5 — 发布

- [ ] 完善文档与截图（README 已按当前实现更新；新增 `docs/release-checklist.md` 汇总发布校验与截图清单，实际截图仍待补充）
- [ ] CI 通过（`.github/workflows/ci.yaml` 已配置，待远端工作流跑通并确认状态）
- [x] `./gradlew build` 产出 jar，走 CD 发布流程（2026-07-05 本地已验证 `./gradlew test`、`./gradlew build` 成功，产物为 `build/libs/plugin-my-docs-1.0.0-SNAPSHOT.jar`）
- [ ] （可选）提交至 Halo 应用市场

## 4. 技术决策与风险

| 项                | 决策 / 说明                                                                 |
| ----------------- | --------------------------------------------------------------------------- |
| 数据存储          | 使用 Halo Extension，随 Halo 主存储走，无需自建数据库                        |
| 目录树            | 用 `spec.parent` 自引用 + `spec.priority` 排序，前端/Finder 侧组装成树       |
| 正文大纲          | 基于渲染后 HTML 标题层级提取；与左侧文档树并存，避免把“文档结构”和“正文结构”混为一体 |
| 同库跳转          | 复用标题锚点 id；仅解析当前库内的文档相对链接，跨库链接先不做自动重写         |
| 渲染配置          | 优先做插件级全局配置，后续再评估文档库级覆盖，避免预览与前台配置漂移         |
| slug 唯一性       | 依赖 Extension 唯一索引（Halo 2.22+ 的 `IndexSpecs.single(...).unique(true)`）|
| 前端构建          | 沿用脚手架的 Rsbuild，不切换                                                 |
| 版本要求          | `requires >= 2.25.0`；使用版本敏感 API 前查 `api-changelog.md`              |
| 风险：API 演进    | Extension / UI API 跨版本变化，实现时以官方文档为准，避免依赖记忆签名        |
| 风险：目录树性能  | 大量文档时递归组装树可能慢，必要时限制层级或分页加载子节点                   |
| 风险：锚点稳定性  | 标题改名会导致旧锚点失效；如后续需要稳定 permalink，可再评估显式 heading id 机制 |

## 5. 参考资料

插件开发各主题的官方参考见 `halo-plugin-dev` 技能的 References 索引；本计划各里程碑已标注对应条目。核心入口：

- Halo 插件开发文档：https://docs.halo.run/developer-guide/plugin/basics/overview
- 插件示例（todolist）：https://docs.halo.run/developer-guide/plugin/examples/todolist
