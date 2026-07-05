# my-docs

一个为 [Halo](https://halo.run) 提供文档 / 知识库能力的插件。

`my-docs` 在 Halo 原有文章与页面模型之外，补充一套独立的文档系统，用来承载产品手册、开发文档、帮助中心这类需要树形目录和连续阅读体验的内容。

项目开发路线见 [PROJECT_PLAN.md](./PROJECT_PLAN.md)，发布前检查项见 [docs/release-checklist.md](./docs/release-checklist.md)。

## 当前能力

- 文档库管理：创建、编辑、删除文档库，支持描述与封面
- 文档管理：按文档库维护文档，支持发布状态、slug、父子层级、排序权重
- 树形编排：在 Console 中拖拽调整目录树与同级顺序
- Markdown 编辑：使用独立 Vditor 编辑器编写 Markdown，支持从 Halo 附件库插入图片、附件与站内文档链接
- 前台展示：提供 `/docs`、`/docs/{librarySlug}`、`/docs/{librarySlug}/{docSlug}` 页面、侧边目录树，以及可配置布局的文档库首页
- 公共访问接口：仅暴露已发布文档的前台只读 API，避免泄露草稿
- 搜索与 SEO：已发布文档接入 Halo 搜索，并输出页面级 SEO 元信息
- 插件设置与仪表盘：支持默认排序、默认文档库、文档库首页布局、文档页面渲染等设置，并提供后台统计卡片
- 文档阅读增强：支持同库文档标题短链接跳转，以及详情页正文大纲、滚动高亮、移动端折叠目录与跟随 Halo 的主题切换

## 当前状态

当前主线里程碑 M0-M4 已完成，M5 正在推进，主要剩余项是：

- 完善发布文档与界面截图
- 等待 CI 在远端跑通并沉淀发布流程
- 可选能力：评论系统集成

## 环境要求

- Halo `>= 2.25.0`
- Java 21+
- Node.js 18+ 与 pnpm 10+
- Docker（本地通过 `haloServer` 启动 Halo 时需要）

## 本地开发

```bash
# 安装前端依赖
cd ui
pnpm install

# 前端监听构建
pnpm dev

# 回到项目根目录，启动 Halo + 插件
cd ..
./gradlew haloServer
```

默认后台地址：`http://localhost:8090/console`

默认账号密码：`admin` / `admin`

后端改动后可使用：

```bash
./gradlew reload
# 或
./gradlew watch
```

## 测试与构建

```bash
# 后端测试
./gradlew test

# 整体构建（含 UI 检查、单测、打包）
./gradlew build
```

构建产物位于 `build/libs/`，当前插件 jar 名称示例：

```text
build/libs/plugin-my-docs-1.0.0-SNAPSHOT.jar
```

## 功能概览

### Console 管理端

- 侧边栏「文档」入口与「文档设置」入口
- 仪表盘文档统计组件
- 文档库列表与编辑弹窗
- 文档树管理页
- 文档编辑页（导航树 + Vditor 编辑器 + 工具栏附件库/文档链接插入）
- 文档设置页支持文档库首页布局编排：默认每行数量、默认每页最大行数、特定页行数、特定行列数、文档库坐标与文件夹标题/描述

### 前台文档站点

- 文档库首页：`/docs`
- 单个文档库页：`/docs/{librarySlug}`
- 文档详情页：`/docs/{librarySlug}/{docSlug}`
- 文档库首页支持按全局行号跨页排布，并可在坐标冲突时显示带名称和描述的文件夹卡片，点击后弹窗选择文档库
- 文档页面渲染可由插件设置统一控制
- 文档站点主题跟随 Halo 当前主题设置，而不是浏览器系统主题
- 可被主题覆盖的 Thymeleaf 模板：`src/main/resources/templates/docs/**`

### 服务端能力

- `DocLibrary`、`Doc` 自定义 Extension
- 应用层 slug 唯一性校验
- `DocReconciler` 自动将 Markdown 渲染为 HTML
- `DocFinder` 向主题暴露已发布文档数据
- `DocSearchDocumentsProvider` 接入 Halo 搜索

## 目录结构

```text
plugin-my-docs/
├── src/main/java/com/github/mydocs/      # 插件后端代码
├── src/main/resources/plugin.yaml        # 插件清单
├── src/main/resources/extensions/        # 设置、角色模板等扩展资源
├── src/main/resources/templates/docs/    # 前台模板
├── src/test/java/com/github/mydocs/      # 后端测试
├── ui/src/                               # Console 前端代码
├── PROJECT_PLAN.md                       # 里程碑与设计计划
└── docs/release-checklist.md             # 发布检查与截图清单
```

## 许可证

[GPL-3.0](./LICENSE) © tsdaer
