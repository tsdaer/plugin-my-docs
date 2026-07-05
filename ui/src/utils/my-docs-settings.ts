export const MY_DOCS_CONFIG_MAP_NAME = 'my-docs-configmap'
export const MY_DOCS_CONFIG_GROUP = 'basic'

export type DocSort = 'priorityAsc' | 'createdDesc' | 'titleAsc'
export type DocContentTheme = 'light' | 'dark' | 'wechat' | 'ant-design'

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
  renderContentTheme: DocContentTheme
  renderCodeTheme: string
  renderLineNumber: boolean
  renderAutoSpace: boolean
  renderGfmAutoLink: boolean
  renderFootnotes: boolean
  renderMark: boolean
  renderFixTermTypo: boolean
  renderParagraphBeginningSpace: boolean
  renderCodeBlockPreview: boolean
  renderMathBlockPreview: boolean
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
  renderContentTheme: 'light',
  renderCodeTheme: 'github',
  renderLineNumber: false,
  renderAutoSpace: false,
  renderGfmAutoLink: true,
  renderFootnotes: true,
  renderMark: false,
  renderFixTermTypo: false,
  renderParagraphBeginningSpace: false,
  renderCodeBlockPreview: true,
  renderMathBlockPreview: true,
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

const contentThemes = new Set<DocContentTheme>(['light', 'dark', 'wechat', 'ant-design'])
const docSorts = new Set<DocSort>(['priorityAsc', 'createdDesc', 'titleAsc'])

function readBoolean(value: unknown, fallback: boolean): boolean {
  return typeof value === 'boolean' ? value : fallback
}

function readString(value: unknown, fallback: string): string {
  return typeof value === 'string' ? value : fallback
}

function readNumber(value: unknown, fallback: number): number {
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : fallback
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
    .sort((a, b) => a.row - b.row || a.column - b.column || a.libraryName.localeCompare(b.libraryName))
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
    const parsed = JSON.parse(raw) as Partial<MyDocsSettings>
    const defaultSort = docSorts.has(parsed.defaultSort as DocSort)
      ? (parsed.defaultSort as DocSort)
      : defaultMyDocsSettings.defaultSort
    const renderContentTheme = contentThemes.has(parsed.renderContentTheme as DocContentTheme)
      ? (parsed.renderContentTheme as DocContentTheme)
      : defaultMyDocsSettings.renderContentTheme

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
      renderContentTheme,
      renderCodeTheme: readString(parsed.renderCodeTheme, defaultMyDocsSettings.renderCodeTheme),
      renderLineNumber: readBoolean(
        parsed.renderLineNumber,
        defaultMyDocsSettings.renderLineNumber,
      ),
      renderAutoSpace: readBoolean(parsed.renderAutoSpace, defaultMyDocsSettings.renderAutoSpace),
      renderGfmAutoLink: readBoolean(
        parsed.renderGfmAutoLink,
        defaultMyDocsSettings.renderGfmAutoLink,
      ),
      renderFootnotes: readBoolean(
        parsed.renderFootnotes,
        defaultMyDocsSettings.renderFootnotes,
      ),
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
    }
  } catch {
    return cloneDefaultSettings()
  }
}

export function stringifyMyDocsSettings(settings: MyDocsSettings): string {
  return JSON.stringify(settings)
}
