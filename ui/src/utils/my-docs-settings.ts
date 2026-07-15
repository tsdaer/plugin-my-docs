export const MY_DOCS_CONFIG_MAP_NAME = 'my-docs-configmap'
export const MY_DOCS_CONFIG_GROUP = 'basic'

export type DocSort = 'priorityAsc' | 'createdDesc' | 'titleAsc'
export type BuiltInDocContentTheme = 'light' | 'dark' | 'wechat' | 'ant-design'
export type DocContentTheme = BuiltInDocContentTheme | 'custom'

export interface LibraryPageLayoutSetting {
  page: number
  maxRows: number
}

export interface LibraryRowLayoutSetting {
  row: number
  columns: number
}

export interface LibraryPlacementSetting {
  libraryName: string
  row: number
  column: number
}

export interface LibraryFolderTitleSetting {
  row: number
  column: number
  title: string
  description: string
}

export interface MyDocsSettings {
  defaultSort: DocSort
  defaultLibraryName: string
  libraryIndexDefaultColumns: number
  libraryIndexDefaultMaxRows: number
  libraryIndexPageLayouts: LibraryPageLayoutSetting[]
  libraryIndexRowLayouts: LibraryRowLayoutSetting[]
  libraryIndexPlacements: LibraryPlacementSetting[]
  libraryIndexFolderTitles: LibraryFolderTitleSetting[]
  renderContentThemeLight: DocContentTheme
  renderContentThemeDark: DocContentTheme
  renderContentThemeLightUrl: string
  renderContentThemeDarkUrl: string
  renderContentThemeLightClass: string
  renderContentThemeDarkClass: string
  renderCodeThemeLight: string
  renderCodeThemeDark: string
  renderLineNumber: boolean
  renderAutoSpace: boolean
  renderGfmAutoLink: boolean
  renderFootnotes: boolean
  renderMark: boolean
  renderFixTermTypo: boolean
  renderParagraphBeginningSpace: boolean
  renderCodeBlockPreview: boolean
  renderMathBlockPreview: boolean
  customHeadHtml: string
  customBodyHtml: string
}

export const defaultMyDocsSettings: MyDocsSettings = {
  defaultSort: 'priorityAsc',
  defaultLibraryName: '',
  libraryIndexDefaultColumns: 2,
  libraryIndexDefaultMaxRows: 2,
  libraryIndexPageLayouts: [],
  libraryIndexRowLayouts: [],
  libraryIndexPlacements: [],
  libraryIndexFolderTitles: [],
  renderContentThemeLight: 'light',
  renderContentThemeDark: 'dark',
  renderContentThemeLightUrl: '',
  renderContentThemeDarkUrl: '',
  renderContentThemeLightClass: 'markdown-body',
  renderContentThemeDarkClass: 'markdown-body',
  renderCodeThemeLight: 'github',
  renderCodeThemeDark: 'github-dark',
  renderLineNumber: false,
  renderAutoSpace: false,
  renderGfmAutoLink: true,
  renderFootnotes: true,
  renderMark: false,
  renderFixTermTypo: false,
  renderParagraphBeginningSpace: false,
  renderCodeBlockPreview: true,
  renderMathBlockPreview: true,
  customHeadHtml: '',
  customBodyHtml: '',
}

function cloneDefaultSettings(): MyDocsSettings {
  return {
    ...defaultMyDocsSettings,
    libraryIndexPageLayouts: [...defaultMyDocsSettings.libraryIndexPageLayouts],
    libraryIndexRowLayouts: [...defaultMyDocsSettings.libraryIndexRowLayouts],
    libraryIndexPlacements: [...defaultMyDocsSettings.libraryIndexPlacements],
    libraryIndexFolderTitles: [...defaultMyDocsSettings.libraryIndexFolderTitles],
  }
}

const contentThemes = new Set<DocContentTheme>(['light', 'dark', 'wechat', 'ant-design', 'custom'])
const legacyContentThemes = new Set<BuiltInDocContentTheme>([
  'light',
  'dark',
  'wechat',
  'ant-design',
])
const docSorts = new Set<DocSort>(['priorityAsc', 'createdDesc', 'titleAsc'])
const cssClassPattern = /^[A-Za-z_][A-Za-z0-9_-]*$/

function readBoolean(value: unknown, fallback: boolean): boolean {
  return typeof value === 'boolean' ? value : fallback
}

function readString(value: unknown, fallback: string): string {
  return typeof value === 'string' ? value : fallback
}

function normalizeThemeUrl(value: unknown): string {
  const url = readString(value, '').trim()
  if (url.startsWith('/') && !url.startsWith('//')) {
    return url
  }
  try {
    return new URL(url).protocol === 'https:' ? url : ''
  } catch {
    return ''
  }
}

