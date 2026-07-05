export function normalizeAttachmentUrl(url: string): string {
  if (!url) {
    return ''
  }

  try {
    return new URL(url, window.location.origin).toString()
  } catch {
    return url
  }
}

export function escapeMarkdownLabel(label: string): string {
  return label.replace(/\\/g, '\\\\').replace(/\[/g, '\\[').replace(/\]/g, '\\]')
}

export function encodeMarkdownDestination(url: string): string {
  return normalizeAttachmentUrl(url)
}

export function buildMarkdownAttachment(
  label: string,
  url: string,
  type: 'image' | 'link',
): string {
  const safeLabel = escapeMarkdownLabel(label)
  const safeUrl = encodeMarkdownDestination(url)
  return type === 'image' ? `![${safeLabel}](${safeUrl})` : `[${safeLabel}](${safeUrl})`
}
