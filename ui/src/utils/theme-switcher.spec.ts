/**
 * 前台明暗主题切换按钮的行为验证。
 *
 * theme.html 的脚本是内联在模板里的（页面直出，无法作为模块 import），
 * 这里直接把模板里的脚本抽出来，在 jsdom 里跑一遍，覆盖：
 * 跟随站点 / 系统信号、手动选择优先且不被观察器改回、刷新后保持、清除后恢复跟随。
 *
 * 另外校验样式表的两组主题变量：jsdom 不求解自定义属性的级联，
 * 所以这里断言「浅色块必须在深色块之后、且声明了整套变量」，
 * 这是浏览器里手动切浅色能压过站点 dark 标记的前提。
 *
 * @vitest-environment jsdom
 */
import { afterEach, describe, expect, it } from 'vitest'
import templateHtml from '../../../src/main/resources/templates/docs/modules/theme.html?raw'

const originalMatchMedia = window.matchMedia

afterEach(() => {
  window.matchMedia = originalMatchMedia
})

function themeScript(): string {
  const start = templateHtml.indexOf('<script>')
  const end = templateHtml.indexOf('</script>', start)
  if (start < 0 || end < 0) {
    throw new Error('theme.html 里找不到内联脚本')
  }
  return templateHtml.slice(start + '<script>'.length, end)
}

interface Options {
  classes?: string[]
  systemDark?: boolean
  storage?: Record<string, string>
}

function setup(options: Options = {}) {
  // 每个用例都从干净的 html/body 开始：上一个用例可能给根元素留了
  // data-color-scheme / class，主题检测会把它当成站点信号读走。
  document.documentElement.className = ''
  ;[...document.documentElement.attributes].forEach((attribute) => {
    if (attribute.name !== 'lang') {
      document.documentElement.removeAttribute(attribute.name)
    }
  })
  document.documentElement.innerHTML = '<head></head><body></body>'

  const storage = new Map(Object.entries(options.storage ?? {}))
  ;(options.classes ?? []).forEach((name) => document.documentElement.classList.add(name))

  let systemDark = !!options.systemDark
  const mediaListeners: Array<() => void> = []
  const storageListeners: Array<(event: { key: string }) => void> = []
  const mediaQuery = {
    get matches() {
      return systemDark
    },
    addEventListener: (_type: string, handler: () => void) => mediaListeners.push(handler),
    addListener: (handler: () => void) => mediaListeners.push(handler),
    removeEventListener: () => {},
    removeListener: () => {},
  }

  const restoreMatchMedia = window.matchMedia
  const originalLocalStorage = Object.getOwnPropertyDescriptor(window, 'localStorage')
  window.matchMedia = (() => mediaQuery) as unknown as typeof window.matchMedia
  Object.defineProperty(window, 'localStorage', {
    configurable: true,
    value: {
      getItem: (key: string) => (storage.has(key) ? storage.get(key)! : null),
      setItem: (key: string, value: string) => storage.set(key, String(value)),
      removeItem: (key: string) => storage.delete(key),
      clear: () => storage.clear(),
    },
  })

  const originalAddEventListener = window.addEventListener.bind(window)
  window.addEventListener = ((type: string, handler: EventListener) => {
    if (type === 'storage') {
      storageListeners.push(handler as unknown as (event: { key: string }) => void)
    }
    return originalAddEventListener(type, handler)
  }) as typeof window.addEventListener

  // eslint-disable-next-line no-eval
  window.eval(themeScript())
  // 模板里的脚本在 head 执行，按钮注入挂在 DOMContentLoaded 上。
  if (document.readyState === 'loading') {
    document.dispatchEvent(new Event('DOMContentLoaded', { bubbles: true }))
  }

  return {
    storage,
    effective: () => document.documentElement.getAttribute('data-mdocs-effective-scheme'),
    manualMarker: () => document.documentElement.getAttribute('data-mdocs-manual-scheme'),
    switcher: () => document.getElementById('mdocs-theme-switcher-button'),
    click: () => document.getElementById('mdocs-theme-switcher-button')?.click(),
    setSystemDark: (value: boolean) => {
      systemDark = value
      mediaListeners.forEach((handler) => handler())
    },
    fireStorage: (key: string) => storageListeners.forEach((handler) => handler({ key })),
    cleanup: () => {
      window.matchMedia = restoreMatchMedia
      window.addEventListener = originalAddEventListener
      if (originalLocalStorage) {
        Object.defineProperty(window, 'localStorage', originalLocalStorage)
      }
    },
  }
}

function withEnv(options: Options, run: (env: ReturnType<typeof setup>) => void) {
  const env = setup(options)
  try {
    run(env)
  } finally {
    env.cleanup()
  }
}

/** 抽出某组选择器所在的 CSS 块，用于校验主题变量的声明顺序。 */
function schemeBlock(startMarker: string): { index: number; body: string } {
  const index = templateHtml.indexOf(startMarker)
  expect(index, `模板里找不到 ${startMarker}`).toBeGreaterThan(-1)
  const open = templateHtml.indexOf('{', index)
  const close = templateHtml.indexOf('}', open)
  return { index, body: templateHtml.slice(open, close) }
}

