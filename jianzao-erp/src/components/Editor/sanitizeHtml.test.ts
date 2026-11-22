// @vitest-environment jsdom
import { beforeEach, describe, expect, it } from 'vitest';
import { sanitizeNoticeHtml } from './sanitizeHtml';

describe('sanitizeNoticeHtml', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
  });

  it('preserves legacy Quill formatting, safe links, images and raster base64', () => {
    const clean = sanitizeNoticeHtml(
      '<h2 class="ql-align-center ql-indent-2 evil" style="color: rgb(12, 34, 56); text-align: center; background-image: url(javascript:alert(1))">Legacy <strong>bold</strong></h2>' +
        '<p><a href="https://example.com/path" target="_blank">safe link</a></p>' +
        '<img src="/uploads/notice.png" alt="diagram" width="640">' +
        '<img src="data:image/png;base64,iVBORw0KGgo=" alt="inline">'
    );
    const parsed = new DOMParser().parseFromString(clean, 'text/html');

    expect(parsed.querySelector('h2')?.textContent).toContain('Legacy bold');
    expect(parsed.querySelector('h2')?.getAttribute('class')).toBe('ql-align-center ql-indent-2');
    expect(parsed.querySelector('h2')?.getAttribute('style')).toContain('color: rgb(12, 34, 56)');
    expect(parsed.querySelector('a')?.getAttribute('href')).toBe('https://example.com/path');
    expect(parsed.querySelector('a')?.getAttribute('rel')).toBe('noopener noreferrer');
    expect(parsed.querySelectorAll('img')).toHaveLength(2);
    expect(parsed.querySelector('img')?.getAttribute('width')).toBe('640');
    expect(clean).not.toContain('background-image');
  });

  it('removes scripts, event attributes, dangerous URLs, SVG and video/embed content', () => {
    const clean = sanitizeNoticeHtml(
      '<p onclick="alert(1)">hello</p>' +
        '<script>alert("script-run")</script>' +
        '<img src="javascript:alert(2)" onerror="alert(3)">' +
        '<a href="jav&#x61;script:alert(4)">bad link</a>' +
        '<svg onload="alert(5)"><script>alert(6)</script></svg>' +
        '<iframe src="https://evil.example">frame payload</iframe>' +
        '<video src="https://evil.example/movie.mp4"><source src="x"></video>' +
        '<object data="x"></object>'
    );
    const parsed = new DOMParser().parseFromString(clean, 'text/html');

    expect(parsed.querySelector('p')?.textContent).toBe('hello');
    expect(parsed.querySelector('[onclick], [onerror], [onload]')).toBeNull();
    expect(parsed.querySelector('script, iframe, video, source, svg, object')).toBeNull();
    expect(parsed.querySelector('img')?.hasAttribute('src')).toBe(false);
    expect(parsed.querySelector('a')?.hasAttribute('href')).toBe(false);
    expect(clean).not.toContain('alert(');
    expect(clean).not.toContain('frame payload');
  });

  it('handles missing and non-string legacy values safely', () => {
    expect(sanitizeNoticeHtml(null)).toBe('');
    expect(sanitizeNoticeHtml(undefined)).toBe('');
    expect(sanitizeNoticeHtml(42)).toBe('');
  });
});
