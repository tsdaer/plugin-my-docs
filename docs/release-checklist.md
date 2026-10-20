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
- `verifyUiBundle` 通过：JAR 中入口脚本引用的全部 JS / CSS chunk 均存在（`build` 自动执行）

## 2. 手工回归

建议在本地 Halo 开发环境中检查：

- 插件可安装、启用、卸载
- Console 左侧菜单中的「文档」「文档设置」可访问
- 文档库增删改查正常
- 文档树拖拽排序与父子层级调整正常
- 文档编辑页保存后，前台能看到渲染结果
- 浅色 / 深色自定义内容 CSS、正文 class 与代码主题能随站点主题切换
- 内容 CSS 留空或加载失败时，正文仍使用基础回退样式并保持可读
- RaTeX 公式背景透明、颜色跟随正文，明暗主题切换后能正确重绘
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

1. 更新 `gradle.properties` 或其他版本来源中的插件版本号；交付更新包时必须递增版本，不能覆盖同版本包。Halo 根据插件版本生成 Console 合并脚本的缓存标识，同版本替换可能让旧脚本继续引用已删除的 chunk。
2. 运行 `./gradlew build`，确认产物正确。
3. 推送主分支并等待 CI 通过。
4. 创建 GitHub Release，触发 CD。
5. 校验 Release 附件中的 jar 可被 Halo 安装。

## Console 页面一直加载且 chunk 返回 404

先比较 `/plugins/my-docs/assets/ui/main.js` 与浏览器加载的 Console `bundle.js` 中的 chunk 文件名。
如果前者已更新、后者仍引用旧文件，说明合并脚本仍在缓存中。应安装递增版本号的新包，刷新整个
Console 页面（`Ctrl+Shift+R`），避免只在后台菜单间切换。

若此前替换过同版本包且升级后仍加载旧合并脚本，重启 Halo 后再次强制刷新。使用 CDN 或反向代理缓存时，
清理 Console 合并脚本的旧缓存，并为 `/apis/api.console.halo.run/` 配置绕过缓存；不要清理插件数据。