const DARK_BLOCK_MARKER = 'html[data-mdocs-effective-scheme="dark"]'
const LIGHT_BLOCK_MARKER = 'html[data-mdocs-effective-scheme="light"]'

describe('docs theme switcher', () => {
  it('follows the site theme and injects a labelled button', () => {
    withEnv({ classes: ['dark'], systemDark: false }, (env) => {
      expect(env.effective()).toBe('dark')
      expect(env.switcher()).not.toBeNull()
      expect(env.switcher()?.getAttribute('aria-label')).toBe('切换到浅色主题')
      expect(env.switcher()?.querySelector('svg')).not.toBeNull()
      expect(env.manualMarker()).toBeNull()
    })
  })

  it('keeps the manual choice even when the site and system change', () => {
    withEnv({ classes: ['dark'], systemDark: true }, (env) => {
      env.click()
      expect(env.effective()).toBe('light')
      expect(env.storage.get('mdocs:color-scheme')).toBe('light')

      document.body.classList.remove('dark')
      document.body.classList.add('light')
      expect(env.effective()).toBe('light')

      env.setSystemDark(false)
      expect(env.effective()).toBe('light')

      env.click()
      expect(env.effective()).toBe('dark')
      expect(env.storage.get('mdocs:color-scheme')).toBe('dark')
    })
  })

  it('marks the manual choice so the stylesheet can override the site theme', () => {
    withEnv({ classes: ['dark'], systemDark: false }, (env) => {
      expect(env.manualMarker()).toBeNull()
      env.click()
      expect(env.manualMarker()).toBe('light')
      env.click()
      expect(env.manualMarker()).toBe('dark')
    })
  })

  it('drops the manual marker after the override is cleared', () => {
    withEnv({ classes: ['dark'], systemDark: false, storage: { 'mdocs:color-scheme': 'light' } }, (env) => {
      expect(env.manualMarker()).toBe('light')
      env.storage.delete('mdocs:color-scheme')
      env.fireStorage('mdocs:color-scheme')
      expect(env.manualMarker()).toBeNull()
    })
  })

  it('restores the manual choice after a reload', () => {
    withEnv({ classes: ['dark'], systemDark: true, storage: { 'mdocs:color-scheme': 'light' } }, (env) => {
      expect(env.effective()).toBe('light')
      expect(env.switcher()?.getAttribute('aria-label')).toBe('切换到深色主题')
    })
  })

  it('goes back to following the site when the override is cleared', () => {
    withEnv({ classes: ['dark'], systemDark: false, storage: { 'mdocs:color-scheme': 'light' } }, (env) => {
      expect(env.effective()).toBe('light')
      env.storage.delete('mdocs:color-scheme')
      env.fireStorage('mdocs:color-scheme')
      expect(env.effective()).toBe('dark')
    })
  })

  it('follows the system when the site reports auto', () => {
    withEnv({ classes: ['color-scheme-auto'], systemDark: true }, (env) => {
      expect(env.effective()).toBe('dark')
      env.setSystemDark(false)
      expect(env.effective()).toBe('light')
    })
  })

  it('reads the theme from data-color-scheme as well as classes', () => {
    withEnv({ systemDark: false }, (env) => {
      document.body.setAttribute('data-color-scheme', 'dark')
      env.fireStorage('theme')
      expect(env.effective()).toBe('dark')
    })
  })

  it('does not touch storage keys owned by the theme or other plugins', () => {
    withEnv({ classes: ['dark'], systemDark: false, storage: { StackColorScheme: 'dark' } }, (env) => {
      env.click()
      expect(env.storage.get('StackColorScheme')).toBe('dark')
      expect([...env.storage.keys()].sort()).toEqual(['StackColorScheme', 'mdocs:color-scheme'])
    })
  })

  it('declares the full light token set after the dark block', () => {
    const dark = schemeBlock(DARK_BLOCK_MARKER)
    const light = schemeBlock(LIGHT_BLOCK_MARKER)

    // 同权重时靠源码顺序取胜：浅色块必须在深色块之后。
    expect(light.index).toBeGreaterThan(dark.index)

    const tokens = [
      '--mdocs-bg',
      '--mdocs-surface',
      '--mdocs-surface-soft',
      '--mdocs-text',
      '--mdocs-text-strong',
      '--mdocs-muted',
      '--mdocs-border',
      '--mdocs-accent',
      '--mdocs-accent-soft',
      '--mdocs-code-bg',
      '--mdocs-code-text',
      '--mdocs-inline-code-bg',
    ]
    tokens.forEach((token) => {
      expect(dark.body, `深色块缺少 ${token}`).toContain(token)
      expect(light.body, `浅色块缺少 ${token}`).toContain(token)
    })

    // 手动标记必须参与选择器，否则「手动切浅色」会被站点 dark 标记压回去。
    // 选择器列表从上一个规则块的结束大括号开始找，避免被中间的长注释干扰。
    const selectorStart = templateHtml.lastIndexOf('}', light.index) + 1
    const selectors = templateHtml.slice(selectorStart, templateHtml.indexOf('{', light.index))
    expect(selectors).toContain('data-mdocs-effective-scheme="light"')
    expect(selectors).toContain('[data-mdocs-manual-scheme="dark"]')
    expect(light.body).toContain('color-scheme: light')
  })
})
