import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  buildMarkdownAttachment,
  encodeMarkdownDestination,
  escapeMarkdownLabel,
  normalizeAttachmentUrl,
  resolveAttachmentLabel,
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

  it('falls back to the file name when the attachment has no title', () => {
    expect(resolveAttachmentLabel('', 'https://example.com/upload/demo.webm')).toBe('demo.webm')
    expect(
      resolveAttachmentLabel(undefined, '/upload/%E6%BC%94%E7%A4%BA%20%E8%A7%86%E9%A2%91.mp4'),
    ).toBe('演示 视频.mp4')
  })

  it('strips query and fragment before reading the file name', () => {
    expect(
      resolveAttachmentLabel(
        null,
        'https://bucket.example.com/SF_VULKAN.webm?X-Amz-Signature=abc#t=10',
      ),
    ).toBe('SF_VULKAN.webm')
    expect(resolveAttachmentLabel('   ', 'https://example.com/upload/')).toBe('附件')
  })

  it('keeps an explicit title and escapes it for markdown', () => {
    expect(resolveAttachmentLabel('  演示[视频]  ', 'https://example.com/a.webm')).toBe('演示[视频]')
  })

  it('builds video attachments as image syntax so the frontend can embed a player', () => {
    vi.stubGlobal('window', {
      location: {
        origin: 'https://example.com',
      },
    })

    expect(buildMarkdownAttachment('demo.webm', '/upload/demo.webm', 'image')).toBe(
      '![demo.webm](https://example.com/upload/demo.webm)',
    )
  })
})
