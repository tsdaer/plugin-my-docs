import { describe, expect, it } from 'vitest'
import {
  MEDIA_PROXY_MAX_BYTES,
  MEDIA_PROXY_MIN_BYTES,
  defaultMyDocsSettings,
  parseMyDocsSettings,
  stringifyMyDocsSettings,
} from './my-docs-settings'

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
          libraryIndexPlacements: [{ libraryName: 'guide', row: 2, column: 3 }],
          libraryIndexFolderTitles: [
            { row: 2, column: 3, title: '入门合集', description: '集中展示新手文档库' },
          ],
          renderContentThemeLightUrl: 'https://cdn.example.com/light.css',
          renderContentThemeDarkUrl: '/themes/dark.css',
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
      libraryIndexPlacements: [{ libraryName: 'guide', row: 2, column: 3 }],
      libraryIndexFolderTitles: [
        { row: 2, column: 3, title: '入门合集', description: '集中展示新手文档库' },
      ],
      renderContentThemeLightUrl: 'https://cdn.example.com/light.css',
      renderContentThemeDarkUrl: '/themes/dark.css',
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
      renderContentThemeLightUrl: 'https://cdn.example.com/light.css',
      renderContentThemeDarkUrl: 'https://cdn.example.com/dark.css',
      renderContentThemeDarkClass: 'markdown-body theme-dark',
      renderCodeThemeLight: 'github',
      renderCodeThemeDark: 'monokai',
    })

    expect(JSON.parse(raw)).toMatchObject({
      renderContentThemeLightUrl: 'https://cdn.example.com/light.css',
      renderContentThemeDarkUrl: 'https://cdn.example.com/dark.css',
      renderContentThemeDarkClass: 'markdown-body theme-dark',
      renderCodeThemeDark: 'monokai',
    })
  })

  it('migrates legacy single code themes to light and dark settings', () => {
    expect(
      parseMyDocsSettings(
        JSON.stringify({ renderContentTheme: 'light', renderCodeTheme: 'github' }),
      ),
    ).toMatchObject({
      renderCodeThemeLight: 'github',
      renderCodeThemeDark: 'github-dark',
    })

    expect(
      parseMyDocsSettings(
        JSON.stringify({ renderContentTheme: 'wechat', renderCodeTheme: 'monokai' }),
      ),
    ).toMatchObject({
      renderCodeThemeLight: 'monokai',
      renderCodeThemeDark: 'monokai',
    })
  })

  it('rejects unsafe custom theme urls and classes', () => {
    expect(
      parseMyDocsSettings(
        JSON.stringify({
          renderContentThemeLightUrl: 'javascript:alert(1)',
          renderContentThemeLightClass: 'valid bad.class',
        }),
      ),
    ).toMatchObject({
      renderContentThemeLightUrl: '',
      renderContentThemeLightClass: 'markdown-body',
    })
  })

  it('keeps media and proxy fields that the settings form now owns', () => {
    // 这些字段此前只写在 settings.yaml 里，文档设置页保存时会把它们整组覆盖掉，
    // 于是既看不到、也会被静默清空。这里守住「解析后再序列化不丢字段」。
    const parsed = parseMyDocsSettings(
      JSON.stringify({
        renderCopyButtons: false,
        renderImageZoom: false,
        renderMediaEmbed: false,
        mediaProxyEnabled: true,
        mediaProxyAllowedHosts: ['media.example.com', '*.r2.cloudflarestorage.com'],
        mediaProxyRequestHeaders: ['media.example.com: Authorization: Bearer x'],
        mediaProxyMaxBytes: 10485760,
      }),
    )

    expect(parsed).toMatchObject({
      renderCopyButtons: false,
      renderImageZoom: false,
      renderMediaEmbed: false,
      mediaProxyEnabled: true,
      mediaProxyAllowedHosts: ['media.example.com', '*.r2.cloudflarestorage.com'],
      mediaProxyRequestHeaders: ['media.example.com: Authorization: Bearer x'],
      mediaProxyMaxBytes: 10485760,
    })

    const roundTripped = parseMyDocsSettings(stringifyMyDocsSettings(parsed))
    expect(roundTripped).toEqual(parsed)
  })

  it('reads proxy host and header lists from newline text as well as arrays', () => {
    expect(
      parseMyDocsSettings(
        JSON.stringify({
          mediaProxyAllowedHosts: 'media.example.com\n\n  *.r2.cloudflarestorage.com  \nmedia.example.com',
          mediaProxyRequestHeaders: 'media.example.com: X-Token: t',
        }),
      ),
    ).toMatchObject({
      mediaProxyAllowedHosts: ['media.example.com', '*.r2.cloudflarestorage.com'],
      mediaProxyRequestHeaders: ['media.example.com: X-Token: t'],
    })
  })

  it('clamps the proxy size limit and defaults the media switches', () => {
    const defaults = parseMyDocsSettings()
    expect(defaults.renderCopyButtons).toBe(true)
    expect(defaults.renderImageZoom).toBe(true)
    expect(defaults.renderMediaEmbed).toBe(true)
    expect(defaults.mediaProxyEnabled).toBe(false)
    expect(defaults.mediaProxyMaxBytes).toBe(536870912)

    expect(
      parseMyDocsSettings(JSON.stringify({ mediaProxyMaxBytes: 1 })).mediaProxyMaxBytes,
    ).toBe(MEDIA_PROXY_MIN_BYTES)
    expect(
      parseMyDocsSettings(JSON.stringify({ mediaProxyMaxBytes: 99999999999 })).mediaProxyMaxBytes,
    ).toBe(MEDIA_PROXY_MAX_BYTES)
    expect(
      parseMyDocsSettings(JSON.stringify({ mediaProxyMaxBytes: 'abc' })).mediaProxyMaxBytes,
    ).toBe(536870912)
  })
})
