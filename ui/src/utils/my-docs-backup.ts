import type { Doc, DocLibrary } from '../api/generated'
import { parseMyDocsSettings, type MyDocsSettings } from './my-docs-settings'

export const MY_DOCS_BACKUP_KIND = 'my-docs-backup'
export const MY_DOCS_BACKUP_VERSION = 1

export interface DocLibraryBackupRecord {
  name: string
  spec: {
    title: string
    slug: string
    description: string
    cover: string
    priority: number
    customHeadHtml: string
    customBodyHtml: string
  }
}

export interface DocBackupRecord {
  name: string
  spec: {
    title: string
    slug: string
    libraryName: string
    parent: string
    priority: number
    raw: string
    rawType: string
    published: boolean
    publishTime: string | null
    customHeadHtml: string
    customBodyHtml: string
  }
}

export interface MyDocsBackupFile {
  kind: typeof MY_DOCS_BACKUP_KIND
  version: typeof MY_DOCS_BACKUP_VERSION
  exportedAt: string
  settings: MyDocsSettings
  libraries: DocLibraryBackupRecord[]
  docs: DocBackupRecord[]
}

function requireString(value: unknown, label: string): string {
  if (typeof value !== 'string' || !value.trim()) {
    throw new Error(`${label}不能为空。`)
  }
  return value.trim()
}

function optionalString(value: unknown): string {
  return typeof value === 'string' ? value.trim() : ''
}

function integer(value: unknown, fallback = 0): number {
  const parsed = Math.trunc(Number(value))
  return Number.isFinite(parsed) ? parsed : fallback
}

function booleanValue(value: unknown, fallback = false): boolean {
  return typeof value === 'boolean' ? value : fallback
}

function dateTimeOrNull(value: unknown): string | null {
  if (typeof value !== 'string' || !value.trim()) {
    return null
  }
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) {
    throw new Error('发布时间格式无效。')
  }
  return parsed.toISOString()
}

function sortLibraries(records: DocLibraryBackupRecord[]): DocLibraryBackupRecord[] {
  return [...records].sort(
    (a, b) =>
      a.spec.priority - b.spec.priority
      || a.spec.title.localeCompare(b.spec.title, 'zh-Hans-CN')
      || a.name.localeCompare(b.name, 'zh-Hans-CN'),
  )
}

function sortDocs(records: DocBackupRecord[]): DocBackupRecord[] {
  return [...records].sort(
    (a, b) =>
      a.spec.libraryName.localeCompare(b.spec.libraryName, 'zh-Hans-CN')
      || a.spec.priority - b.spec.priority
      || a.spec.title.localeCompare(b.spec.title, 'zh-Hans-CN')
      || a.name.localeCompare(b.name, 'zh-Hans-CN'),
  )
}

export function buildMyDocsBackup(
  settings: MyDocsSettings,
  libraries: DocLibrary[],
  docs: Doc[],
): MyDocsBackupFile {
  const libraryRecords = sortLibraries(
    libraries.map((library) => ({
      name: requireString(library.metadata?.name, '文档库名称'),
      spec: {
        title: requireString(library.spec?.title, '文档库标题'),
        slug: requireString(library.spec?.slug, '文档库别名'),
        description: optionalString(library.spec?.description),
        cover: optionalString(library.spec?.cover),
        priority: integer(library.spec?.priority, 0),
        customHeadHtml: optionalString(library.spec?.customHeadHtml),
        customBodyHtml: optionalString(library.spec?.customBodyHtml),
      },
    })),
  )

  const docRecords = sortDocs(
    docs.map((doc) => ({
      name: requireString(doc.metadata?.name, '文档名称'),
      spec: {
        title: requireString(doc.spec?.title, '文档标题'),
        slug: requireString(doc.spec?.slug, '文档别名'),
        libraryName: requireString(doc.spec?.libraryName, '所属文档库'),
        parent: optionalString(doc.spec?.parent),
        priority: integer(doc.spec?.priority, 0),
        raw: typeof doc.spec?.raw === 'string' ? doc.spec.raw : '',
        rawType: optionalString(doc.spec?.rawType) || 'markdown',
        published: booleanValue(doc.spec?.published, false),
        publishTime: dateTimeOrNull(doc.spec?.publishTime),
        customHeadHtml: optionalString(doc.spec?.customHeadHtml),
        customBodyHtml: optionalString(doc.spec?.customBodyHtml),
      },
    })),
  )

  return parseMyDocsBackup(
    JSON.stringify({
      kind: MY_DOCS_BACKUP_KIND,
      version: MY_DOCS_BACKUP_VERSION,
      exportedAt: new Date().toISOString(),
      settings,
      libraries: libraryRecords,
      docs: docRecords,
    }),
  )
}

function normalizeLibraryRecord(value: unknown, index: number): DocLibraryBackupRecord {
  if (!value || typeof value !== 'object') {
    throw new Error(`第 ${index + 1} 个文档库记录无效。`)
  }
  const record = value as Record<string, unknown>
  const spec = (record.spec as Record<string, unknown> | undefined) ?? {}
  return {
    name: requireString(record.name, `第 ${index + 1} 个文档库记录名称`),
    spec: {
      title: requireString(spec.title, `第 ${index + 1} 个文档库标题`),
      slug: requireString(spec.slug, `第 ${index + 1} 个文档库别名`),
      description: optionalString(spec.description),
      cover: optionalString(spec.cover),
      priority: integer(spec.priority, 0),
      customHeadHtml: optionalString(spec.customHeadHtml),
      customBodyHtml: optionalString(spec.customBodyHtml),
    },
  }
}

