# my-docs

一个为 [Halo](https://halo.run) 网站提供**文档（知识库）**功能的插件。

在博客文章之外，很多站点还需要一块结构化、可层级组织、带侧边目录导航的内容区域——例如产品手册、开发文档、帮助中心。`my-docs` 通过独立的文档模型和文档站点前台，把这类内容与普通文章分离管理。

> ⚠️ 当前处于早期开发阶段，仓库仍以脚手架为主，功能尚在实现中。开发路线见 [PROJECT_PLAN.md](./PROJECT_PLAN.md)。

## 功能规划

- 📚 **文档库（Docs）**：按「库 → 分组 → 文档」的层级组织内容
- 🌲 **侧边目录树**：前台文档页自动生成可折叠的目录导航
- ✍️ **富文本编辑**：复用 Halo 默认编辑器撰写文档内容
- 🔗 **自定义别名（slug）**：为每篇文档生成友好的访问路径
- 🔍 **站内搜索集成**：文档内容接入 Halo 搜索引擎
- 🗂️ **排序与置顶**：控制文档在目录中的展示顺序
- 🎨 **主题对接**：通过 Finder API 向主题暴露文档数据

各功能的实现阶段与优先级详见项目计划。

## 技术栈

| 层         | 技术                                                       |
| ---------- | ---------------------------------------------------------- |
| 后端       | Java 21、Spring Boot、Spring WebFlux（响应式）             |
| 数据模型   | Halo Extension（类 CRD 的自定义资源）                      |
| 前端 Console | Vue 3 + TypeScript，@halo-dev/ui-shared / components     |
| 前端构建   | Rsbuild（@halo-dev/ui-plugin-bundler-kit）                 |
| 主题对接   | Thymeleaf 模板 + Finder API                                |
| 构建       | Gradle + Halo Plugin DevTools                              |

## 环境要求

- Halo `>= 2.25.0`
- Java 21+
- Node.js 18+ 与 pnpm
- Docker（`./gradlew haloServer` 本地运行 Halo 时需要）

## 开发

```bash
# 1. 安装前端依赖
cd ui
pnpm install

# 2. 启动前端构建（监听模式，自动重编译）
pnpm dev

# 3. 回到根目录，启动带插件的 Halo 服务（需 Docker）
cd ..
./gradlew haloServer
```

启动后访问 `http://localhost:8090/console`，默认账号密码为 `admin` / `admin`。

修改后端 Java 代码后热重载：

```bash
./gradlew reload
# 或持续监听
./gradlew watch
```

## 构建

```bash
./gradlew build
```

产物为 `build/libs/` 目录下的插件 jar 文件，可在 Halo 控制台「插件」页面上传安装。

## 目录结构

```
plugin-my-docs/
├── build.gradle                 # 插件后端构建配置
├── settings.gradle
├── gradle.properties            # 版本号
├── src/main/
│   ├── java/com/github/mydocs/  # 后端 Java 代码（插件主类、模型、API、Finder）
│   └── resources/
│       ├── plugin.yaml          # 插件清单（元数据、依赖、设置）
│       ├── extensions/          # 声明式扩展资源（角色模板、反向代理等）
│       └── templates/           # 主题前台 Thymeleaf 模板
└── ui/                          # Console / 用户中心前端
    ├── src/
    │   ├── index.ts             # definePlugin 入口（路由、菜单、扩展点）
    │   └── views/               # Vue 页面组件
    └── rsbuild.config.ts
```

## 许可证

[GPL-3.0](./LICENSE) © tsdaer