function normalizeThemeClass(value: unknown): string {
  const classes = readString(value, '').trim().split(/\s+/).filter(Boolean)
  if (
    !classes.length ||
    classes.length > 10 ||
    classes.some((item) => !cssClassPattern.test(item))
  ) {
    return 'markdown-body'
  }
  return Array.from(new Set(classes)).join(' ')
}

function readContentTheme(value: unknown, fallback: DocContentTheme): DocContentTheme {
  return contentThemes.has(value as DocContentTheme) ? (value as DocContentTheme) : fallback
}

function normalizePositiveInt(value: unknown, fallback: number, max = 24): number {
  const parsed = Math.floor(Number(value))
  if (!Number.isFinite(parsed) || parsed < 1) {
    return fallback
  }
  return Math.min(parsed, max)
}

function readPageLayouts(value: unknown): LibraryPageLayoutSetting[] {
  if (!Array.isArray(value)) {
    return []
  }

  const seen = new Set<number>()
  return value
    .map((item) => {
      if (!item || typeof item !== 'object') {
        return undefined
      }
      const page = normalizePositiveInt((item as Record<string, unknown>).page, 0, 999)
      const maxRows = normalizePositiveInt((item as Record<string, unknown>).maxRows, 0, 24)
      if (page < 1 || maxRows < 1) {
        return undefined
      }
      if (seen.has(page)) {
        return undefined
      }
      seen.add(page)
      return { page, maxRows }
    })
    .filter((item): item is LibraryPageLayoutSetting => !!item)
    .sort((a, b) => a.page - b.page)
}

function readRowLayouts(value: unknown): LibraryRowLayoutSetting[] {
  if (!Array.isArray(value)) {
    return []
  }

  const seen = new Set<number>()
  return value
    .map((item) => {
      if (!item || typeof item !== 'object') {
        return undefined
      }
      const row = normalizePositiveInt((item as Record<string, unknown>).row, 0, 999)
      const columns = normalizePositiveInt((item as Record<string, unknown>).columns, 0, 24)
      if (row < 1 || columns < 1) {
        return undefined
      }
      if (seen.has(row)) {
        return undefined
      }
      seen.add(row)
      return { row, columns }
    })
    .filter((item): item is LibraryRowLayoutSetting => !!item)
    .sort((a, b) => a.row - b.row)
}

function readPlacements(value: unknown): LibraryPlacementSetting[] {
  if (!Array.isArray(value)) {
    return []
  }

  const seen = new Set<string>()
  return value
    .map((item) => {
      if (!item || typeof item !== 'object') {
        return undefined
      }
      const record = item as Record<string, unknown>
      const libraryName = readString(record.libraryName, '').trim()
      const row = normalizePositiveInt(record.row, 0)
      const column = normalizePositiveInt(record.column, 0, 24)
      if (!libraryName || row < 1 || column < 1) {
        return undefined
      }
      if (seen.has(libraryName)) {
        return undefined
      }
      seen.add(libraryName)
      return { libraryName, row, column }
    })
    .filter((item): item is LibraryPlacementSetting => !!item)
    .sort(
      (a, b) => a.row - b.row || a.column - b.column || a.libraryName.localeCompare(b.libraryName),
    )
}

function readFolderTitles(value: unknown): LibraryFolderTitleSetting[] {
  if (!Array.isArray(value)) {
    return []
  }

  const seen = new Set<string>()
  return value
    .map((item) => {
      if (!item || typeof item !== 'object') {
        return undefined
      }
      const record = item as Record<string, unknown>
      const row = normalizePositiveInt(record.row, 0)
      const column = normalizePositiveInt(record.column, 0, 24)
      const title = readString(record.title, '').trim()
      const description = readString(record.description, '').trim()
      if (row < 1 || column < 1 || !title) {
        return undefined
      }
      const key = `${row}:${column}`
      if (seen.has(key)) {
        return undefined
      }
      seen.add(key)
      return { row, column, title, description }
    })
    .filter((item): item is LibraryFolderTitleSetting => !!item)
    .sort((a, b) => a.row - b.row || a.column - b.column)
}

