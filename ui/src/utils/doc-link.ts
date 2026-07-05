function escapeMarkdownLabel(label: string): string {
  return label.replace(/([\\[\]])/g, '\\$1')
}

function encodeDocSlug(slug: string): string {
  return encodeURIComponent(slug.trim()).replace(/%2F/gi, '/')
}

function normalizeAnchor(anchor?: string): string {
  if (!anchor) {
    return ''
  }
  return anchor.trim().replace(/^#+/, '')
}

export function buildMarkdownDocLink(label: string, slug: string, anchor?: string): string {
  const safeLabel = escapeMarkdownLabel(label.trim() || slug.trim())
  const href = `./${encodeDocSlug(slug)}`
  const normalizedAnchor = normalizeAnchor(anchor)

  return normalizedAnchor
    ? `[${safeLabel}](${href}#${normalizedAnchor})`
    : `[${safeLabel}](${href})`
}
