/**
 * 前台媒体源判断。
 *
 * 文档正文里的媒体地址可能被服务端改写成同域代理地址
 * （`/apis/api.my-docs.tsdaer.run/v1alpha1/media-proxy?src=…`），
 * 这时扩展名要从 src 参数里的原始地址取，否则 m3u8 会被当成普通视频。
 *
 * 同一份实现也在前台静态脚本 `src/main/resources/static/js/mdocs-enhance.js` 里
 * （静态脚本不经打包，无法 import 本模块），改动时两处要一起改。
 * 代理路径需与后端 `MediaProxyRules.PROXY_PATH` 保持一致。
 */
export const MEDIA_PROXY_PATH = '/apis/api.my-docs.tsdaer.run/v1alpha1/media-proxy'

const MEDIA_PROXY_PREFIX = `${MEDIA_PROXY_PATH}?`
const HLS_SUFFIX = '.m3u8'

export function isMediaProxyUrl(url: string | null | undefined): url is string {
  return typeof url === 'string' && url.startsWith(MEDIA_PROXY_PREFIX)
}

/** 代理地址里被代取的原始地址；不是代理地址时返回原值。 */
export function mediaSourceOf(url: string | null | undefined): string {
  if (!isMediaProxyUrl(url)) {
    return url ?? ''
  }

  const queryWithHash = url.slice(MEDIA_PROXY_PREFIX.length)
  const hashIndex = queryWithHash.indexOf('#')
  const query = hashIndex >= 0 ? queryWithHash.slice(0, hashIndex) : queryWithHash
  for (const pair of query.split('&')) {
    const equals = pair.indexOf('=')
    if (equals <= 0) {
      continue
    }
    if (pair.slice(0, equals) === 'src') {
      try {
        return decodeURIComponent(pair.slice(equals + 1))
      } catch {
        return pair.slice(equals + 1)
      }
    }
  }
  return ''
}

/** 剥掉查询串与锚点后的路径，用于按扩展名判断类型。 */
function pathOf(url: string): string {
  return url.split('#')[0].split('?')[0]
}

export function isHlsSource(url: string | null | undefined): boolean {
  const effective = mediaSourceOf(url)
  if (!effective) {
    return false
  }
  return pathOf(effective).toLowerCase().endsWith(HLS_SUFFIX)
}

/** 视频元素上真正参与播放的地址，用于判断是否是 HLS。 */
export function isHlsMediaElement(video: {
  getAttribute(name: string): string | null
  querySelector(selector: string): { getAttribute(name: string): string | null } | null
}): boolean {
  const direct = video.getAttribute('src')
  if (direct) {
    return isHlsSource(direct)
  }
  const source = video.querySelector('source[src]')
  return isHlsSource(source ? source.getAttribute('src') : '')
}
