/**
 * my-docs 前台正文增强：复制按钮（代码块 / 图片 / 块级公式）、图片灯箱、
 * 内嵌视频 DPlayer 增强、图表主题重渲染。
 *
 * 由 docs/detail.html 引入，配合 window.Vditor（method.min.js）与
 * modules/theme.html 派发的 mdocs:scheme-change 事件工作。
 */
(function () {
      'use strict';

      /** Vditor 各图表渲染器：type 对应 .language-{type} 代码块，themed 表示接受明暗参数。 */
      var CHART_RENDERERS = [
        { type: 'mermaid', render: 'mermaidRender', themed: true },
        { type: 'echarts', render: 'chartRender', themed: true },
        { type: 'flowchart', render: 'flowchartRender', themed: false },
        { type: 'graphviz', render: 'graphvizRender', themed: false },
        { type: 'mindmap', render: 'mindmapRender', themed: true },
        { type: 'markmap', render: 'markmapRender', themed: false },
        { type: 'plantuml', render: 'plantumlRender', themed: false },
        { type: 'abc', render: 'abcRender', themed: false },
        { type: 'smiles', render: 'SMILESRender', themed: true },
      ];

      var IMAGE_EXTENSIONS = ['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg', 'avif', 'bmp', 'ico'];
      var HLS_SUFFIX = '.m3u8';
      var DPLAYER_SCRIPT_BASE = '/plugins/my-docs/assets/static/dplayer/';

      var ICONS = {
        copy:
          '<svg class="mdocs-icon-copy" viewBox="0 0 24 24" fill="none" stroke="currentColor" ' +
          'stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
          '<rect x="9" y="9" width="13" height="13" rx="2"/>' +
          '<path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>',
        check:
          '<svg class="mdocs-icon-check" viewBox="0 0 24 24" fill="none" stroke="currentColor" ' +
          'stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
          '<polyline points="20 6 9 17 4 12"/></svg>',
      };

      var scriptPromises = {};
      var dplayerReady = null;
      var rerenderTimer = null;
      var toastTimer = null;

      window.MdocsEnhance = {
        prepareCharts: prepareCharts,
        mount: mount,
        rerenderCharts: rerenderCharts,
      };

      /** 在 Vditor 渲染前为图表代码块包上容器并保存初始 HTML，供主题切换时恢复重渲。 */
      function prepareCharts(root) {
        if (!root) {
          return;
        }
        CHART_RENDERERS.forEach(function (entry) {
          Array.prototype.slice
            .call(root.querySelectorAll('.language-' + entry.type))
            .forEach(function (node) {
              var parent = node.parentElement;
              if (!parent || parent.classList.contains('mdocs-chart')) {
                return;
              }
              // 围栏图表块输出 <pre><code class="language-x">，Vditor 会在内部替换结构，
              // 必须包住最外层 pre 才能整体恢复。
              var target = parent.tagName === 'PRE' ? parent : node;
              if (target.classList.contains('mdocs-chart')) {
                return;
              }
              var wrapper = root.ownerDocument.createElement('div');
              wrapper.className = 'mdocs-chart';
              wrapper.setAttribute('data-mdocs-chart', entry.type);
              wrapper.setAttribute('data-mdocs-source', target.outerHTML);
              target.replaceWith(wrapper);
              wrapper.appendChild(target);
            });
        });
      }

      /**
       * Vditor / 公式渲染完成后挂载交互：复制按钮、图片灯箱、DPlayer 增强。
       * options: { copyButtons: boolean, imageZoom: boolean }
       */
      function mount(root, options) {
        if (!root) {
          return;
        }
        options = options || {};
        mountCodeCopy(root, options);
        mountImages(root, options);
        mountBlockFormulas(root, options);
        enhanceVideos(root);
        scheduleEchartsPolish(root, currentScheme(), 0);
      }

      /** mdocs:scheme-change 后重渲染全部图表（防抖），并按新主题色修正 ECharts。 */
      function rerenderCharts(root, scheme, cdn) {
        if (!root) {
          return;
        }
        window.clearTimeout(rerenderTimer);
        rerenderTimer = window.setTimeout(function () {
          rerenderChartsNow(root, scheme === 'dark' ? 'dark' : 'light', cdn);
        }, 150);
      }

      function rerenderChartsNow(root, scheme, cdn) {
        if (!window.Vditor) {
          return;
        }
        var wrappers = root.querySelectorAll('.mdocs-chart');
        if (wrappers.length === 0) {
          return;
        }
        var present = {};
        Array.prototype.forEach.call(wrappers, function (wrapper) {
          var type = wrapper.getAttribute('data-mdocs-chart');
          var source = wrapper.getAttribute('data-mdocs-source');
          if (!type || source === null) {
            return;
          }
          if (type === 'echarts') {
            disposeEchart(wrapper);
          }
          wrapper.innerHTML = source;
          present[type] = true;
        });
        CHART_RENDERERS.forEach(function (entry) {
          if (!present[entry.type] || typeof window.Vditor[entry.render] !== 'function') {
            return;
          }
          try {
            if (entry.themed) {
              window.Vditor[entry.render](root, cdn, scheme);
            } else {
              window.Vditor[entry.render](root, cdn);
            }
          } catch (error) {
            console.warn('[my-docs] Chart re-render failed: ' + entry.type, error);
          }
        });
        if (present.echarts) {
          scheduleEchartsPolish(root, scheme, 0);
        }
      }

      // ---------------------------------------------------------------- 复制

      function mountCodeCopy(root, options) {
        if (!options.copyButtons) {
          return;
        }
        // Vditor codeRender 自带复制按钮，统一替换为插件的按钮样式。
        Array.prototype.forEach.call(root.querySelectorAll('.vditor-copy'), function (node) {
          node.remove();
        });
        Array.prototype.forEach.call(root.querySelectorAll('pre'), function (pre) {
          if (pre.classList.contains('mdocs-code') || pre.querySelector('.mdocs-action-btn')) {
            return;
          }
          var code = pre.querySelector('code');
          if (!code) {
            return;
          }
          pre.classList.add('mdocs-code');
          pre.appendChild(createCopyButton('复制代码', function () {
            return copyText(codeText(code));
          }));
        });
      }

      /** 行号等高亮装饰的空 span 不参与文本，优先取原始 textContent。 */
      function codeText(code) {
        return code.textContent || '';
      }

      function mountImages(root, options) {
        var hasZoomable = false;
        Array.prototype.forEach.call(root.querySelectorAll('img'), function (img) {
          if (img.dataset.mdocsMedia) {
            return;
          }
          img.dataset.mdocsMedia = '1';
          var link = img.closest ? img.closest('a') : null;
          var zoomSrc = null;
          if (link) {
            // 链接指向图片时放大原图，指向普通页面则尊重链接、不做任何增强。
            if (isImageHref(link.getAttribute('href'))) {
              zoomSrc = link.href;
            }
          } else {
            zoomSrc = img.currentSrc || img.src;
          }

          var wrapper = root.ownerDocument.createElement('span');
          wrapper.className = 'mdocs-media';
          img.replaceWith(wrapper);
          wrapper.appendChild(img);

          if (zoomSrc && options.imageZoom) {
            img.classList.add('mdocs-zoomable');
            img.setAttribute('data-mdocs-zoom-src', zoomSrc);
            hasZoomable = true;
          }
          if (options.copyButtons) {
            wrapper.appendChild(createCopyButton('复制图片', function () {
              return copyImage(img);
            }));
          }
        });

        if (hasZoomable && options.imageZoom) {
          root.addEventListener('click', function (event) {
            var target = event.target;
            var img = target && target.closest
              ? target.closest('img.mdocs-zoomable') : null;
            if (!img) {
              return;
            }
            event.preventDefault();
            openLightbox(img.getAttribute('data-mdocs-zoom-src') || img.src, img.alt);
          });
        }
      }

      /** 仅块级公式挂复制按钮（复制 LaTeX 源码），行内公式不加以免破坏排版。 */
      function mountBlockFormulas(root, options) {
        if (!options.copyButtons) {
          return;
        }
        Array.prototype.forEach.call(
          root.querySelectorAll('ratex-formula.mdocs-ratex--block'),
          function (formula) {
            if (formula.dataset.mdocsMedia) {
              return;
            }
            formula.dataset.mdocsMedia = '1';
            wrapFormula(root, formula,
              formula.getAttribute('latex') || formula.getAttribute('aria-label') || '');
          }
        );
        // KaTeX 兜底路径：块级公式固定输出 .katex-display，原始 TeX 在 MathML 注解里。
        Array.prototype.forEach.call(root.querySelectorAll('.katex-display'), function (formula) {
          if (formula.dataset.mdocsMedia) {
            return;
          }
          var parent = formula.parentElement;
          if (parent && parent.classList.contains('mdocs-math')) {
            return;
          }
          formula.dataset.mdocsMedia = '1';
          var annotation = formula.querySelector('annotation[encoding="application/x-tex"]');
          wrapFormula(root, formula,
            annotation ? annotation.textContent : formula.textContent);
        });
      }

      function wrapFormula(root, formula, latex) {
        var wrapper = root.ownerDocument.createElement('div');
        wrapper.className = 'mdocs-math';
        formula.replaceWith(wrapper);
        wrapper.appendChild(formula);
        wrapper.appendChild(createCopyButton('复制公式', function () {
          return copyText(latex);
        }));
      }

      function createCopyButton(label, action) {
        var button = document.createElement('button');
        button.type = 'button';
        button.className = 'mdocs-action-btn';
        button.title = label;
        button.setAttribute('aria-label', label);
        button.innerHTML = ICONS.copy + ICONS.check;
        button.addEventListener('click', function (event) {
          event.preventDefault();
          event.stopPropagation();
          Promise.resolve().then(action).then(function () {
            button.classList.add('is-success');
            window.setTimeout(function () {
              button.classList.remove('is-success');
            }, 1200);
          }).catch(function (error) {
            console.warn('[my-docs] Copy failed.', error);
            toast('复制失败，请重试');
          });
        });
        return button;
      }

      function copyText(value) {
        var text = String(value == null ? '' : value);
        if (navigator.clipboard && window.isSecureContext) {
          return navigator.clipboard.writeText(text).catch(function () {
            return legacyCopy(text);
          });
        }
        return Promise.resolve(legacyCopy(text));
      }

      function legacyCopy(text) {
        var textarea = document.createElement('textarea');
        textarea.value = text;
        textarea.setAttribute('readonly', '');
        textarea.style.position = 'fixed';
        textarea.style.opacity = '0';
        document.body.appendChild(textarea);
        textarea.select();
        var succeeded = false;
        try {
          succeeded = document.execCommand('copy');
        } catch (error) {
          succeeded = false;
        }
        textarea.remove();
        if (!succeeded) {
          throw new Error('Clipboard is unavailable');
        }
      }

      /** 复制图片本体：转 PNG 后写剪贴板；跨域等失败时退化为复制图片链接。 */
      function copyImage(img) {
        var url = img.currentSrc || img.src;
        var chain = Promise.resolve().then(function () {
          return fetch(url).then(function (response) {
            if (!response.ok) {
              throw new Error('HTTP ' + response.status);
            }
            return response.blob();
          });
        }).then(function (blob) {
          if (blob.type === 'image/png') {
            return blob;
          }
          return toPngBlob(img).then(function (converted) {
            if (!converted) {
              throw new Error('PNG conversion unsupported');
            }
            return converted;
          });
        }).then(function (blob) {
          if (!navigator.clipboard || typeof ClipboardItem !== 'function') {
            throw new Error('ClipboardItem unsupported');
          }
          return navigator.clipboard.write([new ClipboardItem({ 'image/png': blob })]);
        });
        return chain.catch(function (error) {
          console.warn('[my-docs] Copying image fallbacks to its URL.', error);
          return copyText(url).then(function () {
            toast('图片复制失败，已复制图片链接');
          });
        });
      }

      /** 非位图（jpeg/webp 等）经 canvas 转码为 PNG；SVG 或未解码完成时返回 null。 */
      function toPngBlob(img) {
        return new Promise(function (resolve) {
          if (!img.naturalWidth || !img.naturalHeight || /image\/svg/i.test(img.src || '')) {
            resolve(null);
            return;
          }
          var canvas = document.createElement('canvas');
          canvas.width = img.naturalWidth;
          canvas.height = img.naturalHeight;
          var context = canvas.getContext('2d');
          if (!context) {
            resolve(null);
            return;
          }
          try {
            context.drawImage(img, 0, 0);
          } catch (error) {
            resolve(null);
            return;
          }
          canvas.toBlob(function (blob) {
            resolve(blob);
          }, 'image/png');
        });
      }

      // ---------------------------------------------------------------- 灯箱

      function openLightbox(src, alt) {
        if (!src) {
          return;
        }
        var overlay = document.createElement('div');
        overlay.className = 'mdocs-lightbox is-loading';
        overlay.innerHTML =
          '<button type="button" class="mdocs-lightbox-close" aria-label="关闭">' +
          '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" ' +
          'stroke-linecap="round" aria-hidden="true"><line x1="18" y1="6" x2="6" y2="18"/>' +
          '<line x1="6" y1="6" x2="18" y2="18"/></svg></button>' +
          '<img class="mdocs-lightbox-image" alt="">';
        var image = overlay.querySelector('img');
        image.src = src;
        if (alt) {
          image.alt = alt;
        }

        function close() {
          document.removeEventListener('keydown', onKeydown, true);
          overlay.remove();
          document.body.classList.remove('mdocs-lightbox-open');
        }

        function onKeydown(event) {
          if (event.key === 'Escape') {
            close();
          }
        }

        overlay.addEventListener('click', function (event) {
          // 点击图片本身不关闭，避免查看细节时误触；遮罩与关闭按钮均可关闭。
          if (event.target !== image) {
            close();
          }
        });
        image.addEventListener('load', function () {
          overlay.classList.remove('is-loading');
        });
        image.addEventListener('error', function () {
          overlay.classList.remove('is-loading');
          overlay.classList.add('is-error');
        });
        document.addEventListener('keydown', onKeydown, true);
        document.body.classList.add('mdocs-lightbox-open');
        document.body.appendChild(overlay);
      }

      // ---------------------------------------------------------------- 视频

      function enhanceVideos(root) {
        var videos = root.querySelectorAll('video.mdocs-video');
        if (videos.length === 0) {
          return;
        }
        loadPlayer(videos).then(function (DPlayer) {
          Array.prototype.forEach.call(videos, function (video) {
            if (video.dataset.mdocsPlayer) {
              return;
            }
            video.dataset.mdocsPlayer = '1';
            initDPlayer(root, video, DPlayer);
          });
        }).catch(function (error) {
          console.warn('[my-docs] DPlayer 加载失败，保留原生播放器。', error);
        });
      }

      /** 页面含 m3u8 时先加载 hls.js（DPlayer 依赖全局 Hls），再加载 DPlayer。 */
      function loadPlayer(videos) {
        var needsHls = Array.prototype.some.call(videos, function (video) {
          return isHlsSource(videoSrc(video));
        });
        var chain = needsHls
          ? loadScript(DPLAYER_SCRIPT_BASE + 'hls.min.js')
          : Promise.resolve();
        dplayerReady = dplayerReady || chain.then(function () {
          return loadScript(DPLAYER_SCRIPT_BASE + 'DPlayer.min.js');
        }).then(function () {
          if (!window.DPlayer) {
            throw new Error('DPlayer is unavailable');
          }
          return window.DPlayer;
        });
        return dplayerReady;
      }

      function initDPlayer(root, video, DPlayer) {
        var url = videoSrc(video);
        if (!url) {
          return;
        }
        var container = root.ownerDocument.createElement('div');
        container.className = 'mdocs-dplayer';
        var style = video.getAttribute('style');
        if (style) {
          container.setAttribute('style', style);
        }
        video.replaceWith(container);
        try {
          new DPlayer({
            container: container,
            theme: accentColor(),
            lang: 'zh-cn',
            volume: 0.7,
            hotkey: false,
            video: { url: url, type: isHlsSource(url) ? 'hls' : 'auto' },
          });
          // 按视频真实比例修正容器宽高比，避免 16:9 兜底在竖屏视频上下留黑边。
          var media = container.querySelector('video');
          if (media) {
            media.addEventListener('loadedmetadata', function () {
              if (media.videoWidth && media.videoHeight) {
                container.style.aspectRatio = media.videoWidth + ' / ' + media.videoHeight;
              }
            });
          }
        } catch (error) {
          console.warn('[my-docs] DPlayer 初始化失败，回退原生播放器。', error);
          container.replaceWith(video);
        }
      }

      function videoSrc(video) {
        var source = video.querySelector('source[src]');
        return video.getAttribute('src') || (source ? source.getAttribute('src') : '');
      }

      function loadScript(src) {
        if (!scriptPromises[src]) {
          scriptPromises[src] = new Promise(function (resolve, reject) {
            var script = document.createElement('script');
            script.src = src;
            script.async = true;
            script.onload = function () {
              resolve();
            };
            script.onerror = function () {
              delete scriptPromises[src];
              reject(new Error('Failed to load ' + src));
            };
            document.head.appendChild(script);
          });
        }
        return scriptPromises[src];
      }

      // ------------------------------------------------------------ 主题配色

      function currentScheme() {
        return document.documentElement.getAttribute('data-mdocs-effective-scheme') === 'dark'
          ? 'dark' : 'light';
      }

      function accentColor() {
        var value = window.getComputedStyle(document.documentElement)
          .getPropertyValue('--mdocs-accent').trim();
        return /^#[0-9a-fA-F]{3,8}$/.test(value) ? value : '#0f766e';
      }

      /** ECharts 暗色主题自带不透明底色且默认色板与主题无关，渲染后统一修正。 */
      function scheduleEchartsPolish(root, scheme, attempt) {
        window.setTimeout(function () {
          var pending = polishEcharts(root, scheme);
          if (pending > 0 && attempt < 12) {
            scheduleEchartsPolish(root, scheme, attempt + 1);
          }
        }, attempt === 0 ? 200 : 350);
      }

      function polishEcharts(root, scheme) {
        var wrappers = root.querySelectorAll(
          '.mdocs-chart[data-mdocs-chart="echarts"], .mdocs-chart[data-mdocs-chart="mindmap"]');
        if (wrappers.length === 0) {
          return 0;
        }
        if (!window.echarts || typeof window.echarts.getInstanceByDom !== 'function') {
          return wrappers.length;
        }
        var pending = 0;
        var palette = null;
        Array.prototype.forEach.call(wrappers, function (wrapper) {
          if (wrapper.getAttribute('data-mdocs-polished') === scheme) {
            return;
          }
          var instance = findEchartInstance(wrapper);
          if (!instance) {
            pending += 1;
            return;
          }
          if (!palette) {
            palette = echartsPalette(accentColor(), scheme);
          }
          try {
            var option = { backgroundColor: 'transparent', color: palette };
            if (wrapper.getAttribute('data-mdocs-chart') === 'mindmap') {
              // mindmap 的节点标签是固定浅色底，改随明暗模式。
              option.series = [{
                label: {
                  backgroundColor: 'transparent',
                  borderColor: scheme === 'dark' ? '#334155' : '#e2e8f0',
                  color: scheme === 'dark' ? '#e5edf7' : '#1f2937',
                },
                lineStyle: { color: scheme === 'dark' ? '#334155' : '#d1d5da' },
              }];
            }
            instance.setOption(option);
            wrapper.setAttribute('data-mdocs-polished', scheme);
          } catch (error) {
            console.warn('[my-docs] ECharts theme polish failed.', error);
          }
        });
        return pending;
      }

      /** Vditor 渲染后图表源码仍在 pre 内；ECharts 直接 init 在 code 元素上，
          实例 id 记录在 DOM 属性里，优先按属性定位、失败再全量向下探测。 */
      function findEchartInstance(wrapper) {
        if (!window.echarts || typeof window.echarts.getInstanceByDom !== 'function') {
          return null;
        }
        var host = wrapper.querySelector('[_echarts_instance_]');
        if (host) {
          return window.echarts.getInstanceByDom(host);
        }
        var candidates = wrapper.querySelectorAll('*');
        for (var index = 0; index < candidates.length; index += 1) {
          try {
            var instance = window.echarts.getInstanceByDom(candidates[index]);
            if (instance) {
              return instance;
            }
          } catch (error) {
            // 目标节点不是 ECharts 容器，继续向下找。
          }
        }
        return null;
      }

      function disposeEchart(wrapper) {
        if (!window.echarts || typeof window.echarts.getInstanceByDom !== 'function') {
          return;
        }
        try {
          var instance = findEchartInstance(wrapper);
          if (instance) {
            instance.dispose();
          }
        } catch (error) {
          // 实例可能已被 Vditor 重建流程处理，忽略即可。
        }
      }

      /** 以主题 accent 色为主色的 ECharts 色板：色相偏移生成整组和谐配色。 */
      function echartsPalette(accent, scheme) {
        var base = hexToHsl(accent);
        if (!base) {
          return undefined;
        }
        var hueOffsets = [0, 28, -32, 64, -96, 150, -150, 100];
        return hueOffsets.map(function (offset, index) {
          var hue = (base.h + offset + 360) % 360;
          var saturation = clamp(base.s + (index === 0 ? 0 : 6), 30, 92);
          var lightness = base.l;
          if (index > 0) {
            lightness += (index % 2 === 1 ? 1 : -1) * 5 * Math.ceil(index / 2);
          }
          if (scheme === 'dark') {
            lightness = index === 0 ? Math.max(lightness, 62) : Math.max(lightness, 48);
          } else {
            lightness = index === 0 ? Math.min(lightness, 46) : Math.min(lightness, 62);
          }
          return hslToHex(hue, saturation, clamp(lightness, 26, 80));
        });
      }

      function hexToHsl(value) {
        var normalized = /^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$/.test(value) ? value : '';
        if (!normalized) {
          return null;
        }
        var hex = normalized.slice(1);
        if (hex.length === 3) {
          hex = hex[0] + hex[0] + hex[1] + hex[1] + hex[2] + hex[2];
        }
        var red = parseInt(hex.slice(0, 2), 16) / 255;
        var green = parseInt(hex.slice(2, 4), 16) / 255;
        var blue = parseInt(hex.slice(4, 6), 16) / 255;
        var max = Math.max(red, green, blue);
        var min = Math.min(red, green, blue);
        var lightness = (max + min) / 2;
        var hue = 0;
        var saturation = 0;
        if (max !== min) {
          var delta = max - min;
          saturation = lightness > 0.5 ? delta / (2 - max - min) : delta / (max + min);
          if (max === red) {
            hue = ((green - blue) / delta + (green < blue ? 6 : 0)) * 60;
          } else if (max === green) {
            hue = ((blue - red) / delta + 2) * 60;
          } else {
            hue = ((red - green) / delta + 4) * 60;
          }
        }
        return { h: hue, s: saturation * 100, l: lightness * 100 };
      }

      function hslToHex(hue, saturation, lightness) {
        var s = saturation / 100;
        var l = lightness / 100;
        var channel = function (n) {
          var k = (n + hue / 30) % 12;
          var a = s * Math.min(l, 1 - l);
          var value = l - a * Math.max(-1, Math.min(k - 3, Math.min(9 - k, 1)));
          return Math.round(value * 255).toString(16).padStart(2, '0');
        };
        return '#' + channel(0) + channel(8) + channel(4);
      }

      function clamp(value, min, max) {
        return Math.max(min, Math.min(max, value));
      }

      // ---------------------------------------------------------------- 工具

      function isImageHref(href) {
        if (!href) {
          return false;
        }
        var path = href.split('#')[0].split('?')[0];
        var dotIndex = path.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex === path.length - 1) {
          return false;
        }
        return IMAGE_EXTENSIONS.indexOf(path.slice(dotIndex + 1).toLowerCase()) >= 0;
      }

      function isHlsSource(url) {
        if (!url) {
          return false;
        }
        return url.split('#')[0].split('?')[0].toLowerCase().slice(-HLS_SUFFIX.length)
          === HLS_SUFFIX;
      }

      function toast(message) {
        var existing = document.querySelector('.mdocs-toast');
        if (existing) {
          existing.remove();
        }
        var node = document.createElement('div');
        node.className = 'mdocs-toast';
        node.textContent = message;
        document.body.appendChild(node);
        window.clearTimeout(toastTimer);
        toastTimer = window.setTimeout(function () {
          node.remove();
        }, 2600);
      }
})();
