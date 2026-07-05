import { describe, expect, it } from 'vitest'
import { defaultMyDocsSettings, parseMyDocsSettings, stringifyMyDocsSettings } from './my-docs-settings'

describe('my-docs settings', () => {
  it('returns defaults for empty input', () => {
    expect(parseMyDocsSettings()).toEqual(defaultMyDocsSettings)
  })

  it('merges partial values with defaults', () => {
    expect(
      parseMyDocsSettings(
        JSON.stringify({
          libraryIndexDefaultColumns: 3,
          libraryIndexDefaultMaxRows: 4,
          libraryIndexPageLayouts: [
            { page: 3, maxRows: 5 },
            { page: 2, maxRows: 1 },
          ],
          libraryIndexRowLayouts: [
            { row: 5, columns: 4 },
            { row: 2, columns: 1 },
          ],
          libraryIndexPlacements: [
            { libraryName: 'guide', row: 2, column: 3 },
          ],
          libraryIndexFolderTitles: [
            { row: 2, column: 3, title: '入门合集', description: '集中展示新手文档库' },
          ],
          renderContentTheme: 'wechat',
          renderLineNumber: true,
          renderAutoSpace: true,
        }),
      ),
    ).toEqual({
      ...defaultMyDocsSettings,
      libraryIndexDefaultColumns: 3,
      libraryIndexDefaultMaxRows: 4,
      libraryIndexPageLayouts: [
        { page: 2, maxRows: 1 },
        { page: 3, maxRows: 5 },
      ],
      libraryIndexRowLayouts: [
        { row: 2, columns: 1 },
        { row: 5, columns: 4 },
      ],
      libraryIndexPlacements: [
        { libraryName: 'guide', row: 2, column: 3 },
      ],
      libraryIndexFolderTitles: [
        { row: 2, column: 3, title: '入门合集', description: '集中展示新手文档库' },
      ],
      renderContentTheme: 'wechat',
      renderLineNumber: true,
      renderAutoSpace: true,
    })
  })

  it('drops invalid layout rows and duplicate placements', () => {
    expect(
      parseMyDocsSettings(
        JSON.stringify({
          libraryIndexDefaultColumns: 0,
          libraryIndexDefaultMaxRows: 0,
          libraryIndexPageLayouts: [
            { page: 1, maxRows: 4 },
            { page: 1, maxRows: 2 },
            { page: -1, maxRows: 3 },
          ],
          libraryIndexRowLayouts: [
            { row: 2, columns: 3 },
            { row: 2, columns: 1 },
            { row: 0, columns: 1 },
          ],
          libraryIndexPlacements: [
            { libraryName: 'guide', row: 1, column: 2 },
            { libraryName: 'guide', row: 2, column: 1 },
            { libraryName: '', row: 1, column: 1 },
          ],
          libraryIndexFolderTitles: [
            { row: 1, column: 2, title: '合集', description: '说明' },
            { row: 1, column: 2, title: '重复' },
            { row: 0, column: 3, title: '无效' },
          ],
        }),
      ),
    ).toMatchObject({
      libraryIndexDefaultColumns: 2,
      libraryIndexDefaultMaxRows: 2,
      libraryIndexPageLayouts: [{ page: 1, maxRows: 4 }],
      libraryIndexRowLayouts: [{ row: 2, columns: 3 }],
      libraryIndexPlacements: [{ libraryName: 'guide', row: 1, column: 2 }],
      libraryIndexFolderTitles: [{ row: 1, column: 2, title: '合集', description: '说明' }],
    })
  })

  it('stringifies settings for config map storage', () => {
    const raw = stringifyMyDocsSettings({
      ...defaultMyDocsSettings,
      renderContentTheme: 'dark',
      renderCodeTheme: 'monokai',
    })

    expect(JSON.parse(raw)).toMatchObject({
      renderContentTheme: 'dark',
      renderCodeTheme: 'monokai',
    })
  })
})
