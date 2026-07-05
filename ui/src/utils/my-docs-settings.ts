export const MY_DOCS_CONFIG_MAP_NAME = 'my-docs-configmap'
export const MY_DOCS_CONFIG_GROUP = 'basic'

export type DocSort = 'priorityAsc' | 'createdDesc' | 'titleAsc'
export type DocContentTheme = 'light' | 'dark' | 'wechat' | 'ant-design'

export interface MyDocsSettings {
  defaultSort: DocSort
  pageSize: number
  defaultLibraryName: string
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
  pageSize: 20,
  defaultLibraryName: '',
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

export function parseMyDocsSettings(raw?: string | null): MyDocsSettings {
  if (!raw) {
    return { ...defaultMyDocsSettings }
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
      pageSize: readNumber(parsed.pageSize, defaultMyDocsSettings.pageSize),
      defaultLibraryName: readString(
        parsed.defaultLibraryName,
        defaultMyDocsSettings.defaultLibraryName,
      ),
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
    return { ...defaultMyDocsSettings }
  }
}

export function stringifyMyDocsSettings(settings: MyDocsSettings): string {
  return JSON.stringify(settings)
}
