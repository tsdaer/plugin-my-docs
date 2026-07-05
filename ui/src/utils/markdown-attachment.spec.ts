import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  buildMarkdownAttachment,
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
})
