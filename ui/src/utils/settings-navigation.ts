/**
 * 文档设置页的导航辅助。
 *
 * 页面上的设置项按分组平铺，左侧导航要能跳转、并在滚动时高亮当前分组。
 * 这两件事都不依赖具体 DOM，抽成纯函数便于单测。
 */

export interface SettingsSection {
  id: string
  label: string
  /** 分组下的一句话说明，悬停时显示 */
  hint?: string
}

/** 分组顺序即导航顺序，与模板里的 id 一一对应（模板顺序：备份 → 基础 → 布局 → 渲染 → 媒体 → 代码 → AI）。 */
export const SETTINGS_SECTIONS: SettingsSection[] = [
  { id: 'section-data', label: '备份与恢复', hint: '导出与加载设置' },
  { id: 'section-basic', label: '基础设置', hint: '默认排序与默认文档库' },
  { id: 'section-layout', label: '文档库首页布局', hint: '行列、页数与坐标' },
  { id: 'section-render', label: '文档页面渲染', hint: '主题、代码高亮与正文增强' },
  { id: 'section-media', label: '视频与媒体代理', hint: '内嵌播放与同域代理' },
  { id: 'section-code', label: '全局自定义代码', hint: '注入 head / body' },
  { id: 'section-ai', label: 'AI 辅助编写', hint: '提示词一键复制' },
]

export interface ScrollMetrics {
  /** 各分组距文档顶部的偏移，顺序与 SETTINGS_SECTIONS 一致 */
  offsets: number[]
  /** 当前纵向滚动位置 */
  scrollTop: number
  /** 视口高度 */
  viewportHeight: number
  /** 内容总高度 */
  scrollHeight: number
  /** 判定线相对视口顶部的偏移，默认 120（落在粘性工具条下方） */
  threshold?: number
}

/** 判断元素自身是否能纵向滚动（内容比可视区高，且 overflow 允许滚动）。 */
export function isScrollableOverflow(overflowY: string | null | undefined): boolean {
  const value = (overflowY ?? '').toLowerCase()
  return value === 'auto' || value === 'scroll' || value === 'overlay'
}

/**
 * 找出实际发生滚动的容器。
 *
 * Halo 控制台的外壳用 OverlayScrollbars 把内容放进内部滚动容器，
 * 此时 window 上不会有 scroll 事件、window.scrollY 恒为 0，
 * 只监听 window 的话分组高亮会一直停在第一项。所以先找一个内容溢出的滚动祖先，
 * 找不到再退回 window 对应的 documentElement。
 */
export function resolveScrollContainer(
  start: Element | null | undefined,
  documentElement: Element,
): Element {
  let current: Element | null = start ?? null
  while (current && current !== documentElement) {
    const style = typeof window !== 'undefined' ? window.getComputedStyle(current) : null
    if (
      isScrollableOverflow(style?.overflowY) &&
      current.scrollHeight > current.clientHeight &&
      current.clientHeight > 0
    ) {
      return current
    }
    current = current.parentElement
  }
  return documentElement
}

/** 供滚动容器是 documentElement 时复用：读它对外的滚动位置与可视高度。 */
export function readScrollMetrics(container: Element): {
  scrollTop: number
  viewportHeight: number
  scrollHeight: number
} {
  if (container === container.ownerDocument?.documentElement) {
    return {
      scrollTop: window.scrollY,
      viewportHeight: window.innerHeight,
      scrollHeight: document.documentElement.scrollHeight,
    }
  }
  return {
    scrollTop: container.scrollTop,
    viewportHeight: container.clientHeight,
    scrollHeight: container.scrollHeight,
  }
}

/**
 * 判断滚动到哪个分组。
 *
 * 规则：取最后一个「顶部已越过判定线」的分组；滚到页面底部时直接归到最后一个分组，
 * 否则末尾的短分组会永远高亮不到（它的顶部可能一直停在判定线下方）。
 */
export function resolveActiveSection(metrics: ScrollMetrics): number {
  const { offsets, scrollTop, viewportHeight, scrollHeight } = metrics
  if (!offsets.length) {
    return -1
  }

  const threshold = metrics.threshold ?? 120
  // 整页装得下视口时没有滚动可言，别把最后一项当成当前分组。
  const scrollable = scrollHeight > viewportHeight + 8
  const atBottom = scrollable && scrollTop + viewportHeight >= scrollHeight - 8
  if (atBottom) {
    return offsets.length - 1
  }

  const line = scrollTop + threshold
  let active = 0
  for (let index = 0; index < offsets.length; index += 1) {
    if (offsets[index] <= line) {
      active = index
    } else {
      break
    }
  }
  return active
}

/** 序列化用于比较「有没有改动」，键顺序不影响结果。 */
export function serializeForComparison(value: unknown): string {
  return JSON.stringify(sortKeys(value))
}

function sortKeys(value: unknown): unknown {
  if (Array.isArray(value)) {
    return value.map(sortKeys)
  }
  if (value && typeof value === 'object') {
    const record = value as Record<string, unknown>
    return Object.keys(record)
      .sort()
      .reduce<Record<string, unknown>>((result, key) => {
        result[key] = sortKeys(record[key])
        return result
      }, {})
  }
  return value
}
