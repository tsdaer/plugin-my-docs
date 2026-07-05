import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  buildMarkdownAttachment,
  encodeMarkdownDestination,
  escapeMarkdownLabel,
  normalizeAttachmentUrl,
} from './markdown-attachment'

describe('markdown attachment utils', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('normalizes relative urls to absolute urls', () => {
    vi.stubGlobal('window', {
      location: {
        origin: 'https://example.com',
      },
    })

    expect(normalizeAttachmentUrl('/upload/image.png')).toBe('https://example.com/upload/image.png')
    expect(normalizeAttachmentUrl('upload/file.pdf')).toBe('https://example.com/upload/file.pdf')
  })

  it('escapes markdown label control characters', () => {
    expect(escapeMarkdownLabel('[demo]\\name')).toBe('\\[demo\\]\\\\name')
  })

  it('builds commonmark-safe attachment markdown', () => {
    vi.stubGlobal('window', {
      location: {
        origin: 'https://example.com',
      },
    })

    expect(buildMarkdownAttachment('image', '/upload/a (1).png', 'image')).toBe(
      '![image](https://example.com/upload/a%20(1).png)',
    )
    expect(buildMarkdownAttachment('file', 'upload/read me.pdf', 'link')).toBe(
      '[file](https://example.com/upload/read%20me.pdf)',
    )
  })

  it('appends image rendering params in the destination fragment', () => {
    vi.stubGlobal('window', {
      location: {
        origin: 'https://example.com',
      },
    })

    expect(
      buildMarkdownAttachment('image', '/upload/hero.png', 'image', {
        width: 50,
        align: 'center',
        pad: 16,
      }),
    ).toBe('![image](https://example.com/upload/hero.png#md-width=50&md-align=center&md-pad=16)')
    expect(
      encodeMarkdownDestination('https://cdn.example.com/a.png#preview', {
        align: 'right',
      }),
    ).toBe('https://cdn.example.com/a.png#preview&md-align=right')
  })
})
