import { describe, expect, it } from 'vitest'
import {
  SETTINGS_SECTIONS,
  isScrollableOverflow,
  resolveActiveSection,
  resolveScrollContainer,
  serializeForComparison,
} from './settings-navigation'

describe('settings navigation', () => {
  const offsets = [0, 400, 900, 1400]

  it('keeps the first section active before the first boundary', () => {
    expect(
      resolveActiveSection({ offsets, scrollTop: 0, viewportHeight: 800, scrollHeight: 4000 }),
    ).toBe(0)
  })

  it('switches when a section top crosses the threshold line', () => {
    // 判定线 = scrollTop + 120，取最后一个「顶部已越线」的分组（offsets = [0,400,900,1400]）。
    const active = (scrollTop: number) =>
      resolveActiveSection({ offsets, scrollTop, viewportHeight: 800, scrollHeight: 4000 })

    expect(active(280)).toBe(1) // 线 400，压住 offsets[1]
    expect(active(500)).toBe(1) // 线 620，还没到 offsets[2]
    expect(active(780)).toBe(2) // 线 900，压住 offsets[2]
    expect(active(1000)).toBe(2) // 线 1120，还没到 offsets[3]
    expect(active(1290)).toBe(3) // 线 1410，压住 offsets[3]
  })

  it('jumps to the last section when the page is scrolled to the bottom', () => {
    // 末尾分组很短，顶部可能一直越不过判定线，滚到底就该高亮它
    expect(
      resolveActiveSection({ offsets, scrollTop: 3100, viewportHeight: 900, scrollHeight: 4000 }),
    ).toBe(3)
  })

  it('honours a custom threshold', () => {
    // 判定线 = scrollTop + 200。300 → 500 够不到 offsets[2]（900）
    expect(
      resolveActiveSection({
        offsets,
        scrollTop: 300,
        viewportHeight: 800,
        scrollHeight: 4000,
        threshold: 200,
      }),
    ).toBe(1)
    // 700 → 900 才压住 offsets[2]
    expect(
      resolveActiveSection({
        offsets,
        scrollTop: 700,
        viewportHeight: 800,
        scrollHeight: 4000,
        threshold: 200,
      }),
    ).toBe(2)
  })

  it('returns -1 when there are no sections yet', () => {
    expect(
      resolveActiveSection({ offsets: [], scrollTop: 0, viewportHeight: 800, scrollHeight: 800 }),
    ).toBe(-1)
  })

  it('does not treat a fully visible page as scrolled to the bottom', () => {
    // 整页装得下视口时没有滚动可言，不该高亮最后一个分组。
    expect(
      resolveActiveSection({ offsets, scrollTop: 0, viewportHeight: 1200, scrollHeight: 1100 }),
    ).toBe(0)
  })

  it('detects scrollable overflow values', () => {
    expect(isScrollableOverflow('auto')).toBe(true)
    expect(isScrollableOverflow('scroll')).toBe(true)
    expect(isScrollableOverflow('overlay')).toBe(true)
    expect(isScrollableOverflow('visible')).toBe(false)
    expect(isScrollableOverflow('hidden')).toBe(false)
    expect(isScrollableOverflow(null)).toBe(false)
  })

  it('falls back to the document element when nothing scrolls', () => {
    const documentElement = {} as Element
    expect(resolveScrollContainer(null, documentElement)).toBe(documentElement)
  })

  it('exposes a section list with unique ids', () => {
    const ids = SETTINGS_SECTIONS.map((section) => section.id)
    expect(new Set(ids).size).toBe(ids.length)
    expect(ids).toContain('section-media')
  })

  it('compares settings ignoring key order but not values', () => {
    expect(serializeForComparison({ a: 1, b: [2, 3] })).toBe(
      serializeForComparison({ b: [2, 3], a: 1 }),
    )
    expect(serializeForComparison({ a: 1 })).not.toBe(serializeForComparison({ a: 2 }))
    expect(serializeForComparison({ a: [1, 2] })).not.toBe(serializeForComparison({ a: [2, 1] }))
    expect(serializeForComparison({ a: null })).toBe(serializeForComparison({ a: null }))
  })
})
