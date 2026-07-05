# 开发与维护

本文件面向插件开发者与维护者，汇总 `my-docs` 的开发、调试、构建与发布资料。

## 开发资料

- 项目开发路线：[PROJECT_PLAN.md](../PROJECT_PLAN.md)
- 发布检查清单：[release-checklist.md](./release-checklist.md)
- Release 说明模板：[release-notes-template.md](./release-notes-template.md)
- 截图执行说明：[screenshot-guide.md](./screenshot-guide.md)
- 截图目录说明：[screenshots/README.md](./screenshots/README.md)

## 项目状态

当前主线里程碑 M0-M4 已完成，M5 正在推进，主要剩余项是：

- 可选能力：评论系统集成

远端发布链路已验证：

- GitHub Actions CI/CD 可正常执行
- GitHub Release 已成功附带插件 jar

## 开发环境要求

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

## 实现概览

- 文档站点主题模板位于 `src/main/resources/templates/docs/**`，可由主题侧覆盖
- 服务端通过 `DocLibrary`、`Doc` 自定义 Extension 承载文档库与文档数据
- 应用层负责 slug 唯一性校验
- `DocReconciler` 自动将 Markdown 渲染为 HTML
- `DocFinder` 向主题暴露已发布文档数据
- `DocSearchDocumentsProvider` 接入 Halo 搜索
- `extensionpoint` 包对外开放两个扩展点（SPI），供其他插件扩展前台阅读页，详见下文

## 为 my-docs 编写扩展

`my-docs` 通过 Halo 标准扩展点机制（`org.pf4j.ExtensionPoint` + `ExtensionPointDefinition`）
对外开放两个扩展点，其他插件只需注册 Spring bean 即可介入前台阅读页，无需任何可视化配置。
两个扩展点均为 `MULTI_INSTANCE`（可同时启用多个实现），定义见
`src/main/resources/extensions/extensionpoints.yaml`。

### 1. `DocContentHandler` —— 正文内容后处理

在文档详情页正文 HTML 生成之后、展示之前进行链式改写（图片懒加载、代码高亮容器、内容注入等）。
多个实现按 `getOrder()` 升序串成责任链；`my-docs` 内置的同库短链改写与大纲提取始终作为链的
最后一环，因此扩展看到的是渲染后的原始正文，而内置后处理基于扩展改写后的最终 HTML。

```java
@Component
public class LazyImageHandler implements DocContentHandler {
    @Override
    public Mono<DocContentContext> handle(DocContentContext context) {
        // context.getDoc() / context.getLibrary() 为只读上下文
        var html = context.getContent().replace("<img ", "<img loading=\"lazy\" ");
        context.setContent(html);
        return Mono.just(context);
    }

    @Override
    public int getOrder() {
        return 100; // 值越小越先执行
    }
}
```

### 2. `DocDetailModelHandler` —— 阅读页 Model 注入

向索引页 / 库页 / 详情页的模板 model 注入额外数据或片段（相关文档、上一篇/下一篇、
自定义侧栏块等），供主题模板渲染。通过 `DocModelContext.getPageType()`
区分 `INDEX` / `LIBRARY` / `DETAIL` 三种页面；`library` / `doc` 按页面类型可能为空
（INDEX 页两者皆空，LIBRARY 页仅有 `library`，DETAIL 页两者皆有）。
单个 handler 抛错不会中断整页渲染，仅记录日志后跳过。建议注入的 model 键名带插件前缀以避免冲突。

```java
@Component
public class RelatedDocsHandler implements DocDetailModelHandler {
    @Override
    public Mono<Void> handle(DocModelContext context) {
        if (context.getPageType() != DocPageType.DETAIL) {
            return Mono.empty();
        }
        // ... 计算相关文档
        context.getModel().addAttribute("myPlugin_relatedDocs", relatedDocs);
        return Mono.empty();
    }
}
```

### 实现插件如何声明依赖

扩展插件需在自身 `plugin.yaml` 声明对 `my-docs` 的依赖，确保扩展点类在运行时可用：

```yaml
spec:
  pluginDependencies:
    "my-docs": ">=1.0.0"
```

若希望在 `my-docs` 未安装时仍能独立运行，改用 `optionalDependencies`。

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
└── docs/                                 # 发布检查、截图与发行说明资料
```
