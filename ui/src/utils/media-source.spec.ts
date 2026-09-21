import { describe, expect, it } from 'vitest'
import {
  MEDIA_PROXY_PATH,
  isHlsMediaElement,
  isHlsSource,
  isMediaProxyUrl,
  mediaSourceOf,
} from './media-source'

const proxyUrl = (source: string) => `${MEDIA_PROXY_PATH}?src=${encodeURIComponent(source)}`

describe('media source utils', () => {
  it('detects proxy urls and reads back the original source', () => {
    expect(isMediaProxyUrl(`${MEDIA_PROXY_PATH}?src=a`)).toBe(true)
    expect(isMediaProxyUrl('https://example.com/a.webm')).toBe(false)
    expect(isMediaProxyUrl(null)).toBe(false)
    expect(isMediaProxyUrl(undefined)).toBe(false)
    expect(mediaSourceOf(proxyUrl('https://media.example.com/live/index.m3u8'))).toBe(
      'https://media.example.com/live/index.m3u8',
    )
    expect(mediaSourceOf(proxyUrl('/upload/local.webm'))).toBe('/upload/local.webm')
    expect(mediaSourceOf('https://media.example.com/a.webm')).toBe(
      'https://media.example.com/a.webm',
    )
  })

  it('treats proxied m3u8 as HLS even though the path has no extension', () => {
    expect(isHlsSource(proxyUrl('https://media.example.com/live/index.m3u8'))).toBe(true)
    expect(isHlsSource(proxyUrl('https://media.example.com/live/index.M3U8?token=1'))).toBe(true)
    expect(isHlsSource(proxyUrl('https://media.example.com/demo.mp4'))).toBe(false)
    expect(isHlsSource('https://media.example.com/live/index.m3u8#t=10')).toBe(true)
    expect(isHlsSource('https://media.example.com/demo.mp4')).toBe(false)
    expect(isHlsSource('')).toBe(false)
    expect(isHlsSource(null)).toBe(false)
  })

  it('reads the effective source from a video element', () => {
    expect(
      isHlsMediaElement({
        getAttribute: (name: string) =>
          name === 'src' ? proxyUrl('https://media.example.com/live/index.m3u8') : null,
        querySelector: () => null,
      }),
    ).toBe(true)

    expect(
      isHlsMediaElement({
        getAttribute: () => null,
        querySelector: (selector: string) =>
          selector === 'source[src]'
            ? { getAttribute: () => 'https://media.example.com/demo.mp4' }
            : null,
      }),
    ).toBe(false)

    expect(isHlsMediaElement({ getAttribute: () => null, querySelector: () => null })).toBe(false)
  })
})
