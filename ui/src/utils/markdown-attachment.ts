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

/**
 * 附件缺少标题时用 URL 末段兜底。附件 URL 常带 `?X-Amz-Signature=…` 之类的查询串，
 * 必须先剥掉再取文件名，否则标签会拖着一长串签名参数。
 */
export function resolveAttachmentLabel(
  label: string | null | undefined,
  url: string,
  fallback = '附件',
): string {
  const trimmed = (label ?? '').trim()
  if (trimmed) {
    return trimmed
  }

  const path = (url || '').split('#')[0].split('?')[0]
  const lastSegment = path.split('/').pop() || ''
  let decoded = lastSegment
  try {
    decoded = decodeURIComponent(lastSegment)
  } catch {
    // 非法百分号编码，退回原文。
  }
  return decoded.trim() || fallback
}

export interface MarkdownImageOptions {
  width?: number | null
  align?: '' | 'left' | 'center' | 'right' | null
  pad?: number | null
}

function normalizeImageWidth(width?: number | null): number | undefined {
  if (typeof width !== 'number' || !Number.isFinite(width)) {
    return undefined
  }

  const rounded = Math.round(width)
  if (rounded < 1 || rounded > 100) {
    return undefined
  }

  return rounded
}

function normalizeImageAlignment(
  align?: MarkdownImageOptions['align'],
): 'left' | 'center' | 'right' | undefined {
  return align === 'left' || align === 'center' || align === 'right' ? align : undefined
}

function normalizeImagePadding(pad?: number | null): number | undefined {
  if (typeof pad !== 'number' || !Number.isFinite(pad)) {
    return undefined
  }

  const rounded = Math.round(pad)
  if (rounded < 0) {
    return undefined
  }

  return rounded
}

function appendHashParams(url: string, params: string[]): string {
  if (!params.length) {
    return url
  }

  const hashIndex = url.indexOf('#')
  if (hashIndex === -1) {
    return `${url}#${params.join('&')}`
  }

  const suffix = url.slice(hashIndex + 1)
  if (!suffix) {
    return `${url}${params.join('&')}`
  }

  return `${url}&${params.join('&')}`
}

export function encodeMarkdownDestination(
  url: string,
  imageOptions?: MarkdownImageOptions,
): string {
  const normalized = normalizeAttachmentUrl(url)
  if (!imageOptions) {
    return normalized
  }

  const params: string[] = []
  const width = normalizeImageWidth(imageOptions.width)
  const align = normalizeImageAlignment(imageOptions.align)
  const pad = normalizeImagePadding(imageOptions.pad)

  if (width !== undefined) {
    params.push(`md-width=${encodeURIComponent(String(width))}`)
  }
  if (align) {
    params.push(`md-align=${encodeURIComponent(align)}`)
  }
  if (pad !== undefined) {
    params.push(`md-pad=${encodeURIComponent(String(pad))}`)
  }

  return appendHashParams(normalized, params)
}

export function buildMarkdownAttachment(
  label: string,
  url: string,
  type: 'image' | 'link',
  imageOptions?: MarkdownImageOptions,
): string {
  const safeLabel = escapeMarkdownLabel(label)
  const safeUrl =
    type === 'image' ? encodeMarkdownDestination(url, imageOptions) : encodeMarkdownDestination(url)
  return type === 'image' ? `![${safeLabel}](${safeUrl})` : `[${safeLabel}](${safeUrl})`
}