export function parseMyDocsSettings(raw?: string | null): MyDocsSettings {
  if (!raw) {
    return cloneDefaultSettings()
  }

  try {
    const parsed = JSON.parse(raw) as Partial<MyDocsSettings> & {
      renderContentTheme?: BuiltInDocContentTheme
      renderCodeTheme?: string
    }
    const defaultSort = docSorts.has(parsed.defaultSort as DocSort)
      ? (parsed.defaultSort as DocSort)
      : defaultMyDocsSettings.defaultSort
    const legacyContentTheme = legacyContentThemes.has(
      parsed.renderContentTheme as BuiltInDocContentTheme,
    )
      ? (parsed.renderContentTheme as BuiltInDocContentTheme)
      : undefined
    const legacyCodeTheme = readString(parsed.renderCodeTheme, '').trim()
    const renderContentThemeLight = readContentTheme(
      parsed.renderContentThemeLight,
      legacyContentTheme ?? defaultMyDocsSettings.renderContentThemeLight,
    )
    const renderContentThemeDark = readContentTheme(
      parsed.renderContentThemeDark,
      legacyContentTheme === 'light'
        ? 'dark'
        : (legacyContentTheme ?? defaultMyDocsSettings.renderContentThemeDark),
    )
    const renderContentThemeLightUrl = normalizeThemeUrl(parsed.renderContentThemeLightUrl)
    const renderContentThemeDarkUrl = normalizeThemeUrl(parsed.renderContentThemeDarkUrl)

    return {
      defaultSort,
      defaultLibraryName: readString(
        parsed.defaultLibraryName,
        defaultMyDocsSettings.defaultLibraryName,
      ),
      libraryIndexDefaultColumns: normalizePositiveInt(
        parsed.libraryIndexDefaultColumns,
        defaultMyDocsSettings.libraryIndexDefaultColumns,
        12,
      ),
      libraryIndexDefaultMaxRows: normalizePositiveInt(
        parsed.libraryIndexDefaultMaxRows,
        defaultMyDocsSettings.libraryIndexDefaultMaxRows,
        24,
      ),
      libraryIndexPageLayouts: readPageLayouts(parsed.libraryIndexPageLayouts),
      libraryIndexRowLayouts: readRowLayouts(parsed.libraryIndexRowLayouts),
      libraryIndexPlacements: readPlacements(parsed.libraryIndexPlacements),
      libraryIndexFolderTitles: readFolderTitles(parsed.libraryIndexFolderTitles),
      renderContentThemeLight:
        renderContentThemeLight === 'custom' && !renderContentThemeLightUrl
          ? defaultMyDocsSettings.renderContentThemeLight
          : renderContentThemeLight,
      renderContentThemeDark:
        renderContentThemeDark === 'custom' && !renderContentThemeDarkUrl
          ? defaultMyDocsSettings.renderContentThemeDark
          : renderContentThemeDark,
      renderContentThemeLightUrl,
      renderContentThemeDarkUrl,
      renderContentThemeLightClass: normalizeThemeClass(parsed.renderContentThemeLightClass),
      renderContentThemeDarkClass: normalizeThemeClass(parsed.renderContentThemeDarkClass),
      renderCodeThemeLight:
        readString(
          parsed.renderCodeThemeLight,
          legacyCodeTheme || defaultMyDocsSettings.renderCodeThemeLight,
        ).trim() || defaultMyDocsSettings.renderCodeThemeLight,
      renderCodeThemeDark:
        readString(
          parsed.renderCodeThemeDark,
          legacyCodeTheme === 'github'
            ? defaultMyDocsSettings.renderCodeThemeDark
            : legacyCodeTheme || defaultMyDocsSettings.renderCodeThemeDark,
        ).trim() || defaultMyDocsSettings.renderCodeThemeDark,
      renderLineNumber: readBoolean(
        parsed.renderLineNumber,
        defaultMyDocsSettings.renderLineNumber,
      ),
      renderAutoSpace: readBoolean(parsed.renderAutoSpace, defaultMyDocsSettings.renderAutoSpace),
      renderGfmAutoLink: readBoolean(
        parsed.renderGfmAutoLink,
        defaultMyDocsSettings.renderGfmAutoLink,
      ),
      renderFootnotes: readBoolean(parsed.renderFootnotes, defaultMyDocsSettings.renderFootnotes),
      renderMark: readBoolean(parsed.renderMark, defaultMyDocsSettings.renderMark),
      renderFixTermTypo: readBoolean(
        parsed.renderFixTermTypo,
        defaultMyDocsSettings.renderFixTermTypo,
      ),
      renderParagraphBeginningSpace: readBoolean(
        parsed.renderParagraphBeginningSpace,
        defaultMyDocsSettings.renderParagraphBeginningSpace,
      ),
      renderCodeBlockPreview: readBoolean(
        parsed.renderCodeBlockPreview,
        defaultMyDocsSettings.renderCodeBlockPreview,
      ),
      renderMathBlockPreview: readBoolean(
        parsed.renderMathBlockPreview,
        defaultMyDocsSettings.renderMathBlockPreview,
      ),
      customHeadHtml: readString(parsed.customHeadHtml, defaultMyDocsSettings.customHeadHtml),
      customBodyHtml: readString(parsed.customBodyHtml, defaultMyDocsSettings.customBodyHtml),
    }
  } catch {
    return cloneDefaultSettings()
  }
}

export function stringifyMyDocsSettings(settings: MyDocsSettings): string {
  return JSON.stringify(settings)
}
