import { describe, expect, it } from 'vitest'
import {
  buildMyDocsBackupFilename,
  parseMyDocsBackup,
  MY_DOCS_BACKUP_KIND,
  MY_DOCS_BACKUP_VERSION,
} from './my-docs-backup'

describe('my-docs-backup', () => {
  it('parses a valid backup file', () => {
    const backup = parseMyDocsBackup(
      JSON.stringify({
        kind: MY_DOCS_BACKUP_KIND,
        version: MY_DOCS_BACKUP_VERSION,
        exportedAt: '2026-07-05T08:00:00.000Z',
        settings: {
          defaultSort: 'priorityAsc',
          defaultLibraryName: 'library-a',
        },
        libraries: [
          {
            name: 'library-a',
            spec: {
              title: '文档库 A',
              slug: 'library-a',
            },
          },
        ],
        docs: [
          {
            name: 'doc-a',
            spec: {
              title: '文档 A',
              slug: 'doc-a',
              libraryName: 'library-a',
              parent: '',
              raw: '# title',
            },
          },
        ],
      }),
    )

    expect(backup.settings.defaultSort).toBe('priorityAsc')
    expect(backup.settings.defaultLibraryName).toBe('library-a')
    expect(backup.libraries[0].spec.priority).toBe(0)
    expect(backup.docs[0].spec.rawType).toBe('markdown')
  })

  it('rejects invalid document references', () => {
    expect(() =>
      parseMyDocsBackup(
        JSON.stringify({
          kind: MY_DOCS_BACKUP_KIND,
          version: MY_DOCS_BACKUP_VERSION,
          exportedAt: '2026-07-05T08:00:00.000Z',
          settings: {},
          libraries: [],
          docs: [
            {
              name: 'doc-a',
              spec: {
                title: '文档 A',
                slug: 'doc-a',
                libraryName: 'missing-library',
              },
            },
          ],
        }),
      ),
    ).toThrow('指向了不存在的文档库')
  })

  it('builds a stable filename', () => {
    expect(buildMyDocsBackupFilename('2026-07-05T08:00:00.000Z')).toBe(
      'my-docs-backup-20260705T080000Z.json',
    )
  })
})
