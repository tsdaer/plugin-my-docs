# 发布检查清单

本清单用于推进 `PROJECT_PLAN.md` 中的 M5 发布阶段，覆盖本地校验、截图整理与发布动作。

配套资料：

- 发行说明模板：[release-notes-template.md](./release-notes-template.md)
- 截图执行说明：[screenshot-guide.md](./screenshot-guide.md)
- 截图目录：[screenshots/README.md](./screenshots/README.md)

## 1. 本地校验

发布前至少执行一次：

```bash
./gradlew test
./gradlew build
```

验收点：

- Java 单元测试通过
- `ui` 的 Vitest 单测通过
- `build/libs/` 生成插件 jar
- 前端资源已被打进插件产物

## 2. 手工回归

建议在本地 Halo 开发环境中检查：

- 插件可安装、启用、卸载
- Console 左侧菜单中的「文档」「文档设置」可访问
- 文档库增删改查正常
- 文档树拖拽排序与父子层级调整正常
- 文档编辑页保存后，前台能看到渲染结果
- `/docs`、`/docs/{librarySlug}`、`/docs/{librarySlug}/{docSlug}` 可正常访问
- 搜索结果可命中文档内容

## 3. 截图清单

发布说明至少补齐以下截图：

- 后台文档库列表页
- 后台文档树管理页
- 后台文档编辑页
- 后台文档设置页
- 仪表盘文档统计组件
- 前台文档库首页
- 前台文档详情页（展示侧边目录树）

当前仓库内已落库主图：

- `docs/screenshots/console-libraries.webp`
- `docs/screenshots/console-tree.webp`
- `docs/screenshots/console-editor.webp`
- `docs/screenshots/console-settings.webp`
- `docs/screenshots/dashboard-widget.webp`
- `docs/screenshots/site-index.webp`
- `docs/screenshots/site-detail.webp`

建议统一使用：

- 桌面宽度截图
- 同一套演示数据
- 干净主题和浅色背景，便于应用市场展示

## 4. GitHub Actions

仓库当前已配置：

- [ci.yaml](../.github/workflows/ci.yaml)
- [cd.yaml](../.github/workflows/cd.yaml)

发布前确认：

- CI 在目标分支最近一次提交上通过
- Release 发布后 CD 能产出并附带 jar
- 若计划上架 Halo 应用市场，移除 `skip-appstore-release: true` 并补齐 `app-id`

## 5. 发布动作

1. 更新 `gradle.properties` 或其他版本来源中的插件版本号。
2. 运行 `./gradlew build`，确认产物正确。
3. 推送主分支并等待 CI 通过。
4. 创建 GitHub Release，触发 CD。
5. 校验 Release 附件中的 jar 可被 Halo 安装。