function normalizeDocRecord(value: unknown, index: number): DocBackupRecord {
  if (!value || typeof value !== 'object') {
    throw new Error(`第 ${index + 1} 篇文档记录无效。`)
  }
  const record = value as Record<string, unknown>
  const spec = (record.spec as Record<string, unknown> | undefined) ?? {}
  return {
    name: requireString(record.name, `第 ${index + 1} 篇文档记录名称`),
    spec: {
      title: requireString(spec.title, `第 ${index + 1} 篇文档标题`),
      slug: requireString(spec.slug, `第 ${index + 1} 篇文档别名`),
      libraryName: requireString(spec.libraryName, `第 ${index + 1} 篇文档所属文档库`),
      parent: optionalString(spec.parent),
      priority: integer(spec.priority, 0),
      raw: typeof spec.raw === 'string' ? spec.raw : '',
      rawType: optionalString(spec.rawType) || 'markdown',
      published: booleanValue(spec.published, false),
      publishTime: dateTimeOrNull(spec.publishTime),
      customHeadHtml: optionalString(spec.customHeadHtml),
      customBodyHtml: optionalString(spec.customBodyHtml),
    },
  }
}

function validateBackup(backup: MyDocsBackupFile) {
  const libraryNames = new Set<string>()
  const librarySlugs = new Set<string>()
  for (const library of backup.libraries) {
    if (libraryNames.has(library.name)) {
      throw new Error(`文档库名称 ${library.name} 重复。`)
    }
    if (librarySlugs.has(library.spec.slug)) {
      throw new Error(`文档库别名 ${library.spec.slug} 重复。`)
    }
    libraryNames.add(library.name)
    librarySlugs.add(library.spec.slug)
  }

  const docNames = new Set<string>()
  const docSlugKeys = new Set<string>()
  const docParentMap = new Map<string, string>()
  for (const doc of backup.docs) {
    if (docNames.has(doc.name)) {
      throw new Error(`文档名称 ${doc.name} 重复。`)
    }
    if (!libraryNames.has(doc.spec.libraryName)) {
      throw new Error(`文档 ${doc.name} 指向了不存在的文档库 ${doc.spec.libraryName}。`)
    }
    const slugKey = `${doc.spec.libraryName}:${doc.spec.slug}`
    if (docSlugKeys.has(slugKey)) {
      throw new Error(`文档别名 ${doc.spec.slug} 在文档库 ${doc.spec.libraryName} 中重复。`)
    }
    if (doc.spec.parent && doc.spec.parent === doc.name) {
      throw new Error(`文档 ${doc.name} 不能把自己作为父节点。`)
    }
    docNames.add(doc.name)
    docSlugKeys.add(slugKey)
    docParentMap.set(doc.name, doc.spec.parent)
  }

  for (const doc of backup.docs) {
    if (doc.spec.parent && !docNames.has(doc.spec.parent)) {
      throw new Error(`文档 ${doc.name} 指向了不存在的父文档 ${doc.spec.parent}。`)
    }
  }

  for (const doc of backup.docs) {
    const visited = new Set<string>()
    let current = doc.name
    while (docParentMap.get(current)) {
      const parent = docParentMap.get(current)!
      if (visited.has(parent)) {
        throw new Error(`文档 ${doc.name} 的父子关系存在循环引用。`)
      }
      visited.add(parent)
      current = parent
    }
  }
}

export function parseMyDocsBackup(raw: string): MyDocsBackupFile {
  let parsed: Record<string, unknown>
  try {
    parsed = JSON.parse(raw) as Record<string, unknown>
  } catch {
    throw new Error('备份文件不是合法的 JSON。')
  }

  if (parsed.kind !== MY_DOCS_BACKUP_KIND) {
    throw new Error('这不是 my-docs 的备份文件。')
  }
  if (parsed.version !== MY_DOCS_BACKUP_VERSION) {
    throw new Error(`暂不支持版本 ${String(parsed.version)} 的备份文件。`)
  }

  const exportedAt = dateTimeOrNull(parsed.exportedAt)
  if (!exportedAt) {
    throw new Error('备份文件缺少导出时间。')
  }

  const libraries = Array.isArray(parsed.libraries)
    ? parsed.libraries.map((item, index) => normalizeLibraryRecord(item, index))
    : []
  const docs = Array.isArray(parsed.docs)
    ? parsed.docs.map((item, index) => normalizeDocRecord(item, index))
    : []

  const backup: MyDocsBackupFile = {
    kind: MY_DOCS_BACKUP_KIND,
    version: MY_DOCS_BACKUP_VERSION,
    exportedAt,
    settings: parseMyDocsSettings(JSON.stringify(parsed.settings ?? {})),
    libraries: sortLibraries(libraries),
    docs: sortDocs(docs),
  }

  validateBackup(backup)
  return backup
}

export function buildMyDocsBackupFilename(exportedAt: string): string {
  const stamp = exportedAt.replace(/[-:]/g, '').replace(/\.\d{3}Z$/, 'Z')
  return `my-docs-backup-${stamp}.json`
}
