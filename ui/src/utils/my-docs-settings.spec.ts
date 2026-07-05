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
          pageSize: 50,
          renderContentTheme: 'wechat',
          renderLineNumber: true,
          renderAutoSpace: true,
        }),
      ),
    ).toEqual({
      ...defaultMyDocsSettings,
      pageSize: 50,
      renderContentTheme: 'wechat',
      renderLineNumber: true,
      renderAutoSpace: true,
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
