import { describe, expect, it } from 'vitest'
import { buildMarkdownDocLink } from './doc-link'

describe('doc link utils', () => {
  it('builds relative links for docs in the same library', () => {
    expect(buildMarkdownDocLink('快速开始', 'quick-start')).toBe(
      '[快速开始](./quick-start)',
    )
  })

  it('supports heading anchors and trims leading hash signs', () => {
    expect(buildMarkdownDocLink('安装步骤', 'quick-start', '##install')).toBe(
      '[安装步骤](./quick-start#install)',
    )
  })

  it('escapes markdown control characters in the label', () => {
    expect(buildMarkdownDocLink('文档[一]', 'guide')).toBe('[文档\\[一\\]](./guide)')
  })
})
